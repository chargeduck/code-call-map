package com.github.chargeduck.codecallmap.run;

import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;

/**
 * CodeCallMap 装订区图标的运行时绘制实现。
 *
 * <h2>为什么用代码画而不是放 png</h2>
 * 装订区图标是 12~16px 的小图，位图在 HiDPI 下容易糊。
 * 用 Java2D 绘制可以根据 IDE 的缩放比（{@code JBUI.scale}）精确渲染，
 * 且不必为每个主题（Darcula / Light）各准备一套资源。
 *
 * <p>视觉设计：
 * <ul>
 *   <li>底：圆角圆形，颜色跟随场景（Run=绿、Debug=橙）</li>
 *   <li>字：白色字母 <b>C</b>（CodeCallMap）</li>
 * </ul>
 * 这样既能一眼区分 Run / Debug（靠底色），又保留插件的字母标识。
 */
final class CodeCallMapIcon implements Icon {

    /** 图标边长（未缩放，JBUI 会按 IDE 缩放比放大）。 */
    private static final int BASE_SIZE = 16;

    /** 底色：Run 用绿色系、Debug 用橙色系，与 IDEA 原生 Run/Debug 的语义接近。 */
    private final Color baseColor;

    /** 绘制尺寸（已按 JBUI 缩放）。 */
    private final int size;

    CodeCallMapIcon(@NotNull Color baseColor) {
        this.baseColor = baseColor;
        this.size = com.intellij.util.ui.JBUI.scale(BASE_SIZE);
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // 圆形底
            g2.setColor(baseColor);
            g2.fill(new Ellipse2D.Float(x, y, size, size));

            // 细描边增强对比（深色主题下更清晰）
            g2.setStroke(new BasicStroke(Math.max(1f, size / 16f)));
            g2.setColor(darker(baseColor));
            g2.draw(new Ellipse2D.Float(x + 0.5f, y + 0.5f, size - 1f, size - 1f));

            // 字母 C：白色、居中、略加粗
            g2.setColor(java.awt.Color.WHITE);
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(8, (int) (size * 0.72f))));
            String letter = "C";
            java.awt.FontMetrics fm = g2.getFontMetrics();
            int tx = x + (size - fm.stringWidth(letter)) / 2;
            // 基线对齐：视觉中心略偏上，抵消字母的 descender 视觉重量
            int ty = y + (size - fm.getHeight()) / 2 + fm.getAscent();
            g2.setComposite(AlphaComposite.SrcOver);
            g2.drawString(letter, tx, ty);
        } finally {
            g2.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }

    /** 生成偏暗的同色描边。 */
    private static Color darker(Color c) {
        return new JBColor(
                new Color(
                        Math.max(0, (int) (c.getRed() * 0.75)),
                        Math.max(0, (int) (c.getGreen() * 0.75)),
                        Math.max(0, (int) (c.getBlue() * 0.75))),
                new Color(
                        Math.max(0, (int) (c.getRed() * 0.75)),
                        Math.max(0, (int) (c.getGreen() * 0.75)),
                        Math.max(0, (int) (c.getBlue() * 0.75))));
    }

    /** Run 图标：绿底白 C。 */
    @NotNull
    static CodeCallMapIcon runIcon() {
        // 与 IDEA 原生 Run 的绿色接近，便于用户建立联想
        return new CodeCallMapIcon(new Color(0x59, 0xA8, 0x3C));
    }

    /** Debug 图标：橙底白 C（原生 Debug 是虫子，这里用暖色区分）。 */
    @NotNull
    static CodeCallMapIcon debugIcon() {
        return new CodeCallMapIcon(new Color(0xC7, 0x54, 0x2B));
    }
}
