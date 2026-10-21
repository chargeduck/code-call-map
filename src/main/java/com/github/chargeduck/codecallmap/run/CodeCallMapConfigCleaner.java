package com.github.chargeduck.codecallmap.run;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;


/**
 * 清理历史版本写入用户运行配置的 CCM agent 参数。
 *
 * <h2>为什么需要它</h2>
 * 早期实现（build ≤ 16）在 Runner 里用
 * {@code ApplicationConfiguration.setVMParameters(...)} 注入 {@code -javaagent}。
 * 那是<b>持久化</b>操作，会把参数写进用户的运行配置里。
 *
 * <p>后果：用户点过一次「Debug with CCM」之后，同一个配置即便改用
 * IDEA 原生的 Run / Debug 启动，VM 参数里仍残留 CCM 的 agent，表现为
 * <ul>
 *   <li>原生运行也被插桩；</li>
 *   <li>CCM agent 与 IDEA 的 debugger-agent 叠加，
 *       抛 {@code LinkageError: attempted duplicate abstract class definition
 *       for org.apache.catalina.loader.WebappClassLoaderBase}，应用直接起不来。</li>
 * </ul>
 *
 * <p>新实现已改为"只注入本次进程参数、不碰配置"，但<b>已经被写脏的配置不会自愈</b>，
 * 所以这里在项目打开时做一次静默清理：把指着 CCM agent jar 的
 * {@code -javaagent} 片段从 VM options 里摘掉。
 *
 * <h2>安全性</h2>
 * 只删除同时满足两个条件的片段：以 {@code -javaagent:} 开头，
 * 且路径中包含 CCM agent jar 的特征名。用户自己配的其它 agent 一律不动。
 */
public final class CodeCallMapConfigCleaner implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(CodeCallMapConfigCleaner.class);

    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        try {
            clean(project);
        } catch (Throwable t) {
            // 清理是"善后"性质的，失败绝不应该影响项目打开
            LOG.warn("CCM: 清理历史 agent 参数失败", t);
        }
        return Unit.INSTANCE;
    }

    /**
     * 扫描所有运行配置，移除残留的 CCM agent 参数。
     *
     * @return 被清理的配置数量（便于日志与将来做提示）
     */
    public static int clean(@NotNull Project project) {
        RunManager runManager = RunManager.getInstance(project);
        int cleaned = 0;
        for (RunnerAndConfigurationSettings settings : runManager.getAllSettings()) {
            // 只处理 ApplicationConfiguration：旧实现只可能污染这一类
            if (!(settings.getConfiguration() instanceof ApplicationConfiguration config)) {
                continue;
            }
            String original = config.getVMParameters();
            if (original == null || original.isEmpty()) {
                continue;
            }
            String cleanedOptions = CcmVmOptionCleaner.removeCcmAgentOptions(original);
            if (!cleanedOptions.equals(original)) {
                config.setVMParameters(cleanedOptions);
                cleaned++;
                LOG.info("CCM: 已从运行配置「" + settings.getName() + "」移除残留的 agent 参数");
            }
        }
        return cleaned;
    }

}
