package com.github.chargeduck.codecallmap.run;

import com.intellij.execution.Executor;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 从装订区（gutter）以 CodeCallMap 模式启动应用的执行入口。
 *
 * <p>职责很窄：把一个「Main class」变成一次真实运行。
 * <ol>
 *   <li>在 RunManager 中查找同名同主类的 {@link CodeCallMapRunConfiguration}，命中则复用；</li>
 *   <li>否则创建一个新配置（复制当前 CodeCallMap 模板，填入 Main class 与模块）；</li>
 *   <li>交给平台执行器启动（Run 或 Debug），javaagent 注入由
 *       {@link CodeCallMapProgramPatcher} 在启动前完成。</li>
 * </ol>
 *
 * <p>复用而非每次新建，是为了避免用户点几次就在配置列表里堆出一串重复项。
 */
final class CodeCallMapLauncher {

    private CodeCallMapLauncher() {
    }

    /**
     * 以 CodeCallMap 模式启动指定 Main class。
     *
     * @param project     当前项目
     * @param mainClassName Main class 全限定名
     * @param psiClass    用于确定所属模块（可为 null）
     * @param executor    Run 或 Debug 执行器
     */
    static void launch(@NotNull Project project,
                       @NotNull String mainClassName,
                       @Nullable PsiClass psiClass,
                       @NotNull Executor executor) {
        try {
            RunManager runManager = RunManager.getInstance(project);

            RunnerAndConfigurationSettings settings = findReusable(runManager, mainClassName);
            if (settings == null) {
                settings = createSettings(runManager, project, mainClassName, psiClass);
                if (settings == null) {
                    return;
                }
                runManager.addConfiguration(settings);
                // 关键：新建的配置默认是「临时配置」（isTemporary=true），
                // 临时配置不会出现在顶部 Run Configuration 下拉的常规列表里，
                // Services 面板也不会显示 —— 这正是用户反馈「顶部菜单/Services 看不到 CCM」的根因。
                // 显式存入 .idea 目录，让它变成持久配置，下拉与 Services 才能看到。
                try {
                    settings.storeInDotIdeaFolder();
                } catch (Throwable ignored) {
                    // 多模块/只读项目下可能失败，退化为临时配置，至少本次能启动
                }
            } else if (settings.isTemporary()) {
                // 复用到的配置若仍是临时的，也顺手持久化，避免一直不出现在下拉里
                try {
                    settings.storeInDotIdeaFolder();
                } catch (Throwable ignored) {
                }
            }

            // 选中该配置，让工具栏下拉与运行结果面板的归属一致
            runManager.setSelectedConfiguration(settings);

            // 交给平台执行：与用户在工具栏点 Run/Debug 走完全相同的路径
            com.intellij.execution.ProgramRunnerUtil.executeConfiguration(settings, executor);
        } catch (Throwable t) {
            // 装订区点击失败不应该冒泡成 IDE 异常弹窗
            com.intellij.openapi.diagnostic.Logger.getInstance(CodeCallMapLauncher.class)
                    .warn("CodeCallMap: 从装订区启动失败, mainClass=" + mainClassName, t);
        }
    }

    /** 查找可复用的已有配置：类型为 CodeCallMap 且 Main class 一致。 */
    @Nullable
    private static RunnerAndConfigurationSettings findReusable(@NotNull RunManager runManager,
                                                              @NotNull String mainClassName) {
        // 按配置类型过滤，避免遍历所有配置后再判断（配置多了性能更好，语义也更明确）
        List<RunnerAndConfigurationSettings> candidates =
                runManager.getConfigurationSettingsList(CodeCallMapRunConfigurationType.class);
        for (RunnerAndConfigurationSettings s : candidates) {
            RunConfiguration rc = s.getConfiguration();
            if (rc instanceof CodeCallMapRunConfiguration cm
                    && mainClassName.equals(cm.getMainClassName())) {
                return s;
            }
        }
        return null;
    }

    /** 取 CodeCallMap 配置类型的工厂；找不到时返回 null。 */
    @Nullable
    private static com.intellij.execution.configurations.ConfigurationFactory findFactory() {
        // 直接遍历扩展点，不依赖按类查找（不同平台版本上按类查找的行为有差异）
        for (ConfigurationType type : ConfigurationType.CONFIGURATION_TYPE_EP.getExtensionList()) {
            if (type instanceof CodeCallMapRunConfigurationType cmType) {
                com.intellij.execution.configurations.ConfigurationFactory[] factories =
                        cmType.getConfigurationFactories();
                return factories.length > 0 ? factories[0] : null;
            }
        }
        return null;
    }

    /** 基于模板创建一个新的 CodeCallMap 配置，并填入 Main class。 */
    @Nullable
    private static RunnerAndConfigurationSettings createSettings(@NotNull RunManager runManager,
                                                                 @NotNull Project project,
                                                                 @NotNull String mainClassName,
                                                                 @Nullable PsiClass psiClass) {
        // 从配置类型的工厂创建模板，保证拿到的是 CodeCallMap 类型（而非原生 Application）
        com.intellij.execution.configurations.ConfigurationFactory factory = findFactory();
        if (factory == null) {
            com.intellij.openapi.diagnostic.Logger.getInstance(CodeCallMapLauncher.class)
                    .warn("CodeCallMap: 未找到配置类型扩展，无法从装订区启动");
            return null;
        }

        RunnerAndConfigurationSettings settings =
                runManager.createConfiguration("CodeCallMap - " + simpleName(mainClassName), factory);
        RunConfiguration rc = settings.getConfiguration();
        if (rc instanceof CodeCallMapRunConfiguration cm) {
            cm.setMainClassName(mainClassName);
            // 绑定模块：优先用 Main class 所在模块，保证 classpath 正确
            if (psiClass != null) {
                try {
                    com.intellij.openapi.module.Module module =
                            com.intellij.openapi.module.ModuleUtilCore.findModuleForPsiElement(psiClass);
                    if (module != null) {
                        cm.setModule(module);
                    }
                } catch (Throwable ignored) {
                    // 拿不到模块也不影响启动（IDEA 会按类路径搜索）
                }
            }
            // 默认走自动探测包名（语义与手动新建配置一致）
            cm.setPackagesMode(CodeCallMapRunConfiguration.PackagesMode.AUTO);
        }
        return settings;
    }

    private static String simpleName(String fqcn) {
        int idx = fqcn.lastIndexOf('.');
        return idx >= 0 ? fqcn.substring(idx + 1) : fqcn;
    }
}
