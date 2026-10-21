package com.github.chargeduck.codecallmap.agent;

import com.github.chargeduck.codecallmap.agent.instrument.CallMapTransformer;
import com.github.chargeduck.codecallmap.agent.instrument.InstrumentFilter;
import com.github.chargeduck.codecallmap.agent.instrument.InstrumentReport;
import com.github.chargeduck.codecallmap.agent.instrument.SpanBridge;
import lombok.extern.slf4j.Slf4j;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * javaagent 入口：premain（启动时加载）与 agentmain（运行时 attach）共用初始化逻辑。
 *
 * <p>agentArgs 支持逗号分隔的 key=value，映射到 {@link CallMapConfig} 系统属性，例如：
 * <pre>{@code
 * -javaagent:codeCallMap-agent.jar=output.dir=/var/log/callmap,sample.rate=80,include.packages=com.myapp
 * }</pre>
 *
 * <p>初始化做两件事：
 * <ol>
 *   <li>创建 {@link CallMapRecorder} 负责 JSONL 落盘；</li>
 *   <li>注册 {@link CallMapTransformer}，对命中包前缀的类做方法级字节码插桩，
 *       实现「无需改业务代码的自动调用链采集」。</li>
 * </ol>
 *
 * <p>纯 Java 实现，不依赖 IntelliJ Platform API；加载成功后通过 slf4j 输出确认日志，
 * 并在 JVM 退出时自动关闭记录器（确保 JSONL 完整落盘）。
 */
@Slf4j
public final class AgentBootstrap {

    private static volatile CallMapRecorder recorder;
    private static volatile CallMapTransformer transformer;
    /** 插桩清单：记录实际改造的类与方法，退出时落盘。 */
    private static volatile InstrumentReport report;
    private static volatile boolean initialized;

    private AgentBootstrap() {
    }

    /** JVM 启动时由 {@code -javaagent} 触发。 */
    public static void premain(String agentArgs, Instrumentation inst) {
        init(agentArgs, inst);
    }

    /** 运行时由 Attach API 触发（{@code Agent-Class}）。 */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        init(agentArgs, inst);
    }

    private static void init(String agentArgs, Instrumentation inst) {
        if (initialized) {
            return;
        }
        synchronized (AgentBootstrap.class) {
            if (initialized) {
                return;
            }
            try {
                applyAgentArgs(agentArgs);
                Path baseDir = Paths.get(System.getProperty("user.dir", "."));
                recorder = CallMapRecorder.create(baseDir);
                initialized = true;
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        SpanBridge.deactivate();
                        if (transformer != null) {
                            // 打印 transform 累计耗时：这是判断"是否插桩拖慢启动"的直接证据。
                            // 若该值只有几百毫秒，用户看到的长时间空白就与插桩无关，
                            // 需要抓线程栈看应用在等什么。
                            log.info("[CodeCallMap] 采集统计: 扫描类 {} 个，插桩 {} 个，失败降级 {} 个，"
                                            + "插桩累计耗时 {} ms",
                                    transformer.seenCount(), transformer.instrumentedCount(),
                                    transformer.failedCount(), transformer.transformMillis());
                        }
                        // 插桩清单落盘：用户可打开 .callmap/instrument-report.txt
                        // 查看"到底哪些类/方法被改造了"
                        if (report != null) {
                            report.write();
                            log.info("[CodeCallMap] 插桩清单已写入: {} （{} 个类，{} 个方法）",
                                    recorder != null
                                            ? recorder.config().outputDir()
                                                .resolve("instrument-report.txt")
                                            : "instrument-report.txt",
                                    report.classCount(), report.methodCount());
                        }
                        if (recorder != null) {
                            recorder.close();
                        }
                    } catch (Exception ignored) {
                        // 关闭失败不影响 JVM 退出
                    }
                }, "codecallmap-agent-shutdown"));

                // 诊断产物（停摆线程栈、插桩失败）一律写进 .callmap，不打印到控制台 ——
                // 走 stderr 会混进目标应用的 Terminal，和业务日志交织、影响排障。
                SpanBridge.setDiagnosticDir(recorder.config().outputDir());
                // 先激活探针，再注册 transformer：保证首个被加载的类进来时探针已可用
                SpanBridge.activate(recorder, recorder.config().minDurationMs());

                if (inst != null && recorder.config().instrumentEnabled()) {
                    // 项目根目录：优先用 callmap.project.dir（插件注入），
                    // 否则退回进程工作目录 —— IDEA 启动 Java 应用时通常就是项目根，
                    // 用户把 .callmap/instrument-filter.yml 放在项目里即可生效。
                    String projectDir = System.getProperty("callmap.project.dir");
                    if (projectDir == null || projectDir.isEmpty()) {
                        projectDir = System.getProperty("user.dir");
                    }
                    InstrumentFilter filter = new InstrumentFilter(
                            recorder.config().includePackages(), projectDir);
                    // 插桩清单：把"到底动了哪些类和方法"写到 .callmap/instrument-report.txt，
                    // 用户可据此自查"某方法为何没出现在调用链里"或"启动插了多少类"
                    InstrumentReport rep = new InstrumentReport(recorder.config().outputDir());
                    // 把生效的过滤规则一并写进报告：
                    // 排查"这个方法为什么没插桩"时，只有"插了什么"不够，
                    // 还得知道"是什么规则把它挡掉的"。
                    rep.setFilterSnapshot(filter.effectiveIncludeSpec(),
                            filter.excludeCount(), filter.excludeSample(8));
                    CallMapTransformer t = new CallMapTransformer(filter, rep);
                    inst.addTransformer(t, true);
                    transformer = t;
                    report = rep;
                    log.info("[CodeCallMap] 插桩清单将在 20 秒后写入 {}（运行中即可查看）",
                            recorder.config().outputDir().resolve("instrument-report.txt"));
                    // 启动后 20 秒先落盘一次：此时启动阶段的主要类都已加载完，
                    // 用户不必等到停止就能看到清单。之后只在退出时再写一次。
                    scheduleReportWrite(rep);
                    // 打印"实际生效"的白名单，而不是配置原文 ——
                    // 自动推导场景下两者不同，排查时要看真的生效了什么
                    log.info("[CodeCallMap] 字节码插桩已启用；规则来源={}，采集范围={}，排除 {} 条",
                            filter.configSource(),
                            filter.effectiveIncludeSpec(),
                            filter.excludeCount());
                } else if (inst != null) {
                    log.info("[CodeCallMap] 字节码插桩已关闭（callmap.instrument.enabled=false），"
                            + "仅保留手工埋点采集");
                } else {
                    log.warn("[CodeCallMap] Instrumentation 为 null，无法启用字节码插桩");
                }

                log.info("[CodeCallMap] javaagent 加载成功（premain/agentmain），"
                        + "调用链将录制到: " + recorder.config().outputDir()
                        + "，采样率: " + recorder.config().sampleRate() + "%"
                        + "，最小记录耗时: " + recorder.config().minDurationMs() + "ms");
            } catch (Exception e) {
                log.error("[CodeCallMap] javaagent 初始化失败: {}", e.toString(), e);
            }
        }
    }

    /**
     * 启动 20 秒后把插桩清单落盘一次。
     *
     * <p>为什么不等退出再写：用户排查"为什么这个方法没被记录"时，
     * 应用往往还停在断点上或正在运行，这时候就应该能打开清单查看。
     * 只写一次是因为启动阶段的类加载在 20 秒内基本完成，
     * 之后再写的内容变化不大，反复写盘没有意义。
     */
    private static void scheduleReportWrite(final InstrumentReport rep) {
        if (rep == null) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(20000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                rep.write();
                log.info("[CodeCallMap] 插桩清单已写入（运行中快照）：{} 个类，{} 个方法",
                        rep.classCount(), rep.methodCount());
            }
        }, "codecallmap-report");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 将 agentArgs 中的 key=value 映射为 {@code callmap.*} 系统属性。
     *
     * <p>注意：本类必须以 <b>Java 8 语法</b>书写（见 build.gradle.kts 中 agentJar 的
     * {@code options.release = 8}）。agent jar 由目标应用 JVM 加载，
     * 用户项目可能是 JDK 8/11/17/21 任意版本，因此不能使用 switch 箭头语法、
     * var、isBlank()、List.of() 等新特性。
     */
    private static void applyAgentArgs(String agentArgs) {
        if (agentArgs == null || agentArgs.trim().isEmpty()) {
            return;
        }
        for (String kv : agentArgs.split(",")) {
            int idx = kv.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = kv.substring(0, idx).trim();
            String value = kv.substring(idx + 1).trim();
            // Java 8 兼容写法：传统 switch 语句
            switch (key) {
                case "output.dir":
                    System.setProperty(CallMapConfig.PROP_OUTPUT_DIR, value);
                    break;
                case "sample.rate":
                    System.setProperty(CallMapConfig.PROP_SAMPLE_RATE, value);
                    break;
                case "max.file.bytes":
                    System.setProperty(CallMapConfig.PROP_MAX_FILE_BYTES, value);
                    break;
                case "max.records":
                    System.setProperty(CallMapConfig.PROP_MAX_RECORDS, value);
                    break;
                case "include.packages":
                    System.setProperty(CallMapConfig.PROP_INCLUDE_PACKAGES, value);
                    break;
                case "min.duration.ms":
                    System.setProperty(CallMapConfig.PROP_MIN_DURATION_MS, value);
                    break;
                case "instrument.enabled":
                    System.setProperty(CallMapConfig.PROP_INSTRUMENT_ENABLED, value);
                    break;
                default:
                    System.setProperty("callmap." + key, value);
                    break;
            }
        }
    }
}
