package com.github.chargeduck.codecallmap.run;

import com.github.chargeduck.codecallmap.exec.CcmDebugExecutor;
import com.github.chargeduck.codecallmap.exec.CcmRunExecutor;
import com.github.chargeduck.codecallmap.exec.SpringBootJmxBridge;
import com.github.chargeduck.codecallmap.exec.SpringBootJmxParams;
import com.intellij.execution.Executor;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.runners.JavaProgramPatcher;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.File;

/**
 * VM 参数注入器（com.intellij.java.programPatcher 扩展点实现）。
 *
 * <p>IDEA 2026.x 中 {@code ApplicationConfiguration} 不再暴露
 * {@code getJavaParameters(JavaParameters, RunnerSettings, ExecutionEnvironment)}，
 * 官方替代方案为 {@link JavaProgramPatcher#patchJavaParameters}。本类在每次启动前统一处理：
 * <ul>
 *   <li>目标为 {@link CodeCallMapRunConfiguration}（Run/Debug 下拉选择 "CodeCallMap"）→ 强制注入；</li>
 *   <li>目标为普通 {@link ApplicationConfiguration} 且设置中 {@code autoAttachEnabled} 开启 → 自动注入（运行时兜底）；</li>
 *   <li>其余类型（Gradle/Maven/Tomcat 等）不处理。</li>
 * </ul>
 *
 * <p><b>Run 与 Debug 都走这里</b>：该扩展点由 {@code DefaultJavaProgramRunner} 与
 * {@code GenericDebuggerRunner} 在启动前共同调用，因此同一次配置既可直接 Run，
 * 也可 Debug 启动，采集行为一致；Debug 模式下还能在探针记录到的耗时方法上直接下断点。
 *
 * <p>注入幂等：VM options 已存在指向同一 agent jar 的 {@code -javaagent:} 参数时跳过。
 * 注入时会带上 agentArgs（包前缀、采样率、输出目录），使设置面板的配置真正生效。
 */
public final class CodeCallMapProgramPatcher extends JavaProgramPatcher {

    private static final Logger LOG = Logger.getInstance(CodeCallMapProgramPatcher.class);

    /** -javaagent 参数前缀。 */
    static final String JAVAAGENT_PREFIX = "-javaagent:";

    @Override
    public void patchJavaParameters(@NotNull Executor executor,
                                    @NotNull RunProfile configuration,
                                    @NotNull JavaParameters javaParameters) {
        CodeCallMapSettingsState settings = CodeCallMapSettingsState.getInstance();
        boolean isCallMap = configuration instanceof CodeCallMapRunConfiguration;
        boolean isPlainApplication = configuration instanceof ApplicationConfiguration && !isCallMap;
        boolean autoAttach = isPlainApplication && settings.autoAttachEnabled;

        // CCM 执行器（Run with CCM / Debug with CCM）→ 无条件注入。
        //
        // 【重要】为什么这里能拿到 CCM 的 executor：
        // CCM 的两个 runner 继承自原生 runner（DefaultJavaProgramRunner /
        // GenericDebuggerRunner），执行时<b>不替换执行器</b>，
        // 因此 environment.getExecutor() 就是 CCM.Debug / CCM.Run。
        //
        // 而 JavaProgramPatcher.runCustomPatchers(javaParameters,
        // environment.getExecutor(), environment.getRunProfile())
        // 正是用这个 executor 回调我们 —— 所以在这里判断执行器 ID 即可，
        // 不需要在 runner 的 patch() 里注入
        // （实测 Debug 路径根本不会调用 runner.patch()，只会走 patcher）。
        boolean isCcmExecutor = CcmRunExecutor.ID.equals(executor.getId())
                || CcmDebugExecutor.ID.equals(executor.getId());

        // 诊断日志：确认 patcher 被调用、且能识别出 CCM 执行器。
        // 排查"没有插桩"类问题时，先看这行有没有出现、executorId 是什么。
        LOG.info("CCM: patcher 触发，executor=" + executor.getId()
                + "，配置=" + configuration.getClass().getSimpleName()
                + "，isCcm=" + isCcmExecutor + "，autoAttach=" + autoAttach);

        if (!isCallMap && !autoAttach && !isCcmExecutor) {
            return;
        }

        Project project = configuration instanceof RunConfiguration rc ? rc.getProject() : null;
        String agentJar = AgentJarLocator.resolveAgentJarPath(project);
        if (agentJar.isEmpty()) {
            return;
        }

        // 包名解析：CodeCallMap 专用配置走配置自身的「自动探测 / 手工 / 自动+补充」三模式；
        // 自动接管的普通 Application 没有这块 UI，回退到全局设置里的 monitorPackages。
        String packages = resolvePackages(configuration, settings, isCallMap);

        String agentArg = JAVAAGENT_PREFIX + agentJar
                + buildAgentArgs(packages, settings, isCallMap);

        ParametersList vm = javaParameters.getVMParametersList();
        if (containsAgentForJar(String.join(" ", vm.getParameters()), agentJar)) {
            return;
        }
        vm.add(agentArg);
        injectSpringBootJmx(javaParameters);
    }

    /**
     * 用 Spring Boot 插件自己的 JMX setup 处理参数。
     *
     * <h2>为什么不再手工拼 {@code -D} 参数</h2>
     * 前一版手工写了 5 个 {@code -D}（jmxremote / spring.jmx.enabled / ...），
     * 命令行与原生启动逐字一致，<b>但端口号依然不显示</b>。
     * 反编译后才看清：Spring Boot 插件的 {@code LocalSetup.setupProcessHandler}
     * 第一行就是 {@code if (myJmxPort <= 0) return;}，
     * 而 {@code myJmxPort} 只在 {@code setupJmxParameters} 里被赋成正数。
     * 手工写参数绕过了这一步，端口字段永远是 -1，登记因此不发生。
     *
     * <p>所以改成直接调 {@code SpringBootJmxBridge.prepare()}：
     * 参数由插件自己生成（含它分配的 JMX 端口），
     * 实例也被暂存下来，供 runner 在 {@code onProcessStarted} 阶段完成登记。
     *
     * <p>Spring Boot 插件不存在时静默跳过（不硬依赖该插件）。
     */
    private static void injectSpringBootJmx(JavaParameters javaParameters) {
        try {
            // 只对 Spring Boot 应用处理：判据是 classpath 里有没有 spring-boot-*.jar。
            // 不能用配置类型名 —— 用户跑的是 CodeCallMapRunConfiguration，类名不含 SpringBoot。
            if (!SpringBootJmxParams.classPathHasSpringBoot(
                    javaParameters.getClassPath().getPathList())) {
                return;
            }
            SpringBootJmxBridge.prepare(javaParameters);
        } catch (Throwable t) {
            // 补参数失败不能阻止启动
            LOG.warn("CCM: 应用 Spring Boot JMX 参数失败（端口号可能不显示）", t);
        }
    }

    /**
     * 解析最终包名规格。
     *
     * <p>这里刻意不做 ReadAction 包装：{@code patchJavaParameters} 由平台在
     * 启动流程中调用，此时已是安全的读取上下文（平台自身也在此处做 Psi 查询）。
     * {@link PackageProbe} 内部对异常做了兜底，探测失败时退化为空串（= 不限制包名）。
     */
    private static String resolvePackages(RunProfile configuration,
                                          CodeCallMapSettingsState settings,
                                          boolean isCallMap) {
        if (isCallMap && configuration instanceof CodeCallMapRunConfiguration cmConfig) {
            String resolved = cmConfig.resolvePackagesSpec();
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
            // 探测失败且用户没填 → 留空，让 agent 按内置黑名单走
            return "";
        }
        return settings.monitorPackages == null ? "" : settings.monitorPackages.trim();
    }

    /**
     * 拼装 agentArgs：包名、采样率、输出目录、最小耗时。
     *
     * <p>agentArgs 以 {@code =} 紧跟在 jar 路径之后，多个参数用逗号分隔
     * （与 {@code AgentBootstrap.applyAgentArgs} 的解析格式一致）。
     */
    public static String buildAgentArgs(String packages, CodeCallMapSettingsState settings, boolean forceInstrument) {
        StringBuilder sb = new StringBuilder();
        append(sb, "instrument.enabled", "true");

        if (packages != null && !packages.isBlank()) {
            append(sb, "include.packages", packages.trim());
        }

        if (settings.sampleRate > 0 && settings.sampleRate != 100) {
            append(sb, "sample.rate", String.valueOf(settings.sampleRate));
        }

        // 输出目录：优先设置项；未配置时由 agent 落到 <user.dir>/.callmap（user.dir 即目标应用工作目录）
        if (settings.outputDir != null && !settings.outputDir.isBlank()) {
            append(sb, "output.dir", settings.outputDir.trim());
        }

        if (forceInstrument && settings.minDurationMs > 0) {
            append(sb, "min.duration.ms", String.valueOf(settings.minDurationMs));
        }

        return sb.isEmpty() ? "" : "=" + sb;
    }

    private static void append(StringBuilder sb, String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(',');
        }
        sb.append(key).append('=').append(value);
    }

    /**
     * 判断 VM options 字符串中是否已存在指向同一 agent jar 的 -javaagent 参数。
     *
     * <p>供 {@link CodeCallMapAutoAttachListener} 做幂等判断；比对时忽略 agentArgs 差异
     * （因为 agentArgs 会随设置变化，不应因此重复追加同一 jar）。
     */
    public static boolean containsAgentForJar(String vmOptions, String agentJar) {
        if (vmOptions == null || vmOptions.isBlank() || agentJar == null || agentJar.isEmpty()) {
            return false;
        }
        String normalized = new File(agentJar).getAbsolutePath();
        for (String token : vmOptions.split("\\s+")) {
            if (!token.startsWith(JAVAAGENT_PREFIX)) {
                continue;
            }
            String value = token.substring(JAVAAGENT_PREFIX.length());
            int eq = value.indexOf('=');
            String pathOnly = eq >= 0 ? value.substring(0, eq) : value;
            if (pathOnly.equals(agentJar)) {
                return true;
            }
            try {
                if (new File(pathOnly).getAbsolutePath().equals(normalized)) {
                    return true;
                }
            } catch (Exception ignored) {
                // 路径解析失败时回退到精确字符串比较（已在上面执行）
            }
        }
        return false;
    }
}
