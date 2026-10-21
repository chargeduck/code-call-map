package com.github.chargeduck.codecallmap.webview;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次方法调用的「录制现场」快照。
 *
 * <p>数据结构与 {@code Span} 有重叠但不是同一个东西：
 * <ul>
 *   <li>{@code Span} 是<b>采集侧</b>的传输格式（JSONL 一行），字段偏向"写盘"；</li>
 *   <li>本类是<b>展示侧</b>的视图模型，只保留编辑器里要显示的内容，
 *       且已把外置的 {@code args}/{@code ret} 文件读成可直接渲染的字符串。</li>
 * </ul>
 *
 * <p>为什么要单独一层：编辑器内联提示（inlay）对"显示什么"有很硬的约束 ——
 * 一行里只能放几十个字符，超过就把行挤爆。所以这里额外做了一次
 * {@link #shortArgs()} 之类的短摘要，把完整的 Map 留给 tooltip 与详情面板。
 *
 * @author chargeduck
 */
public final class CcmCallRecord {

    /** span 唯一标识（同时是 args/ret 文件名，回放时按它定位）。 */
    private final String spanId;
    /** 调用发生时间戳（毫秒）。 */
    private final long ts;
    /** 线程名。 */
    private final String thread;
    /** 耗时（毫秒）。 */
    private final long durationMs;
    /** 异常摘要；无异常为 null。 */
    private final String exception;
    /** 入参：参数名 -> 已渲染的值文本（保持采集顺序）。 */
    private final Map<String, String> args;
    /**
     * 返回值文本。
     * <p>
     * null 有<b>两种</b>含义，靠 {@link #hasReturn} 区分：
     * void 方法（不存在返回值）vs 方法返回了 null。
     */
    private final String ret;
    /** 出口探针是否带了返回值（false = void 方法）。 */
    private final boolean hasReturn;

    private CcmCallRecord(Builder b) {
        this.spanId = b.spanId;
        this.ts = b.ts;
        this.thread = b.thread;
        this.durationMs = b.durationMs;
        this.exception = b.exception;
        this.args = b.args;
        this.ret = b.ret;
        this.hasReturn = b.hasReturn;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String spanId() {
        return spanId;
    }

    public long ts() {
        return ts;
    }

    public String thread() {
        return thread;
    }

    public long durationMs() {
        return durationMs;
    }

    public String exception() {
        return exception;
    }

    /** 入参（参数名 -> 值文本），不可变。 */
    public Map<String, String> args() {
        return args;
    }

    /** 返回值文本；void 方法返回 null（用 {@link #hasReturn()} 区分）。 */
    public String ret() {
        return ret;
    }

    /** 是否为有返回值的方法（false 表示 void）。 */
    public boolean hasReturn() {
        return hasReturn;
    }

    /**
     * 单行短摘要，用于方法签名行尾的 inlay。
     *
     * <p>形如 {@code ▶ 12ms · 3 参数 · 返回[2]}。
     * 刻意不带参数值 —— 值放在每个参数后面（{@link #shortArg}），
     * 行尾只回答"这次调用花了多久、有没有返回"。
     *
     * @return 一段不超过 40 字的摘要
     */
    public String shortSummary() {
        StringBuilder sb = new StringBuilder(32);
        sb.append(durationMs).append("ms");
        if (!args.isEmpty()) {
            sb.append(" · ").append(args.size()).append(" 参数");
        }
        if (hasReturn) {
            sb.append(" · 有返回");
        }
        if (exception != null && !exception.isEmpty()) {
            sb.append(" · ⚠ 异常");
        }
        return sb.toString();
    }

    /**
     * 单个参数的短值文本，用于参数后面的 inlay。
     *
     * <p>为什么必须截断：inlay 会<b>占据真实的行内空间</b>，
     * 一个 500 字符的 JSON 会把整行代码推到屏幕外，
     * 反而看不到本来要看的方法签名。
     * 完整值在详情面板里，这里只给"能认出是什么"的长度。
     *
     * @param name 参数名
     * @return 值文本；参数不存在返回 null
     */
    public String shortArg(String name) {
        String v = args.get(name);
        if (v == null) {
            return null;
        }
        return truncate(v, 32);
    }

    /** 返回值短文本，用于行尾或 tooltip。 */
    public String shortRet() {
        if (!hasReturn) {
            return null;
        }
        return ret == null ? "null" : truncate(ret, 48);
    }

    /**
     * 截断到指定长度，超出时保留前段并标注总量。
     *
     * @param s   原文
     * @param max 最大字符数
     * @return 截断结果
     */
    private static String truncate(String s, int max) {
        String oneLine = s.replace('\n', ' ').replace('\r', ' ');
        if (oneLine.length() <= max) {
            return oneLine;
        }
        return oneLine.substring(0, max) + "…(" + oneLine.length() + "字)";
    }

    /** 完整文本（供 tooltip / 详情面板），换行符保留。 */
    public String fullArgsText() {
        if (args.isEmpty()) {
            return "（无参数）";
        }
        StringBuilder sb = new StringBuilder(256);
        for (Map.Entry<String, String> e : args.entrySet()) {
            sb.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    /** 完整返回值文本（供 tooltip / 详情面板）。 */
    public String fullRetText() {
        if (!hasReturn) {
            return "（void，无返回值）";
        }
        return ret == null ? "null" : ret;
    }

    /** 构造器。 */
    public static final class Builder {
        private String spanId;
        private long ts;
        private String thread;
        private long durationMs;
        private String exception;
        private Map<String, String> args = new LinkedHashMap<>();
        private String ret;
        private boolean hasReturn;

        public Builder spanId(String v) {
            this.spanId = v;
            return this;
        }

        public Builder ts(long v) {
            this.ts = v;
            return this;
        }

        public Builder thread(String v) {
            this.thread = v;
            return this;
        }

        public Builder durationMs(long v) {
            this.durationMs = v;
            return this;
        }

        public Builder exception(String v) {
            this.exception = v;
            return this;
        }

        public Builder putArg(String name, String renderedValue) {
            this.args.put(name, renderedValue);
            return this;
        }

        public Builder ret(String v, boolean hasReturn) {
            this.ret = v;
            this.hasReturn = hasReturn;
            return this;
        }

        public CcmCallRecord build() {
            return new CcmCallRecord(this);
        }
    }
}
