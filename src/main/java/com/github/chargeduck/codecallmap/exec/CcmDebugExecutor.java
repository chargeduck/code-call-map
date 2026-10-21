package com.github.chargeduck.codecallmap.exec;

import com.intellij.execution.Executor;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.ToolWindowId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/**
 * 「Debug with CCM」执行器。
 *
 * <p>与 {@link CcmRunExecutor} 的区别只是执行器身份不同（走 Debug runner），
 * agent 注入逻辑完全一致，因此也能正常下断点调试。
 */
public final class CcmDebugExecutor extends Executor {

    /** 执行器 id，供 {@link CcmDebugProgramRunner} 认领。 */
    public static final String ID = "CCM.Debug";

    /**
     * 运行结果挂到哪个工具窗口。
     *
     * <h2>为什么不能再返回空串</h2>
     * {@code getToolWindowId()} 决定「这次运行的结果属于哪个工具窗口」。
     * 原先返回 {@code ""}，结果是运行会话没有被正确归属到 Run/Debug 工具窗口 ——
     * 连带丢掉了一批<b>依赖工具窗口上下文</b>的平台能力，
     * 其中就包括 Spring Boot 的端口号徽标（它挂在会话的进程描述上，
     * 按工具窗口维度组织）。
     *
     * <p>用户反馈"正常 Debug 有端口号，用 CCM Debug 没有"，根因就在这里。
     * 与原生 {@code DebugExecutor} 保持一致即可恢复。
     */
    @Override
    public @NotNull String getToolWindowId() {
        return ToolWindowId.DEBUG;
    }

    @Override
    public @NotNull Icon getToolWindowIcon() {
        return AllIcons.Toolwindows.ToolWindowDebugger;
    }

    @Override
    public @NotNull Icon getIcon() {
        return AllIcons.Actions.StartDebugger;
    }

    @Override
    public @NotNull Icon getDisabledIcon() {
        return AllIcons.Actions.StartDebugger;
    }

    @Override
    public @NotNull String getDescription() {
        return "以 CCM 采集模式调试：自动注入 javaagent，可正常下断点";
    }

    @Override
    public @NotNull String getActionName() {
        return "Debug with CCM";
    }

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @NotNull String getStartActionText() {
        return "Debug with CCM";
    }

    @Override
    public @NotNull String getStartActionText(@Nullable String configurationName) {
        return "Debug with CCM" + (StringUtil.isEmpty(configurationName)
                ? "" : " '" + configurationName + "'");
    }

    @Override
    public @NotNull String getContextActionId() {
        return "DebugWithCcmContextAction";
    }

    @Override
    public @NotNull String getHelpId() {
        return "reference.dialogs.rundebug.Application";
    }
}
