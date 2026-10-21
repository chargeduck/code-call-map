package com.github.chargeduck.codecallmap.agent.instrument;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插桩清单报告：记录 agent 到底改造了哪些类、哪些方法。
 *
 * <h2>为什么需要它</h2>
 * 排查问题时最费时间的不是"改代码"，而是"不知道发生了什么"：
 * <ul>
 *   <li>某个方法明明执行了，却不在调用链里 —— 是没插桩，还是被过滤器排除？</li>
 *   <li>启动变慢 —— 到底插了多少个类？哪些包？</li>
 *   <li>担心误伤三方库 —— 具体动了哪些非业务类？</li>
 * </ul>
 * 这三个问题的答案都在"插了什么"这件事上。把清单落盘，
 * 用户打开文件就能自查，不必反复重启试探。
 *
 * <h2>输出位置与格式</h2>
 * 写到 {@code <项目>/.callmap/instrument-report.txt}，纯文本、可直接阅读。
 * 选择文本而不是 JSON：这份文件是给人看的，不是给程序解析的。
 *
 * <p>写入时机是<b>首次需要时</b>与<b>关闭时</b>：前者让用户在应用运行中
 * 就能看到，后者保证最终状态完整。中间不反复写盘，避免影响启动性能。
 */
public final class InstrumentReport {

    /** 单条记录：类 → 方法。 */
    private static final class Entry {
        final String className;
        final String method;
        final String descriptor;
        final String actor;

        Entry(String className, String method, String descriptor, String actor) {
            this.className = className;
            this.method = method;
            this.descriptor = descriptor;
            this.actor = actor;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    /** 类名 → 该类插桩方法数，用于汇总。 */
    private final Map<String, Integer> perClass = new LinkedHashMap<>();
    private final Path outputFile;
    private final long startMillis = System.currentTimeMillis();
    private int dropped = 0;
    /** 生效的白名单描述，写进报告头，便于核对"为什么这个包没被插"。 */
    private String includeSpec;
    /** 生效的排除规则条数。 */
    private int excludeRuleCount;
    /** 生效的排除规则原文（前若干条）。 */
    private List<String> excludeSample;

    /** 上限：防止在超大项目里把内存和磁盘写爆。 */
    private static final int MAX_ENTRIES = 20000;

    public InstrumentReport(Path outputDir) {
        this.outputFile = outputDir == null ? null : outputDir.resolve("instrument-report.txt");
    }

    /**
     * 记录生效的过滤规则，写进报告头。
     *
     * <h2>为什么报告里要有这个</h2>
     * 排查"某个方法为什么没出现在调用链里"时，光有"插了什么"不够 ——
     * 更要紧的是"为什么没插"。把白名单与排除规则一起落盘，
     * 用户能直接判定是"没在采集范围"还是"被规则挡掉了"，
     * 不必再翻代码或反复改配置试探。
     */
    public synchronized void setFilterSnapshot(String includeSpec, int excludeRuleCount,
                                                List<String> excludeSample) {
        this.includeSpec = includeSpec;
        this.excludeRuleCount = excludeRuleCount;
        this.excludeSample = excludeSample == null
                ? null : new ArrayList<>(excludeSample);
    }

    /**
     * 记录一个被插桩的方法。
     *
     * @param actor {@code business} 表示业务类整体插桩；
     *              其它值为探针角色（如 {@code DB} / {@code HTTP}）
     */
    public synchronized void add(String className, String method, String descriptor, String actor) {
        if (className == null || method == null) {
            return;
        }
        // 统一成点分显示：插桩器传的是 ASM 内部名（com/acme/...），
        // 直接展示会与用户看到的包名不一致，也不利于 grep。
        String display = className.replace('/', '.');
        if (entries.size() >= MAX_ENTRIES) {
            dropped++;
            return;
        }
        entries.add(new Entry(display, method, descriptor, actor));
        perClass.merge(display, 1, Integer::sum);
    }

    public synchronized int classCount() {
        return perClass.size();
    }

    public synchronized int methodCount() {
        return entries.size();
    }

    /**
     * 写出清单文件。
     *
     * <p>写入失败不影响主流程 —— 这只是诊断产物。
     */
    public synchronized void write() {
        if (outputFile == null) {
            return;
        }
        try {
            Files.createDirectories(outputFile.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(outputFile,
                    StandardCharsets.UTF_8)) {
                writeHeader(w);
                writeSummary(w);
                writeDetails(w);
            }
        } catch (Throwable ignored) {
            // 诊断文件写失败不能影响目标应用
        }
    }

    private void writeHeader(BufferedWriter w) throws IOException {
        w.write("# CodeCallMap 插桩清单\n");
        w.write("# 生成时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date(startMillis)) + "\n");
        w.write("# 本文件说明 agent 实际改造了哪些类和方法，用于排查\n");
        w.write("#   · 某个方法为什么没出现在调用链里\n");
        w.write("#   · 启动阶段插了多少类、集中在哪些包\n");
        w.write("# 注意：只统计【真正改写了字节码】的类，被过滤器排除的不在此列。\n\n");
    }

    private void writeSummary(BufferedWriter w) throws IOException {
        w.write("=== 汇总 ===\n");
        w.write("插桩类数: " + perClass.size() + "\n");
        w.write("插桩方法数: " + entries.size() + "\n");
        if (includeSpec != null) {
            w.write("采集范围(include): " + includeSpec + "\n");
        }
        if (excludeRuleCount > 0) {
            w.write("排除规则(exclude): " + excludeRuleCount + " 条");
            if (excludeSample != null && !excludeSample.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < excludeSample.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(excludeSample.get(i));
                }
                w.write("（" + sb + (excludeRuleCount > excludeSample.size() ? " …" : "") + "）");
            }
            w.write("\n");
        }
        if (dropped > 0) {
            w.write("（另有 " + dropped + " 条超出上限未记录，"
                    + "上限 " + MAX_ENTRIES + " 条）\n");
        }
        // 按包统计，便于一眼看出插桩范围是否失控
        //
        // 注意：插桩器传入的 className 是 ASM 风格（斜杠分隔，形如
        //   com/github/chargeduck/OrderService），不是点分。
        // 第一版按 '.' 切，结果 200 个类全被归到 "(default)"，统计完全失效。
        // 这里先统一成点分再切。
        Map<String, Integer> perPackage = new LinkedHashMap<>();
        // 同时统计每个包的类数：只给方法数容易被误读成"类数"，
        // 而两者差异很大（一个 entity 包 30 个类就能有 1145 个方法）。
        Map<String, Integer> classPerPackage = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : perClass.entrySet()) {
            String cn = e.getKey().replace('/', '.');
            int cut = cn.lastIndexOf('.');
            String pkg = cut > 0 ? cn.substring(0, cut) : "(default)";
            perPackage.merge(pkg, e.getValue(), Integer::sum);
            classPerPackage.merge(pkg, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> pkgs = new ArrayList<>(perPackage.entrySet());
        pkgs.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
        // 列头写清楚单位是"方法数"，并附上类数，避免"210 个类"与
        // "1145 个方法"被混为一谈
        w.write("\n--- 按包统计（方法数 desc）---\n");
        int shown = 0;
        for (Map.Entry<String, Integer> e : pkgs) {
            if (shown++ >= 60) {
                w.write("... 还有 " + (pkgs.size() - 60) + " 个包未列出\n");
                break;
            }
            int cls = classPerPackage.getOrDefault(e.getKey(), 0);
            w.write(String.format("%5d 个方法 / %4d 个类  %s%n", e.getValue(), cls, e.getKey()));
        }
        w.write("\n");
    }

    private void writeDetails(BufferedWriter w) throws IOException {
        w.write("=== 明细（按类名排序）===\n");
        w.write("# 格式: 类#方法  [角色]  描述符\n");
        w.write("# 角色: business = 业务类整体插桩；DB / HTTP = 接口探针\n\n");
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator
                .comparing((Entry e) -> e.className)
                .thenComparing(e -> e.method));
        String lastClass = null;
        for (Entry e : sorted) {
            if (!e.className.equals(lastClass)) {
                int n = perClass.getOrDefault(e.className, 0);
                w.write("\n[" + e.className + "]  " + n + " 个方法\n");
                lastClass = e.className;
            }
            w.write("    " + e.method + "  [" + e.actor + "]  " + e.descriptor + "\n");
        }
    }
}
