package com.github.chargeduck.codecallmap.webview;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import org.jetbrains.annotations.NotNull;

/**
 * CodeCallMap 工具窗口工厂。
 *
 * <p>内容为 {@link WebViewPanel}：由 JCEF 承载 Vue 前端页面。
 * JCEF 在 plugin.xml 中以
 * {@code <depends>com.intellij.modules.jcef</depends>} 声明为必需依赖。
 *
 * <h2>历史教训：不要轻易归因于"平台移除"</h2>
 * 曾有一次打开工具窗口报
 * {@code NoClassDefFoundError: com/intellij/ui/jcef/JBCefJSQuery$Response}，
 * 当时误判为"Idea 2026.x 不再暴露 JCEF"，于是把整套 Vue 前端换成了 Swing。
 * 真实原因只是<b>漏了上述模块依赖声明</b> —— 补上后 JCEF 一切正常。
 *
 * <p>这个错误代价很大（前端页面白写了一版 Swing 替代品）。
 * <b>以后遇到 {@code NoClassDefFoundError: com.intellij.xxx.*}，
 * 第一反应应当是"是不是漏了 <depends>"，而不是"平台删了这个 API"。</b>
 *
 * <h2>为什么创建前要先检查内容是否已存在</h2>
 * {@code ToolWindowFactory.createToolWindowContent} 只在工具窗口<b>首次</b>需要显示时调用一次。
 * 若此前 Content 被移除（旧版 plugin.xml 里 {@code canCloseContents=true} 时
 * 用户点了标签页的 ×），再次打开工具窗口不会再创建内容，
 * 界面就只剩 "Nothing to show"。
 *
 * <p>修复分两层：
 * <ol>
 *   <li>plugin.xml 改为 {@code canCloseContents="false"}，从源头禁止关闭标签页</li>
 *   <li>本类创建前判断 {@code getContentCount() == 0}，并设置 {@code setCloseable(false)}，
 *       双保险：即便内容因任何原因丢失，下次打开也能自动恢复</li>
 * </ol>
 */
public final class MainWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ensureContent(project, toolWindow);
    }

    /**
     * 确保工具窗口里至少有一个内容页；已存在则复用，不重复创建。
     *
     * <p>内容为 {@link WebViewPanel}（JCEF 承载 Vue 前端）。
     * JCEF 通过 plugin.xml 的 {@code <depends>com.intellij.modules.jcef</depends>}
     * 声明为必需依赖，因此这里不再做可用性判断 ——
     * 若该模块缺失，插件在加载阶段就会报依赖缺失，比运行到一半才崩更容易定位。
     */
    static void ensureContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ContentManager cm = toolWindow.getContentManager();
        if (cm.getContentCount() > 0) {
            // 已有内容：之前创建过，直接复用
            return;
        }
        WebViewPanel panel = WebViewPanel.getInstance();
        // init 负责注入 Project（前端跳转源码要用）并触发首次加载；
        // 传 null 作为 url 表示用面板内置的默认页面定位逻辑
        panel.init(project, null);
        Content content = cm.getFactory().createContent(panel, "调用链", false);
        // 标签页不可关闭，只能隐藏整个工具窗口，再次打开内容仍在
        content.setCloseable(false);
        cm.addContent(content);
    }
}
