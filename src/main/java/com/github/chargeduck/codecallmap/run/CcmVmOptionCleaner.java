package com.github.chargeduck.codecallmap.run;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 清理历史版本写进用户运行配置的 CCM agent 参数（纯字符串处理）。
 *
 * <h2>背景</h2>
 * 早期实现（build ≤ 16）在 Runner 里用
 * {@code ApplicationConfiguration.setVMParameters(...)} 注入 {@code -javaagent}，
 * 那是<b>持久化</b>操作。后果：用户点过一次「Debug with CCM」之后，
 * 同一个配置即便改用原生 Run / Debug 启动，VM 参数里仍残留 CCM 的 agent，表现为
 * <ul>
 *   <li>原生运行也被插桩；</li>
 *   <li>CCM agent 与 IDEA 的 debugger-agent 叠加，
 *       抛 {@code LinkageError: attempted duplicate abstract class definition
 *       for org.apache.catalina.loader.WebappClassLoaderBase}，应用起不来。</li>
 * </ul>
 *
 * <p>新实现已改为"只注入本次进程参数、不碰配置"，
 * 但<b>已被写脏的配置不会自愈</b>，所以保留本类做一次性清理。
 *
 * <h2>为什么单独抽出来</h2>
 * 真正的清理入口 {@link CodeCallMapConfigCleaner} 依赖 IDEA 平台
 * （{@code ProjectActivity}），在纯单元测试环境里无法加载。
 * 把可测的字符串规则拆到这里，就能用普通 JUnit 覆盖边界情况 ——
 * 这段逻辑会删除用户配置内容，值得单独测。
 */
public final class CcmVmOptionCleaner {

    /**
     * agent jar 文件名特征。
     *
     * <p>{@code AgentJarLocator} 产出的是 {@code codeCallMap-agent.jar}
     * 或带哈希的 {@code codeCallMap-agent-<n>.jar}，统一按小写包含判断。
     */
    private static final String AGENT_MARKER = "codecallmap-agent";

    private CcmVmOptionCleaner() {
    }

    /**
     * 从 VM options 里摘掉 CCM 自己的 {@code -javaagent} 片段。
     *
     * <p>只删除同时满足两个条件的 token：
     * 以 {@code -javaagent:} 开头、且路径含 {@link #AGENT_MARKER}。
     * 用户手配的其它 agent 一律保留。
     *
     * @param vmOptions 原始 VM options（空格分隔）
     * @return 清理后的字符串；未发生删除时<b>原样返回</b>，便于调用方判断是否需要回写
     */
    @NotNull
    public static String removeCcmAgentOptions(@NotNull String vmOptions) {
        List<String> kept = new ArrayList<>();
        boolean removed = false;
        for (String token : vmOptions.split("\\s+")) {
            String lower = token.toLowerCase();
            if (lower.startsWith("-javaagent:") && lower.contains(AGENT_MARKER)) {
                removed = true;
                continue;
            }
            if (!token.isEmpty()) {
                kept.add(token);
            }
        }
        // 没有删除时返回原字符串（而非重新拼接），避免空白差异造成"看起来变了"
        return removed ? String.join(" ", kept) : vmOptions;
    }
}
