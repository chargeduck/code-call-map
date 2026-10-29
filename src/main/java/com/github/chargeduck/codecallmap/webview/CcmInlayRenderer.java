package com.github.chargeduck.codecallmap.webview;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * 编辑器内联提示的渲染器（把录制数据画进代码行）。
 *
 * <h2>为什么用 {@code InlayModel} 而不是 {@code InlayHintsProvider}</h2>
 * 两者都能在代码里插入灰字，但适用场景完全不同：
 * <ul>
 *   <li>{@code InlayHintsProvider} 是<b>声明式</b>的：注册扩展点后由 daemon 反复回调，
 *       适合"根据代码本身推导出来的信息"（如 Kotlin 的类型提示）。
 *       它要求实现 {@code getKey} / {@code getPreviewText} / {@code createConfigurable}
 *       一整套设置体系，并且会被用户放进 Settings → Inlay Hints 里开关。</li>
 *   <li>{@code InlayModel} 是<b>命令式</b>的：直接调
 *       {@code addInlineElement(offset, renderer)} 就出现，想清就 dispose。</li>
 * </ul>
 * 我们的数据来自<b>一次录制</b>，不是从代码推导的，也不该在 daemon 里反复重算
 * （每次重算都要读文件）。所以命令式更贴切，实现也轻得多。
 *
 * <h2>渲染形态</h2>
 * 本类只负责<b>一条行尾摘要</b>（{@code ▶ 412ms · id=10086, qty=1 · 点击看详情}），
 * 外加一个 gutter 图标（由 {@link CcmGutterIcon} 提供，点开看完整入参/返回）：
 * <ol>
 *   <li><b>行尾</b>（after-line-end）：不占代码宽度，参数值也收在这一处；</li>
 *   <li><b>可点击</b>：点开只读详情弹窗（{@link CcmRecordDialog}）。</li>
 * </ol>
 *
 * <p>早期版本还把每个实参插在形参后面（{@code id = 42}），
 * 参数一多整行代码就被推出屏幕 —— 这是"提示遮住代码"的来源，现已移除。
 *
 * @author chargeduck
 */
public final class CcmInlayRenderer
        implements com.intellij.openapi.editor.EditorCustomElementRenderer {

    /** 文本与背景之间的水平留白。 */
    private static final int PAD_X = 4;

    /** 圆角半径。 */
    private static final int ARC = 4;

    /**
     * 前景色：跟随主题的次要文本色。
     * <p>
     * 不用硬编码的 Color.GRAY —— 在深色主题下会和背景糊成一片。
     * {@code JBColor} 会按当前主题自动选浅/深两个值。
     */
    private static final JBColor FG = new JBColor(new Color(0x5F6B7A), new Color(0x9AA4B2));

    /**
     * 背景色：极淡的底，让灰字从代码里"浮"出来但不抢眼。
     * <p>
     * alpha 只给 0x18：太深会让人误以为是选中状态。
     */
    private static final JBColor BG = new JBColor(new Color(0x00, 0x00, 0x00, 0x14),
            new Color(0xFF, 0xFF, 0xFF, 0x18));

    /** 要显示的文本。 */
    private final String text;
    /** 悬停提示（可为 null）。 */
    private final String tooltip;
    /** gutter 图标渲染器（可为 null，表示这个 inlay 不提供图标）。 */
    private final CcmGutterIcon gutter;
    /** 点击回调（可为 null，表示该 inlay 不可点击）。 */
    private final Runnable onClick;

    private CcmInlayRenderer(String text, String tooltip, CcmGutterIcon gutter, Runnable onClick) {
        this.text = text == null ? "" : text;
        this.tooltip = tooltip;
        this.gutter = gutter;
        this.onClick = onClick;
    }

    /**
     * 创建<b>可点击</b>的行尾摘要提示。
     *
     * <h2>为什么点击要自己实现</h2>
     * {@code EditorCustomElementRenderer} 没有"点击动作"的接口 ——
     * 那是 {@link com.intellij.openapi.editor.markup.GutterIconRenderer} 才有的。
     * inlay 想响应点击只能自己往编辑器的 contentComponent 挂 {@code MouseListener}，
     * 并按 {@link Inlay#getBounds()} 做命中判断（见 {@code CcmEditorOverlay}）。
     *
     * <p>摘要现在<b>始终</b>可点击：早期版本只有"多次调用"时才挂回调，
     * 于是单次调用时点它毫无反应，用户会认为"这个提示是死的"。
     *
     * @param summary 摘要文本
     * @param tooltip 悬停信息（完整入参与返回）
     * @param gutter  gutter 图标
     * @param onClick 点击时执行
     * @return 渲染器
     */
    public static CcmInlayRenderer forSummary(String summary, String tooltip,
                                              CcmGutterIcon gutter, Runnable onClick) {
        return new CcmInlayRenderer(summary, tooltip, gutter, onClick);
    }

    /**
     * 点击回调。
     *
     * @return 回调；不可点击时返回 null
     */
    public Runnable onClick() {
        return onClick;
    }

    /**
     * 是否可点击（行尾摘要带 {@code 1/3} 这类切换标记时才是）。
     *
     * @return true 表示可点击
     */
    public boolean isClickable() {
        return onClick != null;
    }

    /**
     * 计算宽度。
     * <p>
     * 用编辑器自己的字体度量，而不是估算 —— inlay 紧贴代码文本，
     * 宽度差几个像素就会显得错位。
     *
     * @param inlay 所属 inlay
     * @return 像素宽度
     */
    @Override
    public int calcWidthInPixels(@NotNull Inlay inlay) {
        FontMetrics fm = fontMetrics(inlay);
        return fm.stringWidth(text) + PAD_X * 2;
    }

    /**
     * 绘制。
     *
     * @param inlay   所属 inlay
     * @param g       图形上下文
     * @param target  可用区域
     * @param attrs   文本属性（未使用，颜色由本类控制）
     */
    @Override
    public void paint(@NotNull Inlay inlay, @NotNull Graphics2D g,
                      @NotNull Rectangle2D target, @NotNull TextAttributes attrs) {
        FontMetrics fm = fontMetrics(inlay);
        int h = fm.getHeight();
        int y = (int) (target.getY() + (target.getHeight() - h) / 2.0);

        g.setColor(BG);
        g.fillRoundRect((int) target.getX(), y, (int) target.getWidth() - 1, h, ARC, ARC);
        g.setColor(FG);
        g.drawString(text, (int) target.getX() + PAD_X, y + fm.getAscent());
    }

    /**
     * 从编辑器取字体度量。
     *
     * @param inlay 所属 inlay
     * @return 度量对象
     */
    private static FontMetrics fontMetrics(Inlay inlay) {
        Editor editor = inlay.getEditor();
        Font font = editor.getContentComponent().getFont();
        return editor.getContentComponent().getFontMetrics(font);
    }

    /**
     * 提供 gutter 图标。
     *
     * @param inlay 所属 inlay
     * @return 图标渲染器；本 inlay 不提供时返回 null
     */
    @Override
    public CcmGutterIcon calcGutterIconRenderer(@NotNull Inlay inlay) {
        return gutter;
    }

    /** 悬停提示文本。 */
    public String tooltip() {
        return tooltip;
    }

    @Override
    public String toString() {
        return "CcmInlay[" + text + "]";
    }
}
