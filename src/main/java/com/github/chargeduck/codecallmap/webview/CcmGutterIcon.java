package com.github.chargeduck.codecallmap.webview;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/**
 * 方法签名行 gutter 区的「录制现场」图标。
 *
 * <p>点击后弹出该次调用的<b>完整</b>入参与返回值。
 *
 * <h2>为什么用弹窗而不是侧边面板</h2>
 * 需求是"像 debug 那样看数据"，而 debug 的核心体验是
 * <b>就地看、不离开当前代码</b>。侧边面板要占掉半屏宽度，
 * 弹窗看完即走，对"确认某个参数值"这种高频小动作更合适。
 * 完整变量表如果将来要做成常驻面板，可以在此基础上另开，
 * 但不应替代这里的快速查看。
 *
 * @author chargeduck
 */
public final class CcmGutterIcon extends GutterIconRenderer {

    /** 一次调用的完整快照。 */
    private final CcmCallRecord record;

    /**
     * 构造。
     *
     * @param record 调用记录
     */
    public CcmGutterIcon(CcmCallRecord record) {
        this.record = record;
    }

    @Override
    public @NotNull Icon getIcon() {
        // 用平台自带的"数据/表格"图标：语义上表示"这里有结构化数据可看"，
        // 且天然适配明暗主题，不需要自带多套 svg
        return AllIcons.Nodes.DataTables;
    }

    @Override
    public @NotNull String getTooltipText() {
        return "录制现场：点击查看入参与返回值（" + record.durationMs() + "ms"
                + (record.exception() != null ? "，有异常" : "") + "）";
    }

    @Override
    public @NotNull Alignment getAlignment() {
        return Alignment.RIGHT;
    }

    /**
     * 点击行为：弹窗展示完整入参与返回值。
     *
     * @return 动作
     */
    @Override
    public @NotNull AnAction getClickAction() {
        return new AnAction() {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                StringBuilder sb = new StringBuilder();
                sb.append("耗时：").append(record.durationMs()).append("ms\n");
                if (record.thread() != null) {
                    sb.append("线程：").append(record.thread()).append("\n");
                }
                sb.append("\n── 入参 ──\n").append(record.fullArgsText());
                sb.append("\n── 返回值 ──\n").append(record.fullRetText());
                if (record.exception() != null) {
                    sb.append("\n── 异常 ──\n").append(record.exception());
                }
                Messages.showMultilineInputDialog(
                        e.getProject(),
                        sb.toString(),
                        "录制现场 · " + record.spanId(),
                        sb.toString(),
                        AllIcons.Nodes.DataTables,
                        null);
            }
        };
    }

    /**
     * 相等性判断。
     *
     * <p>★ {@link GutterIconRenderer} 要求实现 equals/hashCode：
     * 平台靠它判断"图标是否需要重建"。不实现会出现多余的重绘，
     * 或者在内容变化后仍显示旧样式。
     *
     * @param o 另一个对象
     * @return 是否表示同一条记录
     */
    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CcmGutterIcon)) {
            return false;
        }
        CcmGutterIcon other = (CcmGutterIcon) o;
        return record.spanId() != null && record.spanId().equals(other.record.spanId());
    }

    @Override
    public int hashCode() {
        return record.spanId() == null ? 0 : record.spanId().hashCode();
    }
}
