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
 * 三种 inlay 组合成本类的输出（对应产品需求 A+B）：
 * <ol>
 *   <li><b>参数后</b>（inline）：{@code id = 42} —— 值跟在形参后面，最接近 debug 的观感；</li>
 *   <li><b>行尾</b>（after-line-end）：{@code ▶ 12ms · 3 参数 · 有返回} —— 不占代码宽度；</li>
 *   <li><b>gutter 图标</b>：由 {@link CcmGutterIcon} 提供，点开看完整入参/返回。</li>
 * </ol>
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

    private CcmInlayRenderer(String text, String tooltip, CcmGutterIcon gutter) {
        this(text, tooltip, gutter, null);
    }

    private CcmInlayRenderer(String text, String tooltip, CcmGutterIcon gutter, Runnable onClick) {
        this.text = text == null ? "" : text;
        this.tooltip = tooltip;
        this.gutter = gutter;
        this.onClick = onClick;
    }

    /**
     * 创建参数值提示（A 形态）。
     *
     * @param name  参数名
     * @param value 值短文本
     * @return 渲染器
     */
    public static CcmInlayRenderer forArg(String name, String value) {
        return new CcmInlayRenderer(name + " = " + value, null, null);
    }

    /**
     * 创建行尾汇总提示（B 形态），并挂上 gutter 图标。
     *
     * @param summary 摘要文本
     * @param tooltip 悬停信息（完整入参与返回）
     * @param gutter  gutter 图标；null 表示不挂图标
     * @return 渲染器
     */
    public static CcmInlayRenderer forSummary(String summary, String tooltip, CcmGutterIcon gutter) {
        return new CcmInlayRenderer(summary, tooltip, gutter);
    }

    /**
     * 创建<b>可点击</b>的行尾汇总提示，用于「同一方法多次调用时切换到下一条」。
     *
     * <h2>为什么用鼠标监听而不是 AnAction</h2>
     * {@code EditorCustomElementRenderer} 没有"点击动作"的接口 ——
     * 那是 {@link com.intellij.openapi.editor.markup.GutterIconRenderer} 才有的。
     * inlay 想响应点击只能自己往编辑器的 contentComponent 挂 {@code MouseListener}，
     * 并在 {@link #calcWidthInPixels} 记录的区间内命中判断。
     *
     * @param summary 摘要文本
     * @param tooltip 悬停信息
     * @param gutter  gutter 图标
     * @param onClick 点击时执行；通常用于 index + 1 后重绘
     * @return 渲染器
     */
    public static CcmInlayRenderer forSummaryClickable(String summary, String tooltip,
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
