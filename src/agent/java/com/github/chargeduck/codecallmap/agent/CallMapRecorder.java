package com.github.chargeduck.codecallmap.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 调用链路记录门面：组合配置、采样、脱敏与 JSONL 落盘。
 *
 * <p>典型用法：
 * <pre>{@code
 * try (CallMapRecorder recorder = CallMapRecorder.create(projectBaseDir)) {
 *     recorder.spanStart(traceId, spanId, parentId, "UserController", "createUser",
 *             Map.of("username", "zhangsan", "password", "secret123"), "/api/user/create");
 *     // ... 业务执行 ...
 *     recorder.spanEnd(traceId, spanId, 12_345, null, null);
 *     recorder.traceSummary(traceId, 3, 12_345, 2);
 * }
 * }</pre>
 *
 * <p>纯 Java 实现，不依赖 IntelliJ Platform API；采样命中才写入，args 写入前自动脱敏。
 */
public final class CallMapRecorder implements AutoCloseable {

    private final CallMapConfig config;
    private final Sampler sampler;
    private final JsonlWriter writer;

    /** 基于项目目录创建（默认输出 {@code <项目目录>/.callmap}，可被系统属性覆盖）。 */
    public static CallMapRecorder create(Path baseDir) throws IOException {
        return new CallMapRecorder(CallMapConfig.of(baseDir));
    }

    public static CallMapRecorder create(CallMapConfig config) throws IOException {
        return new CallMapRecorder(config);
    }

    private CallMapRecorder(CallMapConfig config) throws IOException {
        this.config = config;
        this.sampler = new Sampler(config.sampleRate());
        this.writer = new JsonlWriter(config.outputDir(), config.maxFileBytes(), config.maxRecords());
    }

    /** 记录 span_start 事件（入参 args 为结构化 Map，写入前自动脱敏）。 */
    public void spanStart(String traceId, String spanId, String parentId,
                          String className, String method,
                          Map<String, ?> args, String path) throws IOException {
        if (!sampler.shouldSample()) {
            return;
        }
        StackTraceElement caller = callerFrame();
        Span span = Span.builder(Span.Type.span_start, traceId, spanId)
                .parentId(parentId)
                .className(className)
                .method(method)
                .args(SensitiveFieldMasker.maskJsonString(Span.argsToJson(args)))
                .path(path)
                .sourceFile(caller != null ? caller.getFileName() : null)
                .lineNumber(caller != null ? caller.getLineNumber() : null)
                .build();
        write(span);
    }

    /** 记录 span_end 事件（入参 args 为预序列化 JSON 字符串，写入前自动脱敏）。 */
    public void spanEnd(String traceId, String spanId,
                        long durationMs, String sql, String exception) throws IOException {
        if (!sampler.shouldSample()) {
            return;
        }
        StackTraceElement caller = callerFrame();
        Span span = Span.builder(Span.Type.span_end, traceId, spanId)
                .durationMs(durationMs)
                .sql(sql)
                .exception(exception)
                .sourceFile(caller != null ? caller.getFileName() : null)
                .lineNumber(caller != null ? caller.getLineNumber() : null)
                .build();
        write(span);
    }

    /** 记录 trace_summary 汇总事件（供 AI 分析整条链路卡点）。 */
    public void traceSummary(String traceId, int spanCount, long totalDurationMs, int slowCount) throws IOException {
        if (!sampler.shouldSample()) {
            return;
        }
        Span span = Span.builder(Span.Type.trace_summary, traceId, traceId)
                .durationMs(totalDurationMs)
                .args("{\"spanCount\":" + spanCount + ",\"slowCount\":" + slowCount + "}")
                .build();
        write(span);
    }

    /**
     * 记录一次插桩自动采集的方法调用（单条 span 即含起止信息）。
     *
     * <p>与手工埋点的 spanStart/spanEnd 两条事件不同，插桩采集在方法出口一次性产出
     * 一条完整 span：既减少一半的落盘量与 IO，也让前端可以直接按记录绘制。
     * {@code type} 仍写作 {@code span_start}，并附带 {@code durationMs}，
     * 前端 {@code buildNodes} 对「同一 spanId 只有一条且已带 durationMs」的情况天然兼容。
     *
     * @param parentSpanId 父 span，根 span 传 null
     * @param startMillis  调用开始的墙上时间（毫秒），用于瀑布图按真实时间轴排布
     * @param durationMs   本次调用耗时（毫秒）
     * @param exception    异常摘要，无异常传 null
     */
    public void recordInstrumentedSpan(String traceId, String spanId, String parentSpanId,
                                       String className, String method, long startMillis,
                                       long durationMs, String exception) throws IOException {
        recordInstrumentedSpan(traceId, spanId, parentSpanId, className, method,
                startMillis, durationMs, null, exception);
    }

    /**
     * 记录插桩 span，并带上附加信息。
     *
     * @param sql 数据库探针采集到的 SQL 语句（其它场景传 null）。
     *            写入前会做敏感字段脱敏，避免把参数值泄露到调用链数据里。
     */
    public void recordInstrumentedSpan(String traceId, String spanId, String parentSpanId,
                                       String className, String method, long startMillis,
                                       long durationMs, String sql, String exception)
            throws IOException {
        recordInstrumentedSpan(traceId, spanId, parentSpanId, className, method,
                startMillis, durationMs, sql, null, exception);
    }

    /**
     * 记录插桩 span，带上 SQL 与方法入参。
     *
     * <h2>入参写到哪里</h2>
     * 入参<b>不再内联进 JSONL</b>，而是写到会话目录下的
     * {@code args/<spanId>.json}，span 里只记录相对路径与字节数。
     * 理由见 {@link JsonlWriter#writeArgs(String, String)}：
     * 既要保证数据完整（不裁剪），又要让 JSONL 保持轻量可逐行解析。
     *
     * @param sql  数据库探针采集到的 SQL（其它场景传 null），写入前脱敏
     * @param args 入参 JSON（由 {@code ArgSerializer} 产出，已做敏感字段脱敏，不做长度裁剪）
     */
    public void recordInstrumentedSpan(String traceId, String spanId, String parentSpanId,
                                       String className, String method, long startMillis,
                                       long durationMs, String sql, String args, String exception)
            throws IOException {
        recordInstrumentedSpan(traceId, spanId, parentSpanId, className, method, startMillis,
                durationMs, sql, null, args, exception);
    }

    /**
     * 记录插桩 span（完整版，带 path）。
     *
     * <h2>path 为什么单独一个参数</h2>
     * 之前把 HTTP 探针的 URI 塞进 {@code sql} 字段（{@code emit} 里
     * 传的是 {@code wrapper.detail}），导致两处错误：
     * <ol>
     *   <li>前端「调用链」下拉靠 {@code span.path} 显示 {@code GET /api/xxx}，
     *       而 path 永远是 null，下拉里只能看到 {@code t-4-50f9c4d2e8e4}
     *       这种内部 id，用户根本认不出是哪个接口；</li>
     *   <li>HTTP 的 URI 被写进了 SQL 列，面板上「SQL」一栏
     *       显示的却是 {@code GET /api/x}。</li>
     * </ol>
     *
     * @param path HTTP 探针采集到的请求标识（如 {@code "GET /api/order"}），
     *             非 HTTP 场景传 null
     */
    public void recordInstrumentedSpan(String traceId, String spanId, String parentSpanId,
                                       String className, String method, long startMillis,
                                       long durationMs, String sql, String path,
                                       String args, String exception)
            throws IOException {
        recordInstrumentedSpan(traceId, spanId, parentSpanId, className, method, startMillis,
                durationMs, sql, path, args, null, exception);
    }

    /**
     * 记录插桩 span（完整版，带 path 与返回值）。
     *
     * <h2>返回值为什么单独外置成文件</h2>
     * 与入参同理但更极端：返回值常是 {@code List<Entity>}，一次几百条，
     * 内联进 JSONL 会让单行达到 MB 级，前端逐行解析会卡死。
     * 写到 {@code ret/<spanId>.json} 后，span 里只留路径与字节数，
     * 需要看时按 spanId 直接定位文件。
     *
     * <h2>为什么只有部分 span 有返回值</h2>
     * 调用方（{@code SpanBridge.emit}）在写盘前已过滤掉「快调用且无异常且非根」的 span，
     * 被丢弃的调用不会走到这里，因此也不会付返回值序列化的开销。
     *
     * @param ret 返回值 JSON（由 {@code ArgSerializer} 产出并脱敏）；void 方法或未采集传 null
     */
    public void recordInstrumentedSpan(String traceId, String spanId, String parentSpanId,
                                       String className, String method, long startMillis,
                                       long durationMs, String sql, String path,
                                       String args, String ret, String exception)
            throws IOException {
        if (!sampler.shouldSample()) {
            return;
        }
        // 【不取 callerFrame】
        //
        // 插桩路径的调用者是探针字节码，栈帧第 4 层永远是 SpanBridge.methodExit，
        // 取出来的 sourceFile/lineNumber 指向探针自己（实测全是
        // SpanBridge.java:82/95）。写进 span 只会让前端跳错位置，
        // 因此这里明确留空，交给前端用 className+method 走后端 PSI 定位。
        // 顺带省掉一次 new Throwable() —— 这条路径是热点中的热点。
        String argsFile = null;
        long argsSize = 0;
        if (args != null && !args.isEmpty()) {
            String masked = SensitiveFieldMasker.maskJsonString(args);
            argsFile = writer.writeArgs(spanId, masked);
            if (argsFile != null) {
                argsSize = masked.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            }
        }
        // 返回值同理外置；脱敏后写盘，span 里只留引用
        String retFile = null;
        long retSize = 0;
        if (ret != null && !ret.isEmpty()) {
            String maskedRet = SensitiveFieldMasker.maskJsonString(ret);
            retFile = writer.writeRet(spanId, maskedRet);
            if (retFile != null) {
                retSize = maskedRet.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            }
        }
        Span span = Span.builder(Span.Type.span_start, traceId, spanId)
                .parentId(parentSpanId)
                .ts(startMillis)
                .className(className)
                .method(method)
                .durationMs(durationMs)
                .sql(sql == null ? null : SensitiveFieldMasker.maskSql(sql))
                .path(path)
                .argsFile(argsFile, argsSize)
                .retFile(retFile, retSize)
                .exception(exception)
                // 不写 sourceFile/lineNumber：插桩路径取不到真实业务行号
                //（详见 callerFrame 的说明），写进去只会让前端跳到 SpanBridge.java。
                // 前端遇到空值会自动改用 className+method 走 PSI 定位。
                .build();
        write(span);
    }

    /**
     * 取调用 spanStart/spanEnd 的业务代码栈帧。
     *
     * <h2>为什么不用 getStackTrace()</h2>
     * {@code Thread.currentThread().getStackTrace()} 要构造整个调用栈数组。
     * 在 Spring Boot 这类深栈场景下（代理 + 拦截器 + 反射能动辄上百帧），
     * 这是每记录一个 span 就要付一次的固定成本。
     *
     * <p>{@code new Throwable()} 只填当前线程栈顶若干帧，
     * JDK 内部走 {@code StackTraceElement[]} 的延迟填充路径，明显更轻。
     *
     * <h2>⚠️ 手工埋点专用，插桩路径不要用它</h2>
     * 只有<b>手工埋点</b>（用户在业务代码里直接调 {@code spanStart}）时，
     * {@code stack[3]} 才真的是业务代码那一帧。
     *
     * <p>插桩路径（{@code SpanBridge.methodExit → emit → recordInstrumentedSpan}）
     * 的调用者是 JVM 生成的探针字节码，{@code stack[3]} 落在
     * {@code SpanBridge.methodExit} 自己身上 —— 实测所有 span 的 sourceFile
     * 都是 {@code SpanBridge.java}、行号 82/95，
     * 前端按它跳转永远跳到探针代码里，用户反馈"跳转行数不对"正是这个原因。
     * 所以插桩路径改为传 null，让前端用 className+method 走后端 PSI 精确定位。
     */
    private static StackTraceElement callerFrame() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        // [0]=callerFrame [1]=recordXxx [2]=emit/bridge [3]=业务代码
        return stack.length > 3 ? stack[3] : null;
    }

    /** 写入任意已构造好的 Span（写入前对 args 做脱敏）。 */
    public void write(Span span) throws IOException {
        if (span == null || !sampler.shouldSample()) {
            return;
        }
        String line = span.toJson();
        // 兼容旧路径：只有显式用 args(...) 内联的 span 才需要这里脱敏。
        // 新采集走 argsFile，入参在写文件前就已完成脱敏（见 recordInstrumentedSpan）。
        if (span.args() != null && !span.args().isEmpty()) {
            line = line.replace("\"args\":" + span.args() + ",", "\"args\":" + SensitiveFieldMasker.maskJsonString(span.args()) + ",");
        }
        writer.write(line);
    }

    /** 入参目录的绝对路径（供 UI/调试信息展示）。 */
    public Path argsDir() {
        return currentFile().getParent().resolve(JsonlWriter.ARGS_DIR);
    }

    /** 慢调用清单的写出缓冲；首次用到时才创建，避免不用时也占着文件句柄。 */
    private java.io.BufferedWriter slowOut;
    /** 本次会话内是否已写过表头。 */
    private boolean slowHeaderWritten;

    /**
     * 记录一次慢调用到独立清单文件 {@code .callmap/slow-calls.txt}。
     *
     * <h2>为什么要单独一个文件</h2>
     * 慢调用信息本来就在 span 的 {@code durationMs} 里，前端可以按阈值筛选。
     * 但排障时更常见的是"事后想 grep 一下哪些方法超了 3 秒"，
     * 而 JSONL 里每行字段很长、还要另外拼条件，直接看一个纯文本清单更省事。
     *
     * <p><b>不打印到控制台</b>：这段信息曾经走 {@code System.err}，
     * 直接混进目标应用的 Terminal，和业务日志交织、还容易被误认成业务报错。
     * 改为只落盘 + 前端高亮。
     *
     * <p>写入失败静默忽略 —— 诊断产物不能影响业务。
     */
    public synchronized void recordSlowCall(String traceId, String spanId, String className,
                                            String method, long durationMs, String thread) {
        try {
            if (slowOut == null) {
                Path dir = config.outputDir();
                Files.createDirectories(dir);
                Path file = dir.resolve("slow-calls.txt");
                slowOut = Files.newBufferedWriter(file, java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            }
            if (!slowHeaderWritten) {
                slowHeaderWritten = true;
                slowOut.write("# CodeCallMap 慢调用清单（阈值以上才记录，仅供排查）");
                slowOut.newLine();
                slowOut.write("# 时间\t耗时\t方法\t线程\ttraceId/spanId");
                slowOut.newLine();
            }
            slowOut.write(java.time.LocalTime.now().withNano(0).toString());
            slowOut.write('\t');
            slowOut.write(durationMs + "ms");
            slowOut.write('\t');
            slowOut.write(className + "#" + method);
            slowOut.write('\t');
            slowOut.write(thread == null ? "-" : thread);
            slowOut.write('\t');
            slowOut.write(traceId + "/" + spanId);
            slowOut.newLine();
            slowOut.flush();
        } catch (Throwable ignored) {
            // 诊断产物写失败不影响业务
        }
    }

    /** 当前分片文件路径（便于日志/UI 展示）。 */
    public Path currentFile() {
        return writer.currentFile();
    }

    public CallMapConfig config() {
        return config;
    }

    @Override
    public void close() throws IOException {
        writer.close();
        // 慢调用清单是独立文件句柄，必须一并关闭，否则 JVM 退出时可能丢最后几行
        java.io.BufferedWriter so = slowOut;
        if (so != null) {
            try {
                so.close();
            } catch (IOException ignored) {
            }
        }
    }
}
