package com.github.chargeduck.codecallmap.exec;

import com.intellij.execution.Executor;
import com.intellij.execution.ExecutorRegistry;
import com.intellij.execution.dashboard.actions.ExecutorAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Services（运行仪表盘）里「用 CCM 采集模式运行」的工具栏/右键动作。
 *
 * <h2>为什么需要单独的 Action</h2>
 * 自定义 {@link Executor} 注册后，会自动出现在：
 * <ul>
 *   <li>编辑器装订区的运行菜单</li>
 *   <li>顶部 Run Configuration 下拉</li>
 * </ul>
 * 但 <b>Services 面板（运行仪表盘）不会自动列出执行器</b> ——
 * 那里列的是"运行中的服务节点"，工具栏上的启动按钮需要各自注册 Action。
 *
 * <p>所以这里继承平台的 {@link ExecutorAction}：它已经封装好
 * 「读取仪表盘选中的节点 → 找到对应运行配置 → 用指定执行器启动」的全套逻辑，
 * 子类只需说明用哪个执行器，以及在什么情况下可用。
 *
 * <p>与同类插件的做法一致：也是往
 * {@code RunDashboardContentToolbar} 组里加一个自己的 action。
 */
public final class CcmRunDashboardAction extends ExecutorAction {

    @Override
    protected @NotNull Executor getExecutor() {
        // 从注册表取扩展点里那个已注册实例，而不是每次 new：
        // 平台内部有按实例比较的场景（如判断"是否正在启动"），
        // 用注册表实例能保证与其它入口拿到的是同一个对象。
        Executor registered = ExecutorRegistry.getInstance()
                .getExecutorById(CcmRunExecutor.ID);
        return registered != null ? registered : new CcmRunExecutor();
    }

    /**
     * 更新 action 的可用状态。
     *
     * <p>父类已根据"选中节点是否可运行"算出 {@code enabled}，
     * 这里只需把结果落到 Presentation 上，并保证文本可见。
     */
    @Override
    protected void update(@NotNull AnActionEvent e, boolean enabled) {
        e.getPresentation().setEnabled(enabled);
        e.getPresentation().setVisible(true);
    }
}
