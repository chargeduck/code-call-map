package com.github.chargeduck.codecallmap.agent;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 调用链路落盘配置。
 *
 * <p>支持通过系统属性覆盖（便于 javaagent / 启动参数注入，无需改代码）：
 * <ul>
 *   <li>{@code callmap.output.dir}：输出目录，默认 {@code <baseDir>/.callmap}</li>
 *   <li>{@code callmap.sample.rate}：采样率 0-100，默认 100</li>
 *   <li>{@code callmap.max.file.bytes}：单分片最大字节数，默认 10MB</li>
 *   <li>{@code callmap.max.records}：单分片最大记录条数，默认 1000</li>
 * </ul>
 *
 * <p>纯 Java 实现，不依赖 IntelliJ Platform API。
 */
public final class CallMapConfig {

    public static final String PROP_OUTPUT_DIR = "callmap.output.dir";
    public static final String PROP_SAMPLE_RATE = "callmap.sample.rate";
    public static final String PROP_MAX_FILE_BYTES = "callmap.max.file.bytes";
    public static final String PROP_MAX_RECORDS = "callmap.max.records";

    /** 需要插桩的包前缀（逗号分隔）；为空则按内置黑名单排除 JDK / 框架包。 */
    public static final String PROP_INCLUDE_PACKAGES = "callmap.include.packages";
    /** 低于该耗时（毫秒）的非根调用不落盘，默认 5ms。设为 0 记录全部。 */
    public static final String PROP_MIN_DURATION_MS = "callmap.min.duration.ms";
    /** 是否启用字节码插桩自动采集，默认 true。 */
    public static final String PROP_INSTRUMENT_ENABLED = "callmap.instrument.enabled";

    public static final String DEFAULT_DIR_NAME = ".callmap";
    public static final int DEFAULT_SAMPLE_RATE = 100;
    public static final long DEFAULT_MAX_FILE_BYTES = 10L * 1024 * 1024; // 10MB
    public static final long DEFAULT_MAX_RECORDS = 1000L;

    /**
     * 默认最小记录耗时（毫秒）。
     *
     * <h2>为什么从 1 改到 5</h2>
     * 1ms 这个阈值几乎等于"不设阈值"：Spring 应用里绝大多数方法调用都在
     * 1ms 以下，全被计入就会让 JSONL 塞满无意义的 getter / 工具方法，
     * 而且每条都要付一次序列化 + 落盘开销。
     * 实测某项目 minDurationMs=1 时，落盘记录几乎全是"1ms 的 SQL 探针"，
     * 真正想看的业务调用反而淹没在里面。
     * 5ms 能滤掉噪音，又不会漏掉任何有分析价值的慢调用。
     */
    public static final long DEFAULT_MIN_DURATION_MS = 5L;

    private final Path outputDir;
    private final int sampleRate;
    private final long maxFileBytes;
    private final long maxRecords;
    private final String includePackages;
    private final long minDurationMs;
    private final boolean instrumentEnabled;

    private CallMapConfig(Path outputDir, int sampleRate, long maxFileBytes, long maxRecords,
                          String includePackages, long minDurationMs, boolean instrumentEnabled) {
        this.outputDir = outputDir;
        this.sampleRate = sampleRate;
        this.maxFileBytes = maxFileBytes;
        this.maxRecords = maxRecords;
        this.includePackages = includePackages == null ? "" : includePackages;
        this.minDurationMs = minDurationMs;
        this.instrumentEnabled = instrumentEnabled;
    }

    /** 基于项目目录构建配置（默认输出 {@code <项目目录>/.callmap}），可被系统属性覆盖。 */
    public static CallMapConfig of(Path baseDir) {
        Path dir = resolveOutputDir(baseDir);
        int rate = intProperty(PROP_SAMPLE_RATE, DEFAULT_SAMPLE_RATE);
        long maxBytes = longProperty(PROP_MAX_FILE_BYTES, DEFAULT_MAX_FILE_BYTES);
        long maxRecords = longProperty(PROP_MAX_RECORDS, DEFAULT_MAX_RECORDS);
        String include = System.getProperty(PROP_INCLUDE_PACKAGES, "");
        long minDuration = longProperty(PROP_MIN_DURATION_MS, DEFAULT_MIN_DURATION_MS);
        boolean instrument = boolProperty(PROP_INSTRUMENT_ENABLED, true);
        return new CallMapConfig(dir, rate, maxBytes, maxRecords, include, minDuration, instrument);
    }

    /**
     * 解析输出目录，容错处理非法路径。
     *
     * <p>用户可能把 {@code output.dir} 填成非法值（含 {@code :} 的畸形串、不可解析的盘符等）。
     * 此时若直接抛 {@link java.nio.file.InvalidPathException}，
     * {@code AgentBootstrap} 的初始化会整体失败——连字节码插桩都不会启用，
     * 而用户看到的症状只是「工具窗口没有数据」，极难定位。
     * 因此这里降级为「退回默认目录并打印警告」，保证采集能力不受影响。
     */
    private static Path resolveOutputDir(Path baseDir) {
        String configured = System.getProperty(PROP_OUTPUT_DIR);
        if (configured != null && !configured.trim().isEmpty()) {
            try {
                return Paths.get(configured.trim());
            } catch (Exception e) {
                System.err.println("[CodeCallMap] output.dir 非法，已退回默认目录。"
                        + "配置值=" + configured + "，原因=" + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
            }
        }
        if (baseDir != null) {
            try {
                return baseDir.resolve(DEFAULT_DIR_NAME);
            } catch (Exception ignored) {
                // 落到下面的最终兜底
            }
        }
        return Paths.get(DEFAULT_DIR_NAME);
    }

    /** 直接指定全部参数构建配置。 */
    public static CallMapConfig of(Path outputDir, int sampleRate, long maxFileBytes, long maxRecords) {
        return new CallMapConfig(outputDir, sampleRate, maxFileBytes, maxRecords,
                "", DEFAULT_MIN_DURATION_MS, true);
    }

    /** 默认项目目录取 {@code user.dir}（后续由调用方显式传入更佳）。 */
    public static CallMapConfig fromSystemProperties() {
        return of(Paths.get(System.getProperty("user.dir", ".")));
    }

    public Path outputDir() {
        return outputDir;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public long maxFileBytes() {
        return maxFileBytes;
    }

    public long maxRecords() {
        return maxRecords;
    }

    /** 需要插桩的包前缀（逗号分隔）；空串表示按内置黑名单排除。 */
    public String includePackages() {
        return includePackages;
    }

    /** 低于该耗时的非根调用不落盘（毫秒）。 */
    public long minDurationMs() {
        return minDurationMs;
    }

    /** 是否启用字节码插桩自动采集。 */
    public boolean instrumentEnabled() {
        return instrumentEnabled;
    }

    private static int intProperty(String key, int defaultValue) {
        String v = System.getProperty(key);
        if (v == null || v.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long longProperty(String key, long defaultValue) {
        String v = System.getProperty(key);
        if (v == null || v.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean boolProperty(String key, boolean defaultValue) {
        String v = System.getProperty(key);
        if (v == null || v.trim().isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(v.trim());
    }
}
