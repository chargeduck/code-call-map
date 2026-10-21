package com.github.chargeduck.codecallmap.run;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeBase;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Run/Debug 配置类型 "CodeCallMap"。
 *
 * <p>复用 {@link CodeCallMapRunConfiguration}（继承自 {@code ApplicationConfiguration}）的
 * 全套配置 UI（Main class / 模块 / 程序参数 / VM options），
 * 启动时由 {@link CodeCallMapProgramPatcher} 自动追加 {@code -javaagent}。
 */
public final class CodeCallMapRunConfigurationType extends ConfigurationTypeBase {

    /** 配置类型 id（写进 runConfigurations/*.xml，切勿改动）。 */
    public static final String TYPE_ID = "CodeCallMapRunConfiguration";

    /**
     * 工厂 id。
     *
     * <p><b>必须是稳定、非本地化的字符串</b>：它会被写入
     * {@code .idea/runConfigurations/<name>.xml} 的 {@code factoryName} 属性，
     * 用于把配置文件反序列化回正确的工厂。
     *
     * <p><b>取值刻意与旧行为保持一致</b>：本插件早期未覆写 {@code getId()}，
     * 于是走了父类默认实现 {@code return getName();}，实际写入磁盘的值就是
     * {@code "CodeCallMap"}。若这里改成别的字符串（如 "CodeCallMapFactory"），
     * 用户此前保存的所有 CodeCallMap 配置都会在反序列化时找不到工厂而失效。
     * 覆写的目的只是消除 IDEA 2026.x 的废弃告警，不改变既有持久化行为。
     */
    public static final String FACTORY_ID = "CodeCallMap";

    public CodeCallMapRunConfigurationType() {
        super(TYPE_ID, "CodeCallMap",
                "以 CodeCallMap 方式启动 Java 应用：自动注入 javaagent 录制调用链（生成 JSONL 供分析）",
                AllIcons.RunConfigurations.Application);
        addFactory(new CodeCallMapConfigurationFactory(this));
    }

    /**
     * 取本插件配置类型的主工厂。
     *
     * <p>供 {@link CodeCallMapRunConfigurationProducer} 构造时使用。
     * 直接 new 一个工厂实例而非从扩展点取：
     * {@code ConfigurationFactory} 只依赖 ConfigurationType 本身，
     * 不涉及项目/索引，构造开销可忽略，且不受扩展点初始化时序影响。
     */
    @NotNull
    static ConfigurationFactory defaultFactory() {
        // 复用一个静态实例即可，工厂是无状态的
        return Holder.DEFAULT;
    }

    /** 惰性持有工厂实例，避免类初始化期访问扩展点。 */
    private static final class Holder {
        static final ConfigurationFactory DEFAULT =
                new CodeCallMapConfigurationFactory(new CodeCallMapRunConfigurationType());
    }

    /** 独立成类（而非匿名类），便于报错栈定位与单元测试引用。 */
    static final class CodeCallMapConfigurationFactory extends ConfigurationFactory {

        CodeCallMapConfigurationFactory(@NotNull ConfigurationTypeBase type) {
            super(type);
        }

        @NotNull
        @Override
        public RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            return new CodeCallMapRunConfiguration("CodeCallMap", project, this);
        }

        /**
         * 覆写 {@code getId()}。
         *
         * <p>父类的默认实现是 {@code return getName();}。IDEA 2026.x 在
         * {@code RunManagerImpl.getFactoryKey()} 调用本方法时会检测「是否覆写了默认实现」，
         * 未覆写就抛 {@code PluginException: The default implementation of method 'getId'
         * is deprecated，... must override it}。这就是用户看到的报错来源。
         *
         * <p>返回值用常量而非 {@code getName()}，理由见 {@link #FACTORY_ID} 的注释：
         * 既要消除告警，又不能破坏已保存配置的反序列化。
         */
        @NotNull
        @Override
        public String getId() {
            return FACTORY_ID;
        }

        @NotNull
        @Override
        public String getName() {
            return "CodeCallMap";
        }
    }
}
