package com.github.chargeduck.codecallmap.run;

import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.openapi.project.Project;
import com.intellij.ide.util.PropertiesComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * CodeCallMap 专用 Run 配置。
 *
 * <p>继承 {@link ApplicationConfiguration} 复用其全套配置 UI（Main class / 模块 / 程序参数 /
 * VM options）与启动逻辑；额外提供「插桩包名」设置（仿 Spring {@code @ComponentScan} 语义）：
 * <ul>
 *   <li>{@code packagesMode = AUTO}：不写包名，启动前由 {@link PackageProbe} 从
 *       Main class 与模块源码根自动探测；</li>
 *   <li>{@code packagesMode = MANUAL}：使用用户手填的 {@code packagesSpec}（逗号分隔多包名）；</li>
 *   <li>{@code packagesMode = AUTO_PLUS}：自动探测结果 + 用户补充（用户填的优先保留）。</li>
 * </ul>
 *
 * <h2>为什么用 PropertiesComponent 而不是 RunConfigurationOptions</h2>
 * {@code ApplicationConfiguration.getDefaultOptionsClass()} 是 {@code protected final}，
 * 无法替换成自定义 Options 子类（换则父类构造器报错）。因此这些「插件私有」字段
 * 单独存到 {@link PropertiesComponent}，按配置名做键，效果等价且不侵入平台机制。
 */
public final class CodeCallMapRunConfiguration extends ApplicationConfiguration {

    /** 包名来源模式。 */
    public enum PackagesMode {
        /** 纯自动探测（默认，零配置）。 */
        AUTO,
        /** 纯手工填写。 */
        MANUAL,
        /** 自动探测 + 手工补充。 */
        AUTO_PLUS
    }

    /** PropertiesComponent 键前缀。 */
    private static final String KEY_PREFIX = "codecallmap.runconfig.";

    private static final String KEY_PACKAGES = ".packages";
    private static final String KEY_MODE = ".packagesMode";

    public CodeCallMapRunConfiguration(@NotNull String name,
                                       @NotNull Project project,
                                       @NotNull ConfigurationFactory factory) {
        super(name, project, factory);
    }

    /**
     * 在标准 Application 配置 UI 之上，追加一个「插桩包名」设置区块。
     *
     * <p>注意泛型：父类返回 {@code SettingsEditor<? extends ApplicationConfiguration>}，
     * 而 {@link SettingsEditorGroup} 要求编辑器与目标配置类型一致。
     * 这里用裸类型规避泛型不变性（平台自身在同类场景也是这么处理的），
     * 两个编辑器实际都作用于 {@code ApplicationConfiguration} 的字段，运行时安全。
     */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public com.intellij.openapi.options.SettingsEditor<ApplicationConfiguration> getConfigurationEditor() {
        com.intellij.openapi.options.SettingsEditorGroup group =
                new com.intellij.openapi.options.SettingsEditorGroup();
        group.addEditor("配置", super.getConfigurationEditor());
        group.addEditor("插桩范围", new CodeCallMapPackagesEditor(getProject()));
        return group;
    }

    // ---------- 包名设置（持久化在 PropertiesComponent，按配置名区分） ----------

    /** 用户填写 / 探测到的包名规格（逗号分隔）。 */
    public String getPackagesSpec() {
        return props().getValue(key(KEY_PACKAGES), "");
    }

    public void setPackagesSpec(@Nullable String spec) {
        props().setValue(key(KEY_PACKAGES), spec == null ? "" : spec.trim());
    }

    /** 包名来源模式。 */
    @NotNull
    public PackagesMode getPackagesMode() {
        String raw = props().getValue(key(KEY_MODE), PackagesMode.AUTO.name());
        try {
            return PackagesMode.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return PackagesMode.AUTO;
        }
    }

    public void setPackagesMode(@NotNull PackagesMode mode) {
        props().setValue(key(KEY_MODE), mode.name());
    }

    /**
     * 解析出最终要传给 agent 的包名规格。
     *
     * <p>这是「配置意图 → 实际包名」的唯一出口，由 {@link CodeCallMapProgramPatcher}
     * 在启动前调用（此时项目索引已就绪，可安全做 Psi 查询）。
     *
     * @return 逗号分隔的包名；返回空串表示让 agent 按内置黑名单全量插桩
     */
    @NotNull
    public String resolvePackagesSpec() {
        String manual = getPackagesSpec() == null ? "" : getPackagesSpec().trim();
        PackagesMode mode = getPackagesMode();

        if (mode == PackagesMode.MANUAL) {
            return manual;
        }

        // AUTO / AUTO_PLUS：探测
        PackageProbe.Result probed = PackageProbe.probe(getProject(), this, getMainClassName());
        java.util.List<String> merged = new java.util.ArrayList<>();

        if (mode == PackagesMode.AUTO_PLUS) {
            // 用户填写的放前面（显式意图优先）
            merged.addAll(PackageProbe.parseSpec(manual));
        }
        for (String p : probed.packages) {
            if (!merged.contains(p)) {
                merged.add(p);
            }
        }
        // 去掉被父包覆盖的冗余项
        return PackageProbe.formatAsSpec(PackageProbe.removeRedundant(merged));
    }

    /** 探测说明（供 UI 展示）。 */
    @NotNull
    public PackageProbe.Result probeNow() {
        return PackageProbe.probe(getProject(), this, getMainClassName());
    }

    private PropertiesComponent props() {
        return PropertiesComponent.getInstance(getProject());
    }

    private String key(String suffix) {
        return KEY_PREFIX + getName() + suffix;
    }
}
