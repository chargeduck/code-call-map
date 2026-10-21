package com.github.chargeduck.codecallmap;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

/**
 * 占位 Action：验证插件扩展点注册链路可用。
 * 后续将在此入口打开调用链路时序图 / 瀑布图 ToolWindow。
 */
public class ShowCallMapAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Messages.showInfoMessage(
                project,
                "CodeCall Map 插件已加载。\n后续将在此展示调用链路时序图 / 瀑布图。",
                "CodeCall Map");
    }
}
