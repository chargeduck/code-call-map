package com.github.chargeduck.codecallmap.run;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 CodeCall Map 的配置持久化到<b>项目目录</b>下，而不是只存在 IDE 的全局配置里。
 *
 * <h2>为什么需要这个</h2>
 * 原配置存在 IDE 的 {@code codeCallMapSettings.xml} 里，与具体项目无关。
 * 换一台机器、换一个 IDE 实例、或者同事拉下代码，都不知道
 * "这个项目上次采集到了哪里、用了什么包前缀"。
 *
 * <p>存到 {@code <项目>/.callmap/callmap-config.yml} 之后：
 * <ul>
 *   <li>历史录制目录可被重新发现 —— <b>即使用户这次没用 CCM 启动，
 *       也能打开工具窗口回看以前的调用链</b>；</li>
 *   <li>配置跟着代码走，可选择性提交到 git 供团队共享；</li>
 *   <li>换机器后开箱即用，不用重新配一遍。</li>
 * </ul>
 *
 * <h2>文件格式</h2>
 * <pre>
 * # CodeCall Map 配置（由插件自动维护）
 * outputDir: ${projectPath}/.callmap
 * monitorPackages: com.github.chargeduck
 * sampleRate: 100
 * minDurationMs: 1
 * </pre>
 *
 * <p>用极简的 {@code key: value} 行格式而非完整 YAML —— 只有几个标量字段，
 * 手写解析足够，也避免给插件引入 snakeyaml 依赖。
 */
public final class ProjectConfigStore {

    private static final Logger LOG = Logger.getInstance(ProjectConfigStore.class);

    /** 数据目录名（同时也是放置配置文件的位置）。 */
    public static final String DIR_NAME = ".callmap";

    /** 配置文件名。 */
    public static final String FILE_NAME = "callmap-config.yml";

    private ProjectConfigStore() {
    }

    /** 项目的 .callmap 目录；无法定位项目时返回 null。 */
    @Nullable
    public static Path dataDir(@Nullable Project project) {
        if (project == null || project.isDisposed() || project.getBasePath() == null) {
            return null;
        }
        return Paths.get(project.getBasePath(), DIR_NAME);
    }

    /** 配置文件路径；无法定位项目时返回 null。 */
    @Nullable
    public static Path configFile(@Nullable Project project) {
        Path dir = dataDir(project);
        return dir == null ? null : dir.resolve(FILE_NAME);
    }

    /**
     * 保存配置到项目目录。
     *
     * <p>失败只记日志，不抛出 —— 保存配置是"锦上添花"，
     * 不能因为它失败而影响用户正常使用插件。
     */
    public static void save(@Nullable Project project) {
        Path file = configFile(project);
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            CodeCallMapSettingsState s = CodeCallMapSettingsState.getInstance();
            StringBuilder sb = new StringBuilder(256);
            sb.append("# CodeCall Map 配置（由插件自动维护，可手工编辑）\n");
            sb.append("# 用途：记住本项目的采集目录与过滤规则，换机器 / 换 IDE 后仍能回看历史录制\n");
            sb.append("outputDir: ").append(nullToEmpty(s.outputDir)).append('\n');
            sb.append("monitorPackages: ").append(nullToEmpty(s.monitorPackages)).append('\n');
            sb.append("sampleRate: ").append(s.sampleRate).append('\n');
            sb.append("minDurationMs: ").append(s.minDurationMs).append('\n');
            Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.warn("CCM: 保存项目配置失败: " + file, e);
        }
    }

    /**
     * 从项目目录读回配置。
     *
     * @return 解析出的键值；文件不存在或解析失败时返回空 Map
     */
    @NotNull
    public static Map<String, String> load(@Nullable Project project) {
        Path file = configFile(project);
        if (file == null || !Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            return CallMapConfigLines.parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.warn("CCM: 读取项目配置失败: " + file, e);
            return new LinkedHashMap<>();
        }
    }

    /**
     * 项目配置文件里的采集目录；没有配置时返回 null。
     *
     * <p>用于"没跑过 CCM 也想看历史日志"这个场景 ——
     * 面板打开时优先按这个目录去找 jsonl。
     */
    @Nullable
    public static String savedOutputDir(@Nullable Project project) {
        String v = load(project).get("outputDir");
        return (v == null || v.isBlank()) ? null : v;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
