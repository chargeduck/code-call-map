package com.github.chargeduck.codecallmap.agent;

import java.util.Map;

/**
 * 调用链路 Span 事件数据模型。
 *
 * <p>JSONL 中每一行即一个事件，支持三类事件：
 * <ul>
 *   <li>{@code span_start}：方法/接口调用开始（记录入参、线程、调用路径）</li>
 *   <li>{@code span_end}：调用结束（记录耗时、SQL、异常）</li>
 *   <li>{@code trace_summary}：整条调用链汇总（用于 AI 分析卡点）</li>
 * </ul>
 *
 * <p>纯 Java 实现，不依赖任何框架与 IntelliJ Platform API，后续可直接拆分为独立 javaagent 使用。
 */
public final class Span {

    /** 事件类型（JSONL 中对应 {@code type} 字段）。 */
    public enum Type {
        span_start("span_start"),
        span_end("span_end"),
        trace_summary("trace_summary");

        private final String jsonValue;

        Type(String jsonValue) {
            this.jsonValue = jsonValue;
        }

        public String jsonValue() {
            return jsonValue;
        }
    }

    private final Type type;
    private final String traceId;
    private final String spanId;
    private final String parentId;
    private final long ts;              // 事件时间戳（毫秒）
    private final String thread;        // 线程名
    private final String className;     // 类名
    private final String method;        // 方法名
    private final String args;          // 入参 JSON 字符串（旧版本内联字段，保留兼容读取）
    /**
     * 入参文件的相对路径（相对会话目录），如 {@code args/<spanId>.json}。
     *
     * <p>为什么把入参挪到独立文件：
     * <ul>
     *   <li>JSONL 要能一行一行解析，内联大入参会让单行达到 MB 级，
     *       前端读取与回放都会卡；</li>
     *   <li>入参不需要任何长度上限 —— 用户明确要求"信息必须完整"，
     *       独立文件可以原样保全，包括 1MB 级的请求体；</li>
     *   <li>回放时按 spanId 直接定位文件，比在 JSONL 里翻找更方便。</li>
     * </ul>
     */
    private final String argsFile;
    /** 入参文件字节数；0 表示无入参。 */
    private final long argsSize;
    /**
     * 返回值文件的相对路径（相对会话目录），如 {@code ret/<spanId>.json}。
     *
     * <p>返回值与入参一样外置：它通常比入参更大（列表/实体），
     * 内联会让 JSONL 单行膨胀到 MB 级。只有<b>确认写盘</b>的 span
     * （根节点、慢调用、异常）才会产出该文件，快调用不采集。
     */
    private final String retFile;
    /** 返回值文件字节数；0 表示无返回值或未采集。 */
    private final long retSize;
    private final Long durationMs;      // 耗时（span_end / trace_summary 使用）
    private final String sql;           // SQL 语句（span_end 使用，已脱敏）
    private final String exception;     // 异常摘要（span_end 使用）
    private final String path;          // 调用路径 / 路由（如 /api/order/create）
    private final String sourceFile;    // 源码文件名（如 OrderController.java，采集自调用栈）
    private final Integer lineNumber;   // 源码行号（调用记录处，可空）

    private Span(Builder b) {
        this.type = b.type;
        this.traceId = b.traceId;
        this.spanId = b.spanId;
        this.parentId = b.parentId;
        this.ts = b.ts > 0 ? b.ts : System.currentTimeMillis();
        this.thread = b.thread != null ? b.thread : Thread.currentThread().getName();
        this.className = b.className;
        this.method = b.method;
        this.args = b.args;
        this.argsFile = b.argsFile;
        this.argsSize = b.argsSize;
        this.retFile = b.retFile;
        this.retSize = b.retSize;
        this.durationMs = b.durationMs;
        this.sql = b.sql;
        this.exception = b.exception;
        this.path = b.path;
        this.sourceFile = b.sourceFile;
        this.lineNumber = b.lineNumber;
    }

    public static Builder builder(Type type, String traceId, String spanId) {
        return new Builder(type, traceId, spanId);
    }

    public Type type() { return type; }
    public String traceId() { return traceId; }
    public String spanId() { return spanId; }
    public String parentId() { return parentId; }
    public long ts() { return ts; }
    public String thread() { return thread; }
    public String className() { return className; }
    public String method() { return method; }
    public String args() { return args; }
    /** 返回值文件相对路径；null 表示未采集（快调用被丢弃，或方法返回 void）。 */
    public String retFile() { return retFile; }
    /** 返回值文件字节数。 */
    public long retSize() { return retSize; }
    public Long durationMs() { return durationMs; }
    public String sql() { return sql; }
    public String exception() { return exception; }
    public String path() { return path; }
    public String sourceFile() { return sourceFile; }
    public Integer lineNumber() { return lineNumber; }

    /** 序列化为一行 JSON（未做脱敏；脱敏请在写入前对 args 字段处理）。 */
    public String toJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendString(sb, "type", type.jsonValue());
        appendString(sb, "traceId", traceId);
        appendString(sb, "spanId", spanId);
        appendString(sb, "parentId", parentId);
        appendNumber(sb, "ts", ts);
        appendString(sb, "thread", thread);
        appendString(sb, "className", className);
        appendString(sb, "method", method);
        if (args != null && !args.isEmpty()) {
            // 旧格式：入参内联。仅用于兼容历史数据，新采集不再走这条。
            sb.append("\"args\":").append(args).append(',');
        }
        // 新格式：入参外置为独立文件，这里只记录引用与大小。
        if (argsFile != null && !argsFile.isEmpty()) {
            appendString(sb, "argsFile", argsFile);
            appendNumber(sb, "argsSize", argsSize);
        }
        // 返回值同样外置（仅对确认写盘的 span 产出）
        if (retFile != null && !retFile.isEmpty()) {
            appendString(sb, "retFile", retFile);
            appendNumber(sb, "retSize", retSize);
        }
        appendNumber(sb, "durationMs", durationMs);
        appendString(sb, "sql", sql);
        appendString(sb, "exception", exception);
        appendString(sb, "path", path);
        appendString(sb, "sourceFile", sourceFile);
        appendNumber(sb, "lineNumber", lineNumber);
        if (sb.charAt(sb.length() - 1) == ',') {
            sb.setLength(sb.length() - 1);
        }
        sb.append('}');
        return sb.toString();
    }

    private static void appendString(StringBuilder sb, String key, String value) {
        if (value != null) {
            sb.append('"').append(key).append("\":\"").append(escape(value)).append("\",");
        }
    }

    private static void appendNumber(StringBuilder sb, String key, Number value) {
        if (value != null) {
            sb.append('"').append(key).append("\":").append(value).append(',');
        }
    }

    /** JSON 字符串转义（反斜杠、双引号、控制字符）。 */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // Java 8 兼容写法：agent jar 需能被 JDK 8+ 的目标 JVM 加载，
            // 因此不能使用 switch 箭头语法（Java 14+）
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    /** 便捷：将结构化参数 Map 序列化为 JSON 字符串（未脱敏）。 */
    public static String argsToJson(Map<?, ?> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        for (Map.Entry<?, ?> e : args.entrySet()) {
            Object key = e.getKey();
            if (key == null) {
                continue;
            }
            sb.append('"').append(escape(key.toString())).append("\":");
            appendJsonValue(sb, e.getValue());
            sb.append(',');
        }
        sb.setLength(sb.length() - 1);
        sb.append('}');
        return sb.toString();
    }

    private static void appendJsonValue(StringBuilder sb, Object value) {
        // Java 8 兼容：不使用 instanceof 模式匹配（Java 16+）
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map) {
            sb.append(argsToJson((Map<?, ?>) value));
        } else if (value instanceof Iterable) {
            sb.append('[');
            for (Object o : (Iterable<?>) value) {
                appendJsonValue(sb, o);
                sb.append(',');
            }
            if (sb.charAt(sb.length() - 1) == ',') {
                sb.setLength(sb.length() - 1);
            }
            sb.append(']');
        } else {
            sb.append('"').append(escape(String.valueOf(value))).append('"');
        }
    }

    /** 构造器。 */
    public static final class Builder {
        private final Type type;
        private final String traceId;
        private final String spanId;
        private String parentId;
        private long ts;
        private String thread;
        private String className;
        private String method;
        private String args;
        private String argsFile;
        private long argsSize;
        private String retFile;
        private long retSize;
        private Long durationMs;
        private String sql;
        private String exception;
        private String path;
        private String sourceFile;
        private Integer lineNumber;

        private Builder(Type type, String traceId, String spanId) {
            this.type = type;
            this.traceId = traceId;
            this.spanId = spanId;
        }

        public Builder parentId(String parentId) { this.parentId = parentId; return this; }
        public Builder ts(long ts) { this.ts = ts; return this; }
        public Builder thread(String thread) { this.thread = thread; return this; }
        public Builder className(String className) { this.className = className; return this; }
        public Builder method(String method) { this.method = method; return this; }
        public Builder args(String args) { this.args = args; return this; }
        /** 入参外置文件（相对会话目录的路径）+ 字节数。 */
        public Builder argsFile(String argsFile, long argsSize) {
            this.argsFile = argsFile;
            this.argsSize = argsSize;
            return this;
        }
        /** 返回值外置文件（相对会话目录的路径）+ 字节数。 */
        public Builder retFile(String retFile, long retSize) {
            this.retFile = retFile;
            this.retSize = retSize;
            return this;
        }
        public Builder args(Map<String, ?> args) { this.args = argsToJson(args); return this; }
        public Builder durationMs(long durationMs) { this.durationMs = durationMs; return this; }
        public Builder sql(String sql) { this.sql = sql; return this; }
        public Builder exception(String exception) { this.exception = exception; return this; }
        public Builder path(String path) { this.path = path; return this; }
        public Builder sourceFile(String sourceFile) { this.sourceFile = sourceFile; return this; }
        public Builder lineNumber(Integer lineNumber) { this.lineNumber = lineNumber; return this; }

        public Span build() {
            return new Span(this);
        }
    }
}
