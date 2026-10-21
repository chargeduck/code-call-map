package com.github.chargeduck.codecallmap.webview;

import com.github.chargeduck.codecallmap.agent.CallMapConfig;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 调用链数据的读取与聚合（纯 Java，不依赖任何 UI / JCEF 类型）。
 *
 * <h2>为什么要有这个类</h2>
 * 数据读取与聚合需要能单独测试 —— 直接写在 {@link JSBridge} 里（由前端 cefQuery
 * 回调触发）会导致任何逻辑调整都必须启动 IDE 手工验证。
 * 抽成纯 Java 类后，会话解析、调用树构建、自身耗时计算都能用单测覆盖。
 *
 * <p>UI 侧有两处使用：{@link JSBridge} 通过它向前端供数（Vue 页面渲染图表）；
 * 单元测试直接构造临时目录验证聚合逻辑。
 */
public final class CallMapData {

    /** 单次最多读取的记录条数，避免超大文件把 IDE 卡死。 */
    private static final int MAX_RECORDS = 200_000;

    private CallMapData() {
    }

    /** 一条调用记录（span_start 与 span_end 按 spanId 合并后的结果）。 */
    public static final class SpanNode {
        public String traceId;
        public String spanId;
        public String parentId;
        public String className;
        public String method;
        public String thread;
        public String path;
        public String sql;
        public String exception;
        public String sourceFile;
        public Integer lineNumber;
        public long startTs;
        public Long durationMs;

        /** 子节点（按开始时间排序）。 */
        public final List<SpanNode> children = new ArrayList<>();

        /** 自身耗时 = 总耗时 − 子调用耗时之和。 */
        public long selfMs() {
            if (durationMs == null) {
                return 0L;
            }
            long childSum = 0L;
            for (SpanNode c : children) {
                if (c.durationMs != null) {
                    childSum += c.durationMs;
                }
            }
            return Math.max(0L, durationMs - childSum);
        }

        /** 简短显示名：SimpleClass.method。 */
        public String displayName() {
            String simple = className == null ? "?" : className;
            int dot = simple.lastIndexOf('.');
            if (dot >= 0) {
                simple = simple.substring(dot + 1);
            }
            return simple + "." + (method == null ? "?" : method);
        }

        /** 全限定名，用于跳转源码。 */
        public String qualifiedName() {
            return (className == null ? "" : className) + "#" + (method == null ? "" : method);
        }
    }

    /** 读取结果：根节点列表 + 数据目录 + 总记录数。 */
    public static final class Snapshot {
        public final List<SpanNode> roots = new ArrayList<>();
        public final Path outputDir;
        /** 本次读取的会话；为空表示没有可用会话。 */
        public Session session;
        public int recordCount;

        Snapshot(Path outputDir) {
            this.outputDir = outputDir;
        }

        public boolean isEmpty() {
            return roots.isEmpty();
        }
    }

    /** 当前数据目录（与 agent 侧保持一致的解析规则）。 */
    public static Path outputDir() {
        return CallMapConfig.fromSystemProperties().outputDir();
    }

    /**
     * 一次录制会话。
     *
     * <p>agent 每次启动都会新开一个 {@code callmap-yyyyMMdd-HHmmss-SSS.jsonl}，
     * 因此「一个文件 = 一次运行」。UI 按会话切换，
     * 避免把多次运行的记录混在同一棵调用树里（那样无法区分是哪次运行的数据）。
     */
    public static final class Session {
        /** 数据文件。 */
        public final Path file;
        /** 会话标识（文件名去掉扩展名），用作下拉展示与去重键。 */
        public final String id;
        /** 展示用标签，形如 {@code 10-19 14:08:37}。 */
        public final String label;
        /** 文件大小（字节），用于展示。 */
        public final long sizeBytes;
        /** 最后修改时间，用于排序。 */
        public final long lastModified;

        Session(Path file, long sizeBytes, long lastModified) {
            this.file = file;
            String name = file.getFileName().toString();
            this.id = name.endsWith(".jsonl")
                    ? name.substring(0, name.length() - ".jsonl".length())
                    : name;
            this.label = prettyLabel(this.id);
            this.sizeBytes = sizeBytes;
            this.lastModified = lastModified;
        }

        /**
         * 把 {@code callmap-20261019-140837-123} 转成 {@code 10-19 14:08:37}。
         *
         * <p>正则分组：1=年 2=月 3=日 4=时 5=分 6=秒。
         * 解析失败时退回原始文件名，保证任何命名都能显示。
         */
        private static String prettyLabel(String id) {
            Matcher m = SESSION_TS.matcher(id);
            if (!m.find()) {
                return id;
            }
            return m.group(2) + "-" + m.group(3) + " "
                    + m.group(4) + ":" + m.group(5) + ":" + m.group(6);
        }

        /** 文件是否还在（用户可能在外部删除了）。 */
        public boolean exists() {
            return Files.isRegularFile(file);
        }
    }

    /** 匹配 callmap-20261019-140837-123 中的时间部分。 */
    private static final Pattern SESSION_TS =
            Pattern.compile("callmap-(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})(\\d{2})-\\d+");

    /** 列出数据目录下的全部会话，按修改时间倒序（最新在前）。 */
    public static List<Session> listSessions() {
        List<Session> out = new ArrayList<>();
        Path dir = outputDir();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .forEach(p -> {
                        try {
                            out.add(new Session(p, Files.size(p), Files.getLastModifiedTime(p).toMillis()));
                        } catch (IOException ignored) {
                            // 单个文件读不到不影响其它会话列出
                        }
                    });
        } catch (IOException e) {
            return out;
        }
        // 按 mtime 倒序；mtime 相同时用文件名（内含 yyyyMMdd-HHmmss-SSS）兜底，
        // 保证"最新会话"在任何情况下都是确定的
        out.sort(Comparator.comparingLong((Session s) -> s.lastModified).reversed()
                .thenComparing(s -> s.id, Comparator.reverseOrder()));
        return out;
    }

    /** 读取「最新」会话；没有会话时返回空快照。 */
    public static Snapshot load() {
        List<Session> sessions = listSessions();
        return sessions.isEmpty() ? new Snapshot(outputDir()) : load(sessions.get(0));
    }

    /**
     * 读取指定会话并构建调用树。
     *
     * <p>采用「流式逐行正则解析」而非引入 JSON 库：
     * agent 写出的每行都是扁平 JSON 对象，字段固定，
     * 用正则取值足够且不增加依赖（插件体积与类加载开销都需要控制）。
     */
    public static Snapshot load(@Nullable Session session) {
        Path dir = outputDir();
        Snapshot snapshot = new Snapshot(dir);
        snapshot.session = session;
        if (session == null || !session.exists()) {
            return snapshot;
        }

        Map<String, SpanNode> byId = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(session.file, StandardCharsets.UTF_8)) {
                if (!line.isEmpty()) {
                    lines.add(line);
                    if (lines.size() >= MAX_RECORDS) {
                        break;
                    }
                }
            }
        } catch (IOException e) {
            return snapshot;
        }

        snapshot.recordCount = lines.size();
        for (String line : lines) {
            mergeLine(line, byId);
        }
        buildTree(byId, snapshot);
        return snapshot;
    }

    /** 解析一行 JSON，合并到 spanId 对应的节点上。 */
    private static void mergeLine(String line, Map<String, SpanNode> byId) {
        String spanId = str(line, "spanId");
        if (spanId == null || spanId.isEmpty()) {
            return;
        }
        SpanNode node = byId.computeIfAbsent(spanId, k -> new SpanNode());
        node.spanId = spanId;

        String traceId = str(line, "traceId");
        if (traceId != null) {
            node.traceId = traceId;
        }
        String parentId = str(line, "parentId");
        if (parentId != null && !"null".equals(parentId)) {
            node.parentId = parentId;
        }
        String className = str(line, "className");
        if (className != null) {
            node.className = className;
        }
        String method = str(line, "method");
        if (method != null) {
            node.method = method;
        }
        String thread = str(line, "thread");
        if (thread != null) {
            node.thread = thread;
        }
        String path = str(line, "path");
        if (path != null) {
            node.path = path;
        }
        String sql = str(line, "sql");
        if (sql != null) {
            node.sql = sql;
        }
        String exception = str(line, "exception");
        if (exception != null) {
            node.exception = exception;
        }
        String sourceFile = str(line, "sourceFile");
        if (sourceFile != null) {
            node.sourceFile = sourceFile;
        }
        Long lineNumber = num(line, "lineNumber");
        if (lineNumber != null) {
            node.lineNumber = lineNumber.intValue();
        }
        Long ts = num(line, "ts");
        if (ts != null && node.startTs == 0L) {
            node.startTs = ts;
        }
        Long duration = num(line, "durationMs");
        if (duration != null) {
            node.durationMs = duration;
        }
    }

    /** 按 parentId 组装树；找不到父节点的作为根。 */
    private static void buildTree(Map<String, SpanNode> byId, Snapshot snapshot) {
        for (SpanNode node : byId.values()) {
            if (node.parentId != null && byId.containsKey(node.parentId)) {
                byId.get(node.parentId).children.add(node);
            } else {
                snapshot.roots.add(node);
            }
        }
        Comparator<SpanNode> byStart = Comparator.comparingLong(n -> n.startTs);
        snapshot.roots.sort(byStart);
        for (SpanNode n : byId.values()) {
            n.children.sort(byStart);
        }
    }

    /** 取字符串字段值（形如 {@code "key":"value"}）。 */
    private static String str(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        if (!m.find()) {
            return null;
        }
        return unescape(m.group(1));
    }

    /** 取数值字段值（形如 {@code "key":123}）。 */
    private static Long num(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (!m.find()) {
            return null;
        }
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException ex) {
                                sb.append(n);
                            }
                        }
                        break;
                    default: sb.append(n); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
