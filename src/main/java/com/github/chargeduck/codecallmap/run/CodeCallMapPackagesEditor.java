package com.github.chargeduck.codecallmap.run;

import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * CodeCallMap 的「插桩包名」设置编辑器。
 *
 * <p>交互设计对齐 Spring 的 {@code @ComponentScan} 心智：
 * 默认不填（自动探测），需要时手工指定。三个模式：
 * <ul>
 *   <li><b>自动探测</b>（推荐）：从 Main class 所在包与模块源码根推导，零配置；</li>
 *   <li><b>手工指定</b>：完全使用文本框里的包名；</li>
 *   <li><b>自动 + 补充</b>：自动结果与手工填写的合并，手工的优先。</li>
 * </ul>
 * 「探测」按钮会把当前推导结果填进文本框，让用户可见、可改。
 */
public final class CodeCallMapPackagesEditor extends SettingsEditor<CodeCallMapRunConfiguration> {

    private final Project project;

    private final JBRadioButton modeAuto = new JBRadioButton("自动探测（根据 Main class 与模块源码根推导，推荐）");
    private final JBRadioButton modeManual = new JBRadioButton("手工指定");
    private final JBRadioButton modeAutoPlus = new JBRadioButton("自动探测 + 手工补充");

    private final JBTextArea packagesArea = new JBTextArea(3, 40);
    private final JBLabel hintLabel = new JBLabel();

    private final JButton probeButton = new JButton("探测");

    private JComponent rootPanel;

    /** 最近一次 resetEditorFrom 传入的配置，供「探测」按钮读取 Main class。 */
    private CodeCallMapRunConfiguration boundConfig;

    public CodeCallMapPackagesEditor(@NotNull Project project) {
        super();
        this.project = project;
        buildUi();
        installModeListeners();
        wireActions();
    }

    private void buildUi() {
        packagesArea.setLineWrap(true);
        packagesArea.setWrapStyleWord(true);
        packagesArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, packagesArea.getFont().getSize()));

        ButtonGroup group = new ButtonGroup();
        group.add(modeAuto);
        group.add(modeManual);
        group.add(modeAutoPlus);

        hintLabel.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        hintLabel.setFont(hintLabel.getFont().deriveFont(Font.PLAIN, hintLabel.getFont().getSize() - 1f));

        JPanel modePanel = new JPanel(new GridLayout(0, 1, 0, 2));
        modePanel.add(modeAuto);
        modePanel.add(modeManual);
        modePanel.add(modeAutoPlus);

        JBLabel areaLabel = new JBLabel("插桩包名（逗号分隔，按前缀匹配；留空=不按包名限制）：");
        JBScrollPane areaScroll = new JBScrollPane(packagesArea);
        areaScroll.setPreferredSize(new Dimension(460, 64));

        JPanel probeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        probeRow.add(probeButton);
        probeRow.add(hintLabel);

        rootPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("包名来源："), modePanel, 1, false)
                .addVerticalGap(6)
                .addComponent(areaLabel)
                .addComponentFillVertically(areaScroll, 4)
                .addComponent(probeRow)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    private void wireActions() {
        probeButton.addActionListener(e -> {
            String mainClass = boundConfig != null ? boundConfig.getMainClassName() : null;
            PackageProbe.Result result = PackageProbe.probe(project, boundConfig, mainClass);
            if (result.isEmpty()) {
                Messages.showInfoMessage(project,
                        "未能自动探测到包名。\n\n原因：" + result.explanation
                                + "\n\n请在上方文本框中手工填写，例如：com.yourcompany",
                        "CodeCallMap 包名探测");
                return;
            }
            packagesArea.setText(PackageProbe.formatAsSpec(result.packages));
            hintLabel.setText("<html>探测说明：" + escape(result.explanation) + "</html>");
            // 探测出结果后切到「自动 + 补充」，方便用户在此基础上增删
            if (!modeManual.isSelected()) {
                modeAutoPlus.setSelected(true);
            }
        });
    }

    @Override
    protected void resetEditorFrom(@NotNull CodeCallMapRunConfiguration config) {
        this.boundConfig = config;
        CodeCallMapRunConfiguration.PackagesMode mode = config.getPackagesMode();
        modeAuto.setSelected(mode == CodeCallMapRunConfiguration.PackagesMode.AUTO);
        modeManual.setSelected(mode == CodeCallMapRunConfiguration.PackagesMode.MANUAL);
        modeAutoPlus.setSelected(mode == CodeCallMapRunConfiguration.PackagesMode.AUTO_PLUS);

        packagesArea.setText(config.getPackagesSpec());

        PackageProbe.Result probed = config.probeNow();
        if (!probed.isEmpty()) {
            hintLabel.setText("<html>当前可探测到：" + escape(PackageProbe.formatAsSpec(probed.packages))
                    + "<br>" + escape(probed.explanation) + "</html>");
        } else {
            hintLabel.setText("<html>未能从 Main class 推断包名，可在上方手工填写。</html>");
        }
        updateEnabledState();
    }

    @Override
    protected void applyEditorTo(@NotNull CodeCallMapRunConfiguration config) {
        CodeCallMapRunConfiguration.PackagesMode mode;
        if (modeManual.isSelected()) {
            mode = CodeCallMapRunConfiguration.PackagesMode.MANUAL;
        } else if (modeAutoPlus.isSelected()) {
            mode = CodeCallMapRunConfiguration.PackagesMode.AUTO_PLUS;
        } else {
            mode = CodeCallMapRunConfiguration.PackagesMode.AUTO;
        }
        config.setPackagesMode(mode);
        config.setPackagesSpec(packagesArea.getText());
    }

    @NotNull
    @Override
    protected JComponent createEditor() {
        return rootPanel;
    }

    /** 模式切换时刷新输入框可用状态。 */
    private void installModeListeners() {
        java.awt.event.ActionListener l = e -> updateEnabledState();
        modeAuto.addActionListener(l);
        modeManual.addActionListener(l);
        modeAutoPlus.addActionListener(l);
    }

    private void updateEnabledState() {
        boolean editable = !modeAuto.isSelected();
        packagesArea.setEnabled(editable);
        packagesArea.setBackground(editable
                ? javax.swing.UIManager.getColor("TextField.background")
                : javax.swing.UIManager.getColor("TextField.disabledBackground"));
    }

    private static String escape(@Nullable String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 供外部获取当前填写的包名（调试用）。 */
    public List<String> currentPackages() {
        return PackageProbe.parseSpec(packagesArea.getText());
    }
}
