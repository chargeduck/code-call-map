package com.github.chargeduck.codecallmap.exec;

import com.intellij.execution.ExecutionResult;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 借用 IDEA Spring Boot 插件的 {@code SpringBootJmxSetup}，把 JMX 会话挂到本次进程上。
 *
 * <h2>端口号显示到底依赖什么（反编译 2025.3 Spring Boot 插件确认）</h2>
 * <pre>
 * SpringBootJmxSetup.LocalSetup
 *   .setupJmxParameters(javaParameters, LocalTargetEnvironmentRequest)   // 注入 -D 参数
 *   .setupProcessHandler(processHandler)                                  // 把会话登记到 LifecycleManager
 *        ↓
 * SpringBootApplicationLifecycleManager.getApplicationInfo(processHandler)
 *   .getServerPort()   ← LiveProperty，从 SpringApplicationAdminMXBean 读
 *        ↓
 * SpringBootApplicationRunDashboardCustomizer 渲染到 Services / Run 面板
 * </pre>
 *
 * <h2>为什么光加 -D 参数不够（实测两次才定位）</h2>
 * 第二次修复已经让命令行与原生启动<b>逐字一致</b>（5 个 -D 参数全在），
 * 但端口号仍然不出现 —— 因为真正缺的是<b>最后一步 {@code setupProcessHandler} 登记</b>。
 * 没有登记，Dashboard 里的 {@code getServerPort()} 就没有宿主，不会渲染。
 * 而这一步只有 Spring Boot 插件自己的 runner 会做，CCM 用自定义 runner，整条链不触发。
 *
 * <h2>为什么用反射</h2>
 * {@code SpringBootJmxSetup} 是 Spring Boot 插件的内部实现，不是公开 API。
 * 编译期依赖会让 CCM <b>硬依赖</b> Spring Boot 插件（用户不装就加载不了 CCM）。
 * 反射失败时静默降级：只是不显示端口号，不影响采集主流程。
 */
public final class SpringBootJmxBridge {

    private static final Logger LOG = Logger.getInstance(SpringBootJmxBridge.class);

    /**
     * 暂存「已完成参数注入的 setup 实例」，等进程启动后取回、完成登记。
     *
     * <h2>为什么需要这个中转</h2>
     * {@code setupJmxParameters} 与 {@code setupProcessHandler} 必须落在<b>同一个实例</b>上
     * （状态靠实例字段 {@code myJmxPort} 传递）。而前者在 patcher 阶段执行
     * （此时进程尚未创建），后者在 {@code onProcessStarted} 阶段执行 ——
     * 两个阶段之间唯一的联系就是这个暂存位。
     *
     * <h2>为什么可以接受静态字段</h2>
     * IDEA 的启动流程在同一时刻对一个项目只推进一次；
     * 即使真有并发（同时 Run 两个配置），最坏结果是端口号错乱显示，
     * 不影响任何采集或运行行为。相比之下，为这点概率引入 Project 级服务
     * 或 Key 传递会让代码复杂度显著上升，收益不成比例。
     *
     * <p>用 {@link ThreadLocal} 更进一步收窄影响面：平台在同一个线程里
     * 依次完成 patch 与 onProcessStarted 的调用在绝大多数实现中成立，
     * 若不成立也只是退回"不显示端口号"。
     */
    private static final ThreadLocal<Object> PENDING_SETUP = new ThreadLocal<>();

    /** Spring Boot 插件内部类全名（LocalSetup 本身是 public 类 + public 构造器）。 */
    private static final String LOCAL_SETUP = "com.intellij.spring.boot.run.SpringBootJmxSetup$LocalSetup";
    /** 平台的目标环境请求类型（setupJmxParameters 的第二个参数）。 */
    private static final String LOCAL_TARGET_REQUEST = "com.intellij.execution.target.local.LocalTargetEnvironmentRequest";
    /** setupJmxParameters 的第二个参数声明类型。 */
    private static final String TARGET_REQUEST = "com.intellij.execution.target.TargetEnvironmentRequest";

    private SpringBootJmxBridge() {
    }

    /** Spring Boot 插件的 LocalSetup 是否可用（用户是否装了该插件）。 */
    static boolean isAvailable() {
        return loadLocalSetupClass() != null;
    }

    /**
     * 加载 {@code SpringBootJmxSetup$LocalSetup} 类。
     *
     * <h2>为什么要做多路尝试</h2>
     * 插件之间的类加载器默认<b>互相不可见</b>：
     * CCM 的 {@code Class.forName} 用的是自己的 PluginClassLoader，
     * 它只能看到 {@code <depends>} 声明过的插件的类。
     *
     * <p>本项目在 plugin.xml 里已声明
     * {@code <depends optional="true" config-file="codecallmap-springboot.xml">}，
     * 正常情况下第一路就能成功。但保留兜底路径：
     * <ul>
     *   <li>某些 IDE 版本 / 安装方式下依赖关系建立时机较晚；</li>
     *   <li>用户手动禁用再启用插件后，类加载器可能没重建。</li>
     * </ul>
     * 兜底用 {@code PluginManagerCore.getPluginByClassName} 找到归属插件，
     * 再从它自己的 classloader 里取类。
     *
     * @return Class 对象；不可用时返回 null
     */
    @Nullable
    private static Class<?> loadLocalSetupClass() {
        // 路 1：本插件的类加载器（依赖已声明时可用）
        try {
            return Class.forName(LOCAL_SETUP, false, SpringBootJmxBridge.class.getClassLoader());
        } catch (Throwable ignored) {
            // 继续尝试
        }
        // 路 2：通过平台查归属插件，用它的类加载器
        try {
            Class<?> ep = Class.forName("com.intellij.ide.plugins.PluginManagerCore");
            java.lang.reflect.Method m = ep.getMethod("getPluginByClassName", String.class);
            Object descriptor = m.invoke(null, LOCAL_SETUP);
            if (descriptor != null) {
                java.lang.reflect.Method getCl = descriptor.getClass().getMethod("getPluginClassLoader");
                Object cl = getCl.invoke(descriptor);
                if (cl instanceof ClassLoader loader) {
                    return Class.forName(LOCAL_SETUP, false, loader);
                }
            }
        } catch (Throwable ignored) {
            // 兜底也失败，说明确实没有该插件
        }
        return null;
    }

    /**
     * 创建 {@code LocalSetup} 实例。
     *
     * @param configurationName 配置名，仅用于该插件自己的日志前缀
     * @return 实例；Spring Boot 插件不可用或内部结构变化时返回 null
     */
    @Nullable
    static Object createLocalSetup(@NotNull String configurationName) {
        try {
            // 必须与 isAvailable() 用同一套加载逻辑，
            // 否则「判断可用」和「真正取类」可能来自不同 classloader，
            // 导致 ClassCastException 或 NoSuchMethodException
            Class<?> cls = loadLocalSetupClass();
            if (cls == null) {
                LOG.info("CCM: 未找到 SpringBootJmxSetup.LocalSetup（Spring Boot 插件不可用？），端口号将不显示");
                return null;
            }
            Constructor<?> ctor = cls.getConstructor(boolean.class, String.class);
            // enableAuth=false：本地调试不需要 JMX 认证，与原生默认行为一致
            return ctor.newInstance(false, configurationName);
        } catch (Throwable t) {
            LOG.warn("CCM: 创建 LocalSetup 失败（端口号将不显示）", t);
            return null;
        }
    }

    /**
     * 为本次启动准备 JMX：建 setup 实例 → 注入参数 → 暂存待登记实例。
     *
     * <h2>为什么必须调 setupJmxParameters，而不能只写 -D 参数</h2>
     * 反编译 {@code LocalSetup.setupProcessHandler} 可见第一行是：
     * <pre>
     * if (myJmxPort <= 0) return;     // 什么都不做
     * processHandler.putUserData(JMX_CONFIGURATION, new SpringBootJmxConfiguration(myJmxPort, ...))
     * </pre>
     * 也就是说 <b>{@code myJmxPort} 必须先被设成正数</b>，登记才会发生；
     * 而 {@code myJmxPort} 只在 {@code setupJmxParameters} 里赋值。
     *
     * <p>这解释了前两次失败：手工写死的 5 个 {@code -D} 参数让命令行看起来和原生
     * 一模一样，但实例里的 {@code myJmxPort} 仍是构造时的 -1，
     * 于是 {@code setupProcessHandler} 直接 return，Dashboard 永远读不到端口。
     *
     * @param javaParameters 本次启动参数（会被就地修改）
     */
    public static void prepare(@NotNull JavaParameters javaParameters) {
        try {
            if (!isAvailable()) {
                LOG.info("CCM: Spring Boot 插件不可用，跳过 JMX（端口号将不显示）");
                return;
            }
            Object setup = createLocalSetup("CCM");
            if (setup == null) {
                return;
            }
            Class<?> reqCls = Class.forName(LOCAL_TARGET_REQUEST);
            Object request = reqCls.getDeclaredConstructor().newInstance();
            Method m = setup.getClass().getMethod("setupJmxParameters",
                    JavaParameters.class, Class.forName(TARGET_REQUEST));
            m.invoke(setup, javaParameters, request);
            // 参数注入成功：实例里已带 JMX 端口，暂存给 onProcessStarted 用
            PENDING_SETUP.set(setup);
            // 自检日志：把实际生成的 JMX 参数打出来。
            // 排查"端口号不显示"时，先看这行有没有、里面有没有 jmxremote.port ——
            // 有 port 说明 IDEA 已分配，问题在登记；没有 port 说明 setup 逻辑没走到。
            LOG.info("CCM: 已应用 Spring Boot JMX 参数，当前 VM 参数中的 JMX 项: "
                    + describeJmxOptions(javaParameters));
        } catch (Throwable t) {
            LOG.warn("CCM: 应用 Spring Boot JMX 参数失败（端口号可能不显示）", t);
        }
    }

    /** 提取与 JMX 相关的 VM 参数，供自检日志展示。 */
    private static String describeJmxOptions(@NotNull JavaParameters javaParameters) {
        try {
            StringBuilder sb = new StringBuilder();
            for (String p : javaParameters.getVMParametersList().getParameters()) {
                if (p.contains("jmx") || p.contains("spring.application.admin")
                        || p.contains("liveBeansView")) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(p);
                }
            }
            return sb.length() == 0 ? "（无）" : sb.toString();
        } catch (Throwable t) {
            return "（读取失败）";
        }
    }

    /**
     * 进程启动后：取回暂存实例，把进程登记到 Spring Boot 的 LifecycleManager。
     *
     * <p>Dashboard 靠这个登记按 ProcessHandler 反查
     * {@code SpringBootApplicationInfo}，再取 {@code getServerPort()}。
     *
     * @param result {@code onProcessStarted} 的入参
     */
    public static void completeOnProcessStarted(@Nullable ExecutionResult result) {
        Object setup = PENDING_SETUP.get();
        if (setup == null) {
            return;
        }
        try {
            if (result == null) {
                return;
            }
            ProcessHandler ph = result.getProcessHandler();
            if (ph == null) {
                return;
            }
            Method m = setup.getClass().getMethod("setupProcessHandler", ProcessHandler.class);
            m.invoke(setup, ph);
            LOG.info("CCM: 已把进程登记到 Spring Boot JMX 会话（Services 面板可显示端口号）");
        } catch (Throwable t) {
            LOG.warn("CCM: 登记 Spring Boot JMX 会话失败（端口号可能不显示）", t);
        } finally {
            // 用完即清，避免污染下一次启动
            PENDING_SETUP.remove();
        }
    }
}
