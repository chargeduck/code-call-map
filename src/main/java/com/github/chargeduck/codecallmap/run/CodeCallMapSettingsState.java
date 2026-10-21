package com.github.chargeduck.codecallmap.run;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * CodeCallMap 插件设置（应用级持久化到 codeCallMapSettings.xml）。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code autoAttachEnabled}：自动接管开关，开启后普通 Java Application 配置启动时也自动注入 agent；</li>
 *   <li>{@code agentJarPath}：agent jar 绝对路径，留空时自动定位项目 build/libs 下的 agent jar；</li>
 *   <li>{@code monitorPackages}：需要插桩的包前缀（逗号分隔），对应 agent 的 {@code include.packages}。
 *       留空时 agent 按内置黑名单排除 JDK/框架包后全量插桩；</li>
 *   <li>{@code sampleRate}：采样率 0-100，默认 100；</li>
 *   <li>{@code minDurationMs}：低于该耗时（毫秒）的非根调用不落盘，默认 5，
 *       调大可显著降低数据量与 IO；</li>
 *   <li>{@code outputDir}：JSONL 输出目录，留空时落到目标应用工作目录下的 {@code .callmap}。</li>
 * </ul>
 */
@State(name = "CodeCallMapSettings", storages = {@Storage("codeCallMapSettings.xml")})
public final class CodeCallMapSettingsState implements PersistentStateComponent<CodeCallMapSettingsState> {

    public boolean autoAttachEnabled = false;
    public String agentJarPath = "";
    public String monitorPackages = "";
    public int sampleRate = 100;
    /**
     * 采集过滤阈值（毫秒）：低于它的非根调用不写入 JSONL。
     *
     * <p>默认 5：与 {@code CallMapConfig.DEFAULT_MIN_DURATION_MS} 保持一致。
     * 旧默认值 0（全部记录）会让 JSONL 塞满 1ms 级的 getter 与 SQL 探针，
     * 真正要看的业务调用反而被淹没。
     */
    public int minDurationMs = 5;

    /**
     * 慢调用阈值（毫秒）：耗时 ≥ 该值的调用会被标记为"慢"。
     *
     * <h2>与 {@link #minDurationMs} 的区别（很容易搞混）</h2>
     * <ul>
     *   <li>{@code minDurationMs} 是<b>采集过滤</b>：低于它的 span 根本不写入 JSONL，
     *       调大能降噪，但也会丢数据；</li>
     *   <li>本字段是<b>展示判定</b>：数据照常采集，只是超过阈值的会被标红、
     *       计入慢调用统计。</li>
     * </ul>
     * 所以设成 60000 时，正确做法是配本字段，而不是把 minDurationMs 调到 60000
     * （那会让 60 秒以下的调用全部消失，连统计样本都没有了）。
     */
    public int slowThresholdMs = 1000;
    public String outputDir = "";

    public static CodeCallMapSettingsState getInstance() {
        return ApplicationManager.getApplication().getService(CodeCallMapSettingsState.class);
    }

    @Nullable
    @Override
    public CodeCallMapSettingsState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull CodeCallMapSettingsState state) {
        this.autoAttachEnabled = state.autoAttachEnabled;
        this.agentJarPath = state.agentJarPath == null ? "" : state.agentJarPath;
        this.monitorPackages = state.monitorPackages == null ? "" : state.monitorPackages;
        this.sampleRate = state.sampleRate;
        this.minDurationMs = state.minDurationMs;
        this.outputDir = state.outputDir == null ? "" : state.outputDir;
    }
}
