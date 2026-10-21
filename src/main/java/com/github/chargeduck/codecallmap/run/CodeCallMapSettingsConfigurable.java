package com.github.chargeduck.codecallmap.run;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * CodeCallMap 设置面板（Settings → CodeCallMap）：
 * 自动接管开关、agent jar 路径（带文件选择器）、监控包前缀、采样率。
 */
public final class CodeCallMapSettingsConfigurable implements SearchableConfigurable {

    private JCheckBox autoAttachCheckBox;
    private TextFieldWithBrowseButton agentJarField;
    private JTextField monitorPackagesField;
    private JTextField sampleRateField;
    private JTextField minDurationField;
    private JTextField slowThresholdField;
    private TextFieldWithBrowseButton outputDirField;

    @NotNull
    @Override
    public String getId() {
        return "codecallmap.settings";
    }

    @Nls
    @Override
    public String getDisplayName() {
        return "CodeCallMap";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        autoAttachCheckBox = new JCheckBox(
                "自动接管：开启后，普通 Java Application 配置启动时也自动注入 agent（已注入过的配置不再重复追加）");
        agentJarField = new TextFieldWithBrowseButton();
        agentJarField.addActionListener(e -> {
            FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, false) {
                @Override
                public boolean isFileSelectable(@Nullable VirtualFile file) {
                    return file != null && !file.isDirectory()
                            && "jar".equalsIgnoreCase(file.getExtension());
                }
            };
            descriptor.setTitle("选择 CodeCallMap agent jar");
            VirtualFile chosen = FileChooser.chooseFile(descriptor, null, null);
            if (chosen != null) {
                agentJarField.setText(chosen.getPath());
            }
        });
        monitorPackagesField = new JTextField();
        sampleRateField = new JTextField();
        minDurationField = new JTextField();
        slowThresholdField = new JTextField();
        outputDirField = new TextFieldWithBrowseButton();
        outputDirField.addActionListener(e -> {
            FileChooserDescriptor descriptor = new FileChooserDescriptor(false, true, false, false, false, false);
            descriptor.setTitle("选择调用链 JSONL 输出目录");
            VirtualFile chosen = FileChooser.chooseFile(descriptor, null, null);
            if (chosen != null) {
                outputDirField.setText(chosen.getPath());
            }
        });

        JPanel root = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        root.add(autoAttachCheckBox, c);

        c.gridwidth = 1;
        c.gridy = 1;
        c.gridx = 0;
        root.add(new JLabel("Agent jar 路径（留空自动定位 build/libs）："), c);
        c.gridx = 1;
        c.weightx = 1.0;
        c.fill = GridBagConstraints.HORIZONTAL;
        root.add(agentJarField, c);

        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.gridy = 2;
        c.gridx = 0;
        root.add(new JLabel("监控包前缀（仅用于「自动接管」路径，逗号分隔）："), c);
        c.gridx = 1;
        c.weightx = 1.0;
        c.fill = GridBagConstraints.HORIZONTAL;
        root.add(monitorPackagesField, c);

        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.gridy = 3;
        c.gridx = 0;
        c.gridwidth = 2;
        root.add(new JLabel("<html><span style='color:gray;font-size:10px'>"
                + "说明：在 Run 配置下拉里选择「CodeCallMap」时，包名由该配置的「插桩范围」页决定"
                + "（可自动探测），此处设置不生效。"
                + "</span></html>"), c);
        c.gridwidth = 1;

        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.gridy = 4;
        c.gridx = 0;
        root.add(new JLabel("采样率（0-100，默认 100）："), c);
        c.gridx = 1;
        c.weightx = 1.0;
        c.fill = GridBagConstraints.HORIZONTAL;
        root.add(sampleRateField, c);

        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.gridy = 5;
        c.gridx = 0;
        root.add(new JLabel("最小记录耗时 ms（0=全部记录，调大可降噪）："), c);
        c.gridx = 1;
        c.weightx = 1.0;
        c.fill = GridBagConstraints.HORIZONTAL;
        root.add(minDurationField, c);

        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.gridy = 6;
        c.gridx = 0;
        root.add(new JLabel("慢调用阈值 ms（超过则标红并计入慢调用统计）："), c);
        c.gridx = 1;
        c.weightx = 1.0;
        c.fill = GridBagConstraints.HORIZONTAL;
        root.add(slowThresholdField, c);

        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.gridy = 7;
        c.gridx = 0;
        root.add(new JLabel("输出目录（留空=目标应用工作目录/.callmap）："), c);
        c.gridx = 1;
        c.weightx = 1.0;
        c.fill = GridBagConstraints.HORIZONTAL;
        root.add(outputDirField, c);

        reset();
        return root;
    }

    @Override
    public boolean isModified() {
        CodeCallMapSettingsState s = CodeCallMapSettingsState.getInstance();
        if (autoAttachCheckBox.isSelected() != s.autoAttachEnabled) {
            return true;
        }
        if (!agentJarField.getText().trim().equals(s.agentJarPath == null ? "" : s.agentJarPath)) {
            return true;
        }
        if (!monitorPackagesField.getText().trim().equals(s.monitorPackages == null ? "" : s.monitorPackages)) {
            return true;
        }
        if (!outputDirField.getText().trim().equals(s.outputDir == null ? "" : s.outputDir)) {
            return true;
        }
        if (!sampleRateField.getText().trim().equals(String.valueOf(s.sampleRate))) {
            return true;
        }
        if (!minDurationField.getText().trim().equals(String.valueOf(s.minDurationMs))) {
            return true;
        }
        return !slowThresholdField.getText().trim().equals(String.valueOf(s.slowThresholdMs));
    }

    @Override
    public void apply() {
        CodeCallMapSettingsState s = CodeCallMapSettingsState.getInstance();
        s.autoAttachEnabled = autoAttachCheckBox.isSelected();
        s.agentJarPath = agentJarField.getText().trim();
        s.monitorPackages = monitorPackagesField.getText().trim();
        s.outputDir = outputDirField.getText().trim();
        try {
            s.sampleRate = Math.max(0, Math.min(100, Integer.parseInt(sampleRateField.getText().trim())));
        } catch (NumberFormatException ignored) {
            // 非法输入保持原值
        }
        try {
            s.minDurationMs = Math.max(0, Integer.parseInt(minDurationField.getText().trim()));
            s.slowThresholdMs = Math.max(0, Integer.parseInt(slowThresholdField.getText().trim()));
        } catch (NumberFormatException ignored) {
            // 非法输入保持原值
        }
    }

    @Override
    public void reset() {
        CodeCallMapSettingsState s = CodeCallMapSettingsState.getInstance();
        autoAttachCheckBox.setSelected(s.autoAttachEnabled);
        agentJarField.setText(s.agentJarPath == null ? "" : s.agentJarPath);
        monitorPackagesField.setText(s.monitorPackages == null ? "" : s.monitorPackages);
        outputDirField.setText(s.outputDir == null ? "" : s.outputDir);
        sampleRateField.setText(String.valueOf(s.sampleRate));
        minDurationField.setText(String.valueOf(s.minDurationMs));
        slowThresholdField.setText(String.valueOf(s.slowThresholdMs));
    }
}
