package com.github.chargeduck.codecallmap.exec;

import com.intellij.debugger.impl.GenericDebuggerRunner;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunnerSettings;
import org.jetbrains.annotations.NotNull;

/**
 * 「Debug with CCM」的执行 Runner。
 *
 * <p>与 {@link CcmRunProgramRunner} 同样的思路（继承原生 runner 而非委托），
 * 基类换成调试专用的 {@link GenericDebuggerRunner}，因此断点、单步、
 * 变量查看等全部沿用平台实现。
 *
 * <h2>与 debugger-agent 的共存</h2>
 * IDEA 调试时会自带 {@code -javaagent:debugger-agent.jar}。
 * CCM 的 agent 追加在其后，两者各管各的：前者负责断点与变量，
 * 后者只做字节码插桩记录调用链，互不干扰。
 * （早期的错误做法会污染配置导致 agent 重复叠加，才引发 LinkageError。）
 */
public final class CcmDebugProgramRunner extends GenericDebuggerRunner {

    @Override
    public @NotNull String getRunnerId() {
        return "CCM.Runner." + CcmDebugExecutor.ID;
    }

    @Override
    public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
        if (!CcmDebugExecutor.ID.equals(executorId)) {
            return false;
        }
        return profile instanceof com.intellij.execution.configurations.RunProfileWithCompileBeforeLaunchOption;
    }

    /**
     * 追加 {@code -javaagent}。
     *
     * <p>先调 {@code super.patch} 让调试器完成自己的参数准备（含 debugger-agent），
     * 再追加 CCM 的 agent，保证两者都在最终命令行里。
     *
     * <p>注意父类声明了 {@code throws ExecutionException}（调试器准备失败时抛出），
     * 因此签名必须一致地向上抛，不能吞掉 —— 否则会出现"调试器没准备好但进程已启动"。
     */
    @Override
    public void patch(@NotNull JavaParameters javaParameters,
                      @NotNull RunnerSettings settings,
                      @NotNull RunProfile runProfile,
                      boolean beforeExecution) throws ExecutionException {
        super.patch(javaParameters, settings, runProfile, beforeExecution);
        CcmAgentInjector.inject(javaParameters, runProfile);
    }

    /**
     * 进程启动后登记 Spring Boot JMX 会话，让 Services 面板能显示端口号。
     *
     * <h2>Debug 路径为什么必须要这一段</h2>
     * 端口号靠 Spring Boot 插件的 {@code SpringBootJmxSetup.setupProcessHandler()}
     * 把进程登记到 LifecycleManager，Dashboard 才能按 ProcessHandler 反查
     * {@code getServerPort()}。原生 Spring Boot 配置由它自己的 runner 完成登记；
     * CCM 用自己的 runner 就少了这一步，于是参数齐全但端口号不出现。
     *
     * <p>实测 Debug 不调用 {@code patch()}，但 {@code onProcessStarted} 一定会走，
     * 因此这里是可靠时机。失败仅影响端口号，不影响调试与采集。
     */
    @Override
    public void onProcessStarted(@NotNull RunnerSettings settings,
                                 @NotNull ExecutionResult result) {
        super.onProcessStarted(settings, result);
        try {
            // 与 patcher 阶段的 prepare() 成对：取回暂存的 setup，完成进程登记。
            // 没有这一步，Dashboard 的 getServerPort() 就没有宿主，端口号不显示。
            SpringBootJmxBridge.completeOnProcessStarted(result);
        } catch (Throwable ignored) {
            // 端口号是附加能力，任何异常都不应影响调试
        }
    }
}
