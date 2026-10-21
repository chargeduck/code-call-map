package com.github.chargeduck.codecallmap.exec;

import com.github.chargeduck.codecallmap.agent.CallMapConfig;
import com.github.chargeduck.codecallmap.run.AgentJarLocator;
import com.github.chargeduck.codecallmap.run.CodeCallMapSettingsState;
import com.github.chargeduck.codecallmap.run.ProjectConfigStore;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 把 CCM 的 {@code -javaagent} 注入到<b>本次启动</b>的 JVM 参数里。
 *
 * <h2>唯一的注入入口</h2>
 * 由 {@link CcmRunProgramRunner} 与 {@link CcmDebugProgramRunner} 在
 * {@code patch(...)} 中调用。两个 runner 都继承自原生 runner，
 * 因此 {@code patch} 只会在"CMM 执行器启动"时被调用 ——
 * <b>不需要任何额外标记就能确定这是 CCM 启动</b>。
 *
 * <h2>⚠️ 只改 JavaParameters，绝不改配置对象</h2>
 * {@link JavaParameters} 是本次进程的参数集合，进程结束即丢弃，
 * 不会写回 {@code .idea/runConfigurations}。
 *
 * <p>早期版本把 agent 写进了 {@code ApplicationConfiguration.setVMParameters()}，
 * 那是<b>持久化</b>的：点过一次 CCM 之后，同一个配置用原生 Run/Debug 启动时
 * 依然带着 agent，导致
 * <ul>
 *   <li>原生运行也被插桩；</li>
 *   <li>与 IDEA 的 debugger-agent 叠加，
 *       {@code LinkageError: duplicate abstract class definition for
 *       org.apache.catalina.loader.WebappClassLoaderBase}。</li>
 * </ul>
 * 这是必须避免的错误。
 */
final class CcmAgentInjector {

    private static final Logger LOG = Logger.getInstance(CcmAgentInjector.class);

    private CcmAgentInjector() {
    }

    /**
     * 注入 agent。
     *
     * @param javaParameters 本次启动参数（会被就地修改）
     * @param profile        运行配置，用于取项目与插桩范围设置
     */
    static void inject(@NotNull JavaParameters javaParameters, @NotNull RunProfile profile) {
        Project project = profile instanceof RunConfiguration rc ? rc.getProject() : null;
        // 无条件打一条 INFO：用于快速判断"patch 是否被调用"。
        // 若日志里连这条都没有，说明 runner 的 patch 根本没触发，
        // 问题在 canRun / 注册，而不是注入逻辑。
        LOG.info("CCM: patch 触发，配置=" + (profile.getClass().getSimpleName())
                + "，项目=" + (project == null ? "null" : project.getName()));
        try {
            String agentJar = AgentJarLocator.resolveAgentJarPath(project);
            if (agentJar == null || agentJar.isEmpty()) {
                LOG.warn("CCM: 未定位到 agent jar，本次以普通模式启动（不采集调用链）");
                return;
            }

            // 幂等：同一 jar 已在参数里就不重复加。
            // 正常流程不会重复，但用户可能在 VM options 里手工配过。
            String existing = javaParameters.getVMParametersList().getParametersString();
            if (existing != null && existing.contains(agentJar)) {
                LOG.debug("CCM: agent 参数已存在，跳过注入");
                return;
            }

            String vmOption = "-javaagent:" + Paths.get(agentJar).toAbsolutePath()
                    + buildAgentArgs(project);
            javaParameters.getVMParametersList().add(vmOption);
            LOG.info("CCM: 已注入 " + vmOption);

            // 【注意】JMX 参数不在这里加。
            //
            // 实测 Debug 启动根本不会调用 runner.patch()，只走
            // CodeCallMapProgramPatcher（JavaProgramPatcher 扩展点）。
            // 把 JMX 参数挂在本方法上等于挂在一条 Debug 时不执行的路径上，
            // 用户日志里因此一个 JMX 参数都没有。正确位置见
            // CodeCallMapProgramPatcher#injectSpringBootJmx。

            // 把本次使用的配置落到 <项目>/.callmap/callmap-config.yml。
            // 目的：以后即使不用 CCM 启动，工具窗口也能按这里记录的目录
            // 找到历史录制并回看；配置也随代码走，换机器/换 IDE 不用重配。
            Path outputDir = resolveOutputDir(project);
            if (outputDir != null) {
                CodeCallMapSettingsState.getInstance().outputDir = outputDir.toString();
            }
            ProjectConfigStore.save(project);
        } catch (Throwable t) {
            // 注入失败不应阻止程序启动，降级为普通运行
            LOG.warn("CCM: 注入 javaagent 失败，本次以普通模式启动", t);
        }
    }

    /**
     * 组装 agent 参数。
     *
     * <p>格式：{@code -javaagent:<jar>=<k1>=<v1>,<k2>=<v2>}。
     * 目前传入输出目录、采样率、最小耗时 —— 与设置面板保持同步，
     * 让用户在 Settings 里的调整真正生效。
     */
    @NotNull
    private static String buildAgentArgs(Project project) {
        CodeCallMapSettingsState settings = CodeCallMapSettingsState.getInstance();
        StringBuilder sb = new StringBuilder("=");

        Path outDir = resolveOutputDir(project);
        if (outDir != null) {
            sb.append(CallMapConfig.PROP_OUTPUT_DIR).append('=').append(outDir);
        }

        // 采样率与最小耗时紧跟其后，用逗号分隔
        sb.append(',').append(CallMapConfig.PROP_SAMPLE_RATE)
                .append('=').append(settings.sampleRate);
        sb.append(',').append(CallMapConfig.PROP_MIN_DURATION_MS)
                .append('=').append(settings.minDurationMs);

        String packages = settings.monitorPackages;
        if (packages != null && !packages.trim().isEmpty()) {
            sb.append(',').append(CallMapConfig.PROP_INCLUDE_PACKAGES)
                    .append('=').append(packages.trim());
        }
        return sb.toString();
    }

    /**
     * 数据目录：优先系统属性，其次项目根下的 .callmap。 */
    private static Path resolveOutputDir(Project project) {
        String configured = System.getProperty(CallMapConfig.PROP_OUTPUT_DIR);
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured.trim());
        }
        if (project != null && project.getBasePath() != null) {
            return Paths.get(project.getBasePath(), CallMapConfig.DEFAULT_DIR_NAME);
        }
        return null;
    }
}
