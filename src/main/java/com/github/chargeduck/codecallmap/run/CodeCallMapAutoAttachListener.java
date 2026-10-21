package com.github.chargeduck.codecallmap.run;

import com.intellij.execution.RunManagerListener;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * 自动接管监听器（项目级 service，构造时订阅 {@link RunManagerListener#TOPIC}）。
 *
 * <p>当设置中 {@code autoAttachEnabled} 开启时，对 Run 配置列表中新增/修改的
 * 普通 Java Application 配置幂等地在 VM options 追加 {@code -javaagent:<agent jar>}
 * （已包含同一 agent jar 则跳过，避免重复注入），使用户可在配置编辑界面直接看到注入项。
 *
 * <p>注入时同步带上 agentArgs（包前缀 / 采样率 / 输出目录 / 最小耗时），
 * 让设置面板的配置对「普通 Application 自动接管」这条路径同样生效——
 * 否则用户改了设置却发现没有效果，会非常困惑。
 *
 * <p>自身类型 {@link CodeCallMapRunConfiguration} 不在此处理（由
 * {@link CodeCallMapProgramPatcher} 强制注入），避免双重追加。
 *
 * <p>注册方式为 {@code <projectService>}（其生命周期与项目一致，{@code connect(project)}
 * 保证项目关闭时自动断开消息总线订阅）。
 */
public final class CodeCallMapAutoAttachListener implements RunManagerListener {

    private final Project project;

    public CodeCallMapAutoAttachListener(@NotNull Project project) {
        this.project = project;
        project.getMessageBus().connect(project).subscribe(RunManagerListener.TOPIC, this);
    }

    @Override
    public void runConfigurationAdded(@NotNull RunnerAndConfigurationSettings settings) {
        maybeAttach(settings);
    }

    @Override
    public void runConfigurationChanged(@NotNull RunnerAndConfigurationSettings settings) {
        maybeAttach(settings);
    }

    @Override
    public void runConfigurationChanged(@NotNull RunnerAndConfigurationSettings settings, @NotNull String reason) {
        maybeAttach(settings);
    }

    private void maybeAttach(RunnerAndConfigurationSettings settings) {
        CodeCallMapSettingsState settingsState = CodeCallMapSettingsState.getInstance();
        if (!settingsState.autoAttachEnabled) {
            return;
        }
        RunConfiguration configuration = settings.getConfiguration();
        if (configuration instanceof CodeCallMapRunConfiguration) {
            return; // 自身类型由 ProgramPatcher 强制注入
        }
        if (!(configuration instanceof ApplicationConfiguration appConfig)) {
            return;
        }
        String agentJar = AgentJarLocator.resolveAgentJarPath(project);
        if (agentJar.isEmpty()) {
            // 找不到 agent jar 时静默跳过，避免在用户每次编辑配置时弹窗骚扰；
            // ProgramPatcher 在真正启动时也无法注入，届时由用户从「加载数据为空」察觉。
            return;
        }
        String agentArg = "-javaagent:" + agentJar
                + CodeCallMapProgramPatcher.buildAgentArgs(settingsState.monitorPackages, settingsState, true);
        String vm = appConfig.getVMParameters() == null ? "" : appConfig.getVMParameters();
        if (CodeCallMapProgramPatcher.containsAgentForJar(vm, agentJar)) {
            return; // 幂等：已注入过则跳过
        }
        String newVm = (vm.isBlank() ? "" : vm + " ") + agentArg;
        appConfig.setVMParameters(newVm);
    }
}
