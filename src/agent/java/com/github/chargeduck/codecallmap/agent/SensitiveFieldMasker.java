package com.github.chargeduck.codecallmap.agent;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感字段脱敏工具。
 *
 * <p>对 {@code args}（入参）中 password / token / secret / authorization 等敏感字段做截断或掩码：
 * <ul>
 *   <li>结构化入口：{@link #maskMap(Map)} 返回脱敏后的 Map，可配合 {@link Span#argsToJson(Map)} 使用；</li>
 *   <li>字符串入口：{@link #maskJsonString(String)} 直接对预序列化的 JSON 对象字符串做脱敏。</li>
 * </ul>
 *
 * <p>纯 Java 实现，不依赖 IntelliJ Platform API。
 */
public final class SensitiveFieldMasker {

    /**
     * 命中即视为敏感字段的关键字（大小写不敏感、支持驼峰/下划线变体）。
     *
     * <p>用 {@code Collections.unmodifiableSet} 而非 {@code Set.of}（Java 9+）：
     * agent 需兼容 JDK 8 目标 JVM。
     */
    private static final Set<String> SENSITIVE_KEYWORDS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "password", "passwd", "pwd",
                    "token", "access_token", "refresh_token",
                    "secret", "client_secret",
                    "authorization", "auth",
                    "apikey", "api_key", "privatekey", "private_key",
                    "cookie", "sessionid", "session_id",
                    // ---- 个人信息（合规必需）----
                    // 此前词表只覆盖「凭据类」信息，漏了个人信息。
                    // 而调用链采集会把方法入参整体序列化落盘，
                    // 手机号、身份证号一旦进了 JSONL 就等于明文泄露。
                    "phone", "mobile", "telephone", "contact",
                    "idcard", "id_card", "identity", "idno", "id_no",
                    "bankcard", "bank_card", "cardno", "card_no",
                    "email", "address", "realname", "real_name"
            )));

    private static final Pattern KEY_PATTERN = Pattern.compile(
            "\\b(?:" + String.join("|", SENSITIVE_KEYWORDS) + ")\\b",
            Pattern.CASE_INSENSITIVE);

    /** 匹配 JSON 对象中的 {@code "敏感key": "value"} 字符串值（忽略值中含转义引号的情况，覆盖常规场景）。 */
    private static final Pattern JSON_PAIR_PATTERN = Pattern.compile(
            "(\"(?:" + String.join("|", SENSITIVE_KEYWORDS) + ")\"\\s*:\\s*)\"([^\"]*)\"",
            Pattern.CASE_INSENSITIVE);

    /**
     * 归一化用的分隔符正则，<b>预编译</b>。
     *
     * <h2>为什么必须预编译</h2>
     * {@link String#replaceAll(String, String)} 每次调用都会重新编译正则。
     * 原先 {@link #isSensitiveKey(String)} 里直接写 {@code key.replaceAll("[_-]", "")}，
     * 并且还在关键字循环里对每个关键字各调一次 —— 一次判断就是 30+ 次正则编译。
     *
     * <p>而 {@code isSensitiveKey} 是<b>入参序列化的热路径</b>：
     * 每处理一个字段都要调一次。实测某 Spring Boot 项目启动时，
     * 序列化一个深层对象导致 {@code main} 线程卡死 60+ 秒，
     * 线程栈显示几乎全耗在
     * {@code String.replaceAll -> Matcher.replaceAll -> Pattern.compile}。
     *
     * <p>改成预编译后，同样的判断只剩字符串比较，开销可忽略。
     */
    private static final Pattern SEPARATOR_PATTERN = Pattern.compile("[_-]");

    /**
     * 已归一化的关键字缓存（去掉 {@code _} / {@code -} 并转小写）。
     *
     * <p>启动时算一次，避免每次判断都重新归一化整个词表。
     */
    private static final String[] NORMALIZED_KEYWORDS;

    static {
        String[] raw = SENSITIVE_KEYWORDS.toArray(new String[0]);
        NORMALIZED_KEYWORDS = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            NORMALIZED_KEYWORDS[i] = SEPARATOR_PATTERN.matcher(raw[i])
                    .replaceAll("").toLowerCase();
        }
    }

    private SensitiveFieldMasker() {
    }

    /** 判断字段名是否敏感。 */
    public static boolean isSensitiveKey(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        // 热路径：不做任何正则编译。分隔符用预编译 Pattern，
        // 关键字用启动时算好的归一化数组，这里只做 contains 比较。
        String normalized = SEPARATOR_PATTERN.matcher(key).replaceAll("").toLowerCase();
        for (String normKw : NORMALIZED_KEYWORDS) {
            if (normalized.contains(normKw)) {
                return true;
            }
        }
        return KEY_PATTERN.matcher(normalized).find();
    }

    /**
     * 掩码字符串值：长度不超过 4 全量掩码；否则保留前 2 位与后 2 位，中间以 {@code ****} 代替。
     */
    public static String maskValue(String value) {
        if (value == null) {
            return null;
        }
        if (value.isEmpty()) {
            return "";
        }
        if (value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    /**
     * 对 SQL 语句做脱敏：保留结构，抹掉具体取值。
     *
     * <h2>为什么需要单独处理</h2>
     * SQL 的敏感信息藏在<b>字面量</b>里，而不是字段名：
     * {@code WHERE id_card = '110101199001011234'} 里表名和列名不敏感，
     * 但那个身份证号极其敏感。所以不能复用 {@link #maskValue}（按 key 判断）。
     *
     * <h2>处理规则</h2>
     * <ul>
     *   <li>单引号字符串 → {@code '***'}（保留引号，便于看出这里原本有个值）</li>
     *   <li>长数字字面量（≥6 位，可能是手机号 / 证件号 / 订单号）→ {@code ?}</li>
     *   <li>其余结构（表名、列名、关键字）原样保留</li>
     * </ul>
     *
     * <p>预处理过的 SQL（{@code executeQuery()} 无参重载）通常已经是 {@code ?} 占位，
     * 不含字面量，这里会原样返回。
     */
    public static String maskSql(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        String masked = SQL_STRING_LITERAL.matcher(sql).replaceAll("'***'");
        masked = SQL_LONG_NUMBER.matcher(masked).replaceAll("?");
        return masked;
    }

    /** 匹配 SQL 单引号字面量，支持 SQL 里的 '' 转义。 */
    private static final java.util.regex.Pattern SQL_STRING_LITERAL =
            java.util.regex.Pattern.compile("'(?:[^']|'')*'");

    /** 匹配长度 >= 6 的独立数字（前后不是数字/字母/下划线），避免误伤列名里的数字。 */
    private static final java.util.regex.Pattern SQL_LONG_NUMBER =
            java.util.regex.Pattern.compile("(?<![\\w.])\\d{6,}(?![\\w.])");

    /** 对结构化 Map 做脱敏（保留字段顺序，嵌套 Map 递归处理；key 统一转为 String）。 */
    public static Map<String, Object> maskMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        for (Map.Entry<?, ?> e : source.entrySet()) {
            Object key = e.getKey();
            if (key == null) {
                continue;
            }
            String keyStr = key.toString();
            Object value = e.getValue();
            if (isSensitiveKey(keyStr)) {
                // Java 8 兼容：不使用 instanceof 模式匹配（Java 16+）
                Object masked = value instanceof String ? maskValue((String) value) : value;
                result.put(keyStr, masked);
            } else if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<?, ?> nested = (Map<?, ?>) value;
                result.put(keyStr, maskMap(nested));
            } else {
                result.put(keyStr, value);
            }
        }
        return result;
    }

    /**
     * 对预序列化的 JSON 对象字符串做脱敏（仅处理字符串值；非字符串敏感值原样保留）。
     *
     * <p>限制：适用于标准 JSON 对象文本，字符串值内不含未转义双引号；满足入参脱敏的常规场景。
     */
    public static String maskJsonString(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        Matcher m = JSON_PAIR_PATTERN.matcher(json);
        if (!m.find()) {
            return json;
        }
        // 用 StringBuffer 而非 StringBuilder：Matcher.appendReplacement 接受
        // StringBuilder 的重载是 Java 9+ 才有的，Java 8 只有 StringBuffer 版本
        StringBuffer sb = new StringBuffer(json.length() + 32);
        do {
            String keyJson = m.group(1);
            String value = m.group(2);
            m.appendReplacement(sb, Matcher.quoteReplacement(keyJson + "\"" + maskValue(value) + "\""));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }
}
