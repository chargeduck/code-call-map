package com.github.chargeduck.codecallmap.webview;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 「录制现场」详情弹窗：<b>只读</b>展示一次调用的完整入参、返回值与异常。
 *
 * <h2>为什么自己写一个 DialogWrapper</h2>
 * 之前用的是 {@code Messages.showMultilineInputDialog} —— 它是"输入框"，
 * 内容可以改、有光标、能选中删除，看起来像是让用户编辑录制数据；
 * 实际这些数据是只读快照，可编辑会误导用户以为改动会生效。
 *
 * <h2>为什么带「上一条 / 下一条」</h2>
 * 同一个方法在一次 trace 里常被调用十几次（例如分页查询）。
 * 用户点开行尾提示想看的往往是"第 n 次传了什么"，
 * 关掉再回代码里点一下才能切换，来回太碎；在弹窗里直接翻页更贴近 debug 的体验。
 *
 * @author chargeduck
 */
public final class CcmRecordDialog extends DialogWrapper {

    /** 弹窗标题里展示的「类#方法」。 */
    private final String title;
    /** 该方法的全部调用记录（按时间升序）。 */
    private final List<CcmCallRecord> records;
    /** 当前展示第几条（0-based）。 */
    private int index;
    /** 翻页回调：让调用方（编辑器覆盖层）同步把 inlay 也换成对应那一条。 */
    private final IntConsumer onIndexChange;

    /** 正文区域（只读）。 */
    private JBTextArea area;
    /** 右上角的「第 n/N 次」标签。 */
    private JBLabel counterLabel;

    /**
     * 构造并显示。
     *
     * @param project        项目
     * @param title          「类#方法」
     * @param records        该方法的所有调用记录
     * @param index          初始展示第几条
     * @param onIndexChange  翻页回调，可为 null（如从 gutter 图标打开时）
     */
    public CcmRecordDialog(@Nullable Project project, String title,
                           List<CcmCallRecord> records, int index,
                           @Nullable IntConsumer onIndexChange) {
        super(project, false);
        this.title = title;
        this.records = records;
        this.index = clamp(index);
        this.onIndexChange = onIndexChange;
        setTitle("录制现场 · " + title);
        setModal(true);
        init();
    }

    private int clamp(int i) {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        return Math.max(0, Math.min(i, records.size() - 1));
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setBorder(JBUI.Borders.empty(4));

        counterLabel = new JBLabel();
        counterLabel.setBorder(JBUI.Borders.emptyBottom(4));
        root.add(counterLabel, BorderLayout.NORTH);

        area = new JBTextArea();
        area.setEditable(false);
        area.setLineWrap(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12)));
        area.setBorder(JBUI.Borders.empty(6));
        JBScrollPane scroll = new JBScrollPane(area,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setPreferredSize(JBUI.size(660, 420));
        root.add(scroll, BorderLayout.CENTER);

        refresh();
        return root;
    }

    /**
     * 底部按钮区：翻页 + 关闭。
     *
     * <p>不调 {@code super.createSouthPanel()} —— 那会带出「确定 / 取消」，
     * 而这里只有一个动作：关闭。
     */
    @Override
    protected @Nullable JComponent createSouthPanel() {
        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        if (records != null && records.size() > 1) {
            JButton prev = new JButton("← 上一条");
            prev.addActionListener(e -> move(-1));
            JButton next = new JButton("下一条 →");
            next.addActionListener(e -> move(1));
            left.add(prev);
            left.add(next);
        }
        south.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton close = new JButton("关闭");
        close.addActionListener(e -> close(OK_EXIT_CODE));
        right.add(close);
        south.add(right, BorderLayout.EAST);
        return south;
    }

    /** 翻页（循环）。 */
    private void move(int delta) {
        if (records == null || records.size() <= 1) {
            return;
        }
        index = (index + delta + records.size()) % records.size();
        refresh();
        if (onIndexChange != null) {
            onIndexChange.accept(index);
        }
    }

    /** 重新渲染正文与计数标签。 */
    private void refresh() {
        if (records == null || records.isEmpty()) {
            if (area != null) {
                area.setText("（没有可展示的调用记录）");
            }
            return;
        }
        CcmCallRecord record = records.get(clamp(index));
        if (counterLabel != null) {
            counterLabel.setText("第 " + (index + 1) + " / " + records.size() + " 次调用  ·  " + title);
        }
        if (area != null) {
            area.setText(render(record));
            area.setCaretPosition(0);
        }
    }

    /**
     * 生成正文文本。
     *
     * <p>顺序按排查习惯排：先耗时/线程（判断是不是这次调用慢），
     * 再入参（跟代码里的变量对照），最后返回值和异常。
     */
    private static String render(CcmCallRecord record) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("耗时：").append(record.durationMs()).append(" ms");
        if (record.thread() != null && !record.thread().isBlank()) {
            sb.append("        线程：").append(record.thread());
        }
        sb.append("\n\n── 入参 ──\n").append(record.fullArgsText());
        sb.append("\n\n── 返回值 ──\n").append(record.fullRetText());
        if (record.exception() != null && !record.exception().isBlank()) {
            sb.append("\n\n── 异常 ──\n").append(record.exception());
        }
        return sb.toString();
    }

    /**
     * 打开后把焦点交给正文区域，这样可以直接用键盘滚动、Ctrl+A 复制。
     *
     * <h2>为什么在 init() 里做，而不是重写 getPreferredFocusedComponent()</h2>
     * 那个方法在 IntelliJ 平台的可见性变过 —— 2025.2 是 {@code protected}，
     * 2025.3 改成 {@code public}。本仓库同一份代码要同时编出 233 与 253 两个
     * 分支的包，任何一种写法都会在另一个平台上编不过（"正在尝试分配更低的访问权限"）。
     * {@link #init()} 的签名多年未变，用它设置初始焦点最稳妥。
     */
    @Override
    protected void init() {
        super.init();
        SwingUtilities.invokeLater(() -> {
            if (area != null) {
                area.requestFocusInWindow();
            }
        });
    }
}
