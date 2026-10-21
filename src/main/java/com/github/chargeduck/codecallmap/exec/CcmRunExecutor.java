package com.github.chargeduck.codecallmap.exec;

import com.intellij.execution.Executor;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.ToolWindowId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/**
 * 「Run with CCM」执行器。
 *
 * <p>独立成顶层类而非内部类：plugin.xml 里用 {@code $} 写内部类名虽能被反射实例化，
 * 但受混淆/重命名影响且不易排查，顶层类最稳妥。
 *
 * <p>图标用原生 Run 的绿三角，让用户在工具栏/下拉里一眼认出这是"运行"语义。
 */
public final class CcmRunExecutor extends Executor {

    /** 执行器 id，供 {@link CcmRunProgramRunner} 认领。 */
    public static final String ID = "CCM.Run";

    /**
     * 运行结果挂到哪个工具窗口。
     *
     * <p>与 {@link CcmDebugExecutor#getToolWindowId()} 同理：必须返回真实的
     * 工具窗口 id，不能是空串。空串会让会话脱离 Run 工具窗口的上下文，
     * 连带丢掉依赖该上下文的平台增强能力（典型表现就是
     * Spring Boot 项目名后面不显示端口号）。
     */
    @Override
    public @NotNull String getToolWindowId() {
        return ToolWindowId.RUN;
    }

    @Override
    public @NotNull Icon getToolWindowIcon() {
        return AllIcons.Toolwindows.ToolWindowRun;
    }

    @Override
    public @NotNull Icon getIcon() {
        return AllIcons.Actions.Execute;
    }

    @Override
    public @NotNull Icon getDisabledIcon() {
        return AllIcons.Actions.Execute;
    }

    @Override
    public @NotNull String getDescription() {
        return "以 CCM 采集模式运行：自动注入 javaagent 录制方法级调用链";
    }

    @Override
    public @NotNull String getActionName() {
        return "Run with CCM";
    }

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @NotNull String getStartActionText() {
        return "Run with CCM";
    }

    @Override
    public @NotNull String getStartActionText(@Nullable String configurationName) {
        return "Run with CCM" + (StringUtil.isEmpty(configurationName)
                ? "" : " '" + configurationName + "'");
    }

    @Override
    public @NotNull String getContextActionId() {
        return "RunWithCcmContextAction";
    }

    @Override
    public @NotNull String getHelpId() {
        return "reference.dialogs.rundebug.Application";
    }
}
