package com.github.chargeduck.codecallmap.exec;

import com.intellij.execution.Executor;
import com.intellij.execution.ExecutorRegistry;
import com.intellij.execution.dashboard.actions.ExecutorAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Services（运行仪表盘）里「用 CCM 采集模式调试」的工具栏/右键动作。
 *
 * <p>与 {@link CcmRunDashboardAction} 一一对应，区别只是使用
 * {@link CcmDebugExecutor}（走调试通道，可下断点）。
 */
public final class CcmDebugDashboardAction extends ExecutorAction {

    @Override
    protected @NotNull Executor getExecutor() {
        // 优先取注册表里的实例，理由见 CcmRunDashboardAction 的说明
        Executor registered = ExecutorRegistry.getInstance()
                .getExecutorById(CcmDebugExecutor.ID);
        return registered != null ? registered : new CcmDebugExecutor();
    }

    @Override
    protected void update(@NotNull AnActionEvent e, boolean enabled) {
        e.getPresentation().setEnabled(enabled);
        e.getPresentation().setVisible(true);
    }
}
