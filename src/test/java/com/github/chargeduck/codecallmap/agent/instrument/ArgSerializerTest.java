package com.github.chargeduck.codecallmap.agent.instrument;

import com.github.chargeduck.codecallmap.agent.SensitiveFieldMasker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入参 JSON 序列化测试。
 *
 * <p>这块逻辑直接运行在<b>目标应用的业务线程</b>里，
 * 任何异常都会打断用户代码，所以测试重点不只是"结果对不对"，
 * 更要覆盖各种"必须安全降级"的边界。
 */
class ArgSerializerTest {

    // ---------- 基本类型 ----------

    @Test
    void serializesPrimitivesWithParamNames() {
        String json = ArgSerializer.serialize(
                new String[]{"orderId", "pageNum", "active"},
                new Object[]{123L, 2, true});
        assertEquals("{\"orderId\":123,\"pageNum\":2,\"active\":true}", json);
    }

    @Test
    void missingParamNamesFallBackToIndex() {
        String json = ArgSerializer.serialize(null, new Object[]{"abc"});
        assertEquals("{\"arg0\":\"abc\"}", json);
    }

    @Test
    void nullValueIsWrittenAsNull() {
        String json = ArgSerializer.serialize(new String[]{"x"}, new Object[]{null});
        assertEquals("{\"x\":null}", json);
    }

    // ---------- 脱敏（最关键） ----------

    @Test
    void sensitiveFieldsAreMasked() {
        String json = ArgSerializer.serialize(
                new String[]{"password", "phone", "username"},
                new Object[]{"mySecret123", "13800138000", "zhangsan"});
        assertFalse(json.contains("mySecret123"), "密码原文绝不能出现在输出里: " + json);
        assertFalse(json.contains("13800138000"), "手机号原文绝不能出现: " + json);
        assertTrue(json.contains("zhangsan"), "非敏感字段应保留原值: " + json);
    }

    @Test
    void sensitiveBeanFieldIsMaskedByName() {
        User user = new User();
        user.name = "张三";
        user.password = "p@ssw0rd";
        String json = ArgSerializer.serialize(new String[]{"user"}, new Object[]{user});
        assertFalse(json.contains("p@ssw0rd"), "嵌套对象里的敏感字段也要脱敏: " + json);
        assertTrue(json.contains("张三"));
    }

    // ---------- 容器 ----------

    @Test
    void serializesListAndMap() {
        List<String> list = Arrays.asList("a", "b");
        Map<String, Object> map = new HashMap<>();
        map.put("k", 1);
        String json = ArgSerializer.serialize(new String[]{"l", "m"}, new Object[]{list, map});
        assertTrue(json.contains("\"l\":[\"a\",\"b\"]"), json);
        assertTrue(json.contains("\"m\":{\"k\":1}"), json);
    }

    /**
     * 大集合按<b>元素个数</b>截断，并标注剩余数量（不再返回 {@code _overflow}）。
     *
     * <h2>策略变更原因（2026-10-21）</h2>
     * 早先要求"完整保留、一个不少"，靠 8MB 总长熔断兜底。
     * 但实测中一个方法收到几万条记录时，主线程会一路 append 到 8MB 才停 ——
     * 既拖慢业务，最终产出还只是一句 {@code _overflow}，对排查毫无价值。
     *
     * <p>现在改为：超过 {@code MAX_COLLECTION_ITEMS}(200) 个元素即停止展开，
     * 末尾追加字符串元素 {@code "…剩下 N 项未展开"}（数组里不能放键值对）。
     * 排查时前 200 条足够判断数据类型与内容特征，
     * 而"一共有多少条"由标注给出，信息并没有丢。
     */
    @Test
    void largeCollectionIsTruncatedWithCount() {
        List<Integer> big = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            big.add(i);
        }
        String json = ArgSerializer.serialize(new String[]{"ids"}, new Object[]{big});
        assertNotNull(json);
        assertValidJson("大集合", json);
        // 从第一个元素开始展开
        assertTrue(json.contains("\"ids\":[0,1,"), "应从第一个元素开始: " + json.substring(0, 40));
        // 200 个之后停下，并说明剩余数量（500 - 200 = 300）
        assertTrue(json.contains("剩下 300 项未展开"), "数组应标注剩余数量: " + json.substring(json.length() - 60));
        
        // 不应再出现 _overflow 熔断标记
        assertTrue(!json.contains("_overflow"), "不应触发整体熔断: " + json);
    }

    /** 超大字符串也必须原样保留（不做 MAX_STRING 裁剪）。 */
    @Test
    void longStringIsPreservedCompletely() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            body.append('x');
        }
        body.append("END_MARKER");
        String json = ArgSerializer.serialize(new String[]{"body"}, new Object[]{body.toString()});
        assertNotNull(json);
        assertValidJson("超长字符串", json);
        assertTrue(json.contains("END_MARKER"), "超长字符串不能被截断");
    }

    /**
     * 2 万条记录的入参：必须在<b>可接受的体积内</b>返回，且仍是合法 JSON。
     *
     * <h2>这个用例守护的是"不要把主线程拖垮"</h2>
     * 旧实现会把这 2 万条全部展开，产出 1MB+ 字符串 ——
     * 这既是用户遇到 {@code _overflow} 的场景，也是卡顿的来源。
     *
     * <p>新策略下应截断在 200 条，产出体积远小于 1MB，
     * 且末尾标注剩余 19800 项。
     */
    @Test
    void hugeCollectionIsTruncatedWithinReasonableSize() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 20000; i++) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", i);
            item.put("name", "商品名称-" + i + "-" + "abcdefghij".repeat(3));
            item.put("price", i * 1.5);
            items.add(item);
        }
        String json = ArgSerializer.serialize(new String[]{"items"}, new Object[]{items});
        assertNotNull(json);
        assertValidJson("超大集合", json);
        // 关键：体积必须可控，不能因为用户传了大集合就产出 MB 级字符串
        assertTrue(json.length() < 100_000,
                "截断后体积应远小于 1MB，实际 " + json.length() + " 字符");
        assertTrue(json.contains("剩下 19800 项未展开"), "应标注剩余数量: " + json.substring(json.length() - 60));
        // 20000 - 200 = 19800
        
        assertTrue(!json.contains("_overflow"), "不应触发整体熔断: " + json);
    }

    // ---------- 合法性：任何输出都必须能被 JSON.parse 解析 ----------

    /**
     * 断言输出是合法 JSON（极简校验，不引第三方库）。
     *
     * <p>用递归下降解析一遍，能完整吃掉整个字符串才算合法。
     */
    private static void assertValidJson(String label, String json) {
        try {
            Object parsed = new TestJsonParser(json).parse();
            assertNotNull(parsed, label + " 解析结果为空");
        } catch (RuntimeException e) {
            throw new AssertionError(label + " 不是合法 JSON: " + e.getMessage()
                    + "\n原文: " + json, e);
        }
    }

    /** 极简 JSON 校验器：只判断语法，不构建对象。 */
    private static final class TestJsonParser {
        private final String s;
        private int i;

        TestJsonParser(String s) {
            this.s = s;
        }

        Object parse() {
            skipWs();
            Object v = value();
            skipWs();
            if (i != s.length()) {
                throw new RuntimeException("位置 " + i + " 后有多余内容");
            }
            return v;
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private Object value() {
            if (i >= s.length()) {
                throw new RuntimeException("意外的结尾");
            }
            char c = s.charAt(i);
            if (c == '{') {
                return object();
            }
            if (c == '[') {
                return array();
            }
            if (c == '"') {
                return string();
            }
            if (c == 't' || c == 'f' || c == 'n') {
                for (String lit : new String[]{"true", "false", "null"}) {
                    if (s.startsWith(lit, i)) {
                        i += lit.length();
                        return lit;
                    }
                }
                throw new RuntimeException("非法字面量 @" + i);
            }
            int start = i;
            if (c == '-') {
                i++;
            }
            while (i < s.length() && (Character.isDigit(s.charAt(i))
                    || s.charAt(i) == '.' || s.charAt(i) == 'e'
                    || s.charAt(i) == 'E' || s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            if (start == i) {
                throw new RuntimeException("非法字符 '" + c + "' @" + i);
            }
            return s.substring(start, i);
        }

        private Object object() {
            i++;   // {
            skipWs();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return "{}";
            }
            while (true) {
                skipWs();
                if (i >= s.length() || s.charAt(i) != '"') {
                    throw new RuntimeException("对象键必须是字符串 @" + i);
                }
                string();
                skipWs();
                if (i >= s.length() || s.charAt(i) != ':') {
                    throw new RuntimeException("键后缺冒号 @" + i);
                }
                i++;
                skipWs();
                value();
                skipWs();
                if (i >= s.length()) {
                    throw new RuntimeException("对象未闭合");
                }
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == '}') {
                    i++;
                    return "{}";
                }
                throw new RuntimeException("对象内非法字符 '" + c + "' @" + i);
            }
        }

        private Object array() {
            i++;   // [
            skipWs();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return "[]";
            }
            while (true) {
                skipWs();
                value();
                skipWs();
                if (i >= s.length()) {
                    throw new RuntimeException("数组未闭合");
                }
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == ']') {
                    i++;
                    return "[]";
                }
                throw new RuntimeException("数组内非法字符 '" + c + "' @" + i);
            }
        }

        private String string() {
            i++;   // "
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (i >= s.length()) {
                        throw new RuntimeException("转义未完成");
                    }
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': case '\\': case '/': case 'b':
                        case 'f': case 'n': case 'r': case 't':
                            sb.append(e);
                            break;
                        case 'u':
                            if (i + 4 > s.length()) {
                                throw new RuntimeException("\\u 转义不完整");
                            }
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default:
                            throw new RuntimeException("非法转义 \\" + e);
                    }
                    continue;
                }
                sb.append(c);
            }
            throw new RuntimeException("字符串未闭合");
        }
    }

    /**
     * 回归：入参包含大型嵌套对象时，输出必须仍是合法 JSON。
     *
     * <p>线上实际故障：MyBatis 的 {@code Invocation} 参数会牵扯出整棵
     * Configuration，里面 ResultMap 的 toString 很长。旧实现用
     * {@code substring(0, 500)} 硬截，把花括号和引号切在半截，
     * 导致整行 JSONL 不可解析 —— 前端报
     * {@code Expected ',' or '}' after property value in JSON at position 2029}，
     * 整个会话都打不开。
     */
    @Test
    void deeplyNestedLargeObjectStaysValidJson() {
        Map<String, Object> resultMaps = new HashMap<>();
        for (int i = 0; i < 50; i++) {
            resultMaps.put("com.github.Mapper.ResultMap" + i,
                    "org.apache.ibatis.mapping.ResultMap@" + Integer.toHexString(i) + " ");
        }
        Map<String, Object> configuration = new HashMap<>();
        configuration.put("resultMaps", resultMaps);
        configuration.put("caches", new HashMap<>());
        configuration.put("mybatisMapperRegistry", "com.baomidou.mybatisplus.core.MybatisMapperRegistry@66c91224");
        Map<String, Object> target = new HashMap<>();
        target.put("configuration", configuration);
        Map<String, Object> invocation = new HashMap<>();
        invocation.put("target", target);

        String json = ArgSerializer.serialize(new String[]{"invocation"}, new Object[]{invocation});

        assertNotNull(json);
        assertValidJson("MyBatis Invocation", json);
    }

    /**
     * 回归（线上卡死故障）：Map/List 构成的深层嵌套必须被深度上限拦住。
     *
     * <h2>这次故障的完整链条</h2>
     * 用户反馈"Debug with CCM 启动卡死 60+ 秒，普通 Debug 正常"。
     * 看门狗抓到的 main 线程栈：
     * <pre>
     *   java.util.regex.Matcher.replaceAll
     *   SensitiveFieldMasker.isSensitiveKey
     *   ArgSerializer.appendValue
     *   ArgSerializer.appendBean
     *   ArgSerializer.appendValue   ← 这两个交替出现几十次
     *   ... 还有 175 帧
     * </pre>
     * 两个原因叠加：
     * <ol>
     *   <li><b>深度上限被绕过</b>：检查写在数组/集合/Map 分支之后，
     *       而深层嵌套恰恰多由 Map/List 构成，那些分支在检查前就 return 了。
     *       实测递归到 87 层。</li>
     *   <li><b>isSensitiveKey 每次编译正则</b>：内部用
     *       {@code key.replaceAll("[_-]", "")}，还对每个关键字各调一次，
     *       一次判断 30+ 次编译。乘上 87 层 × 每层多字段，直接卡死。</li>
     * </ol>
     *
     * <p>触发场景：业务方法签名里出现 MyBatis 的 {@code Invocation}，
     * 其 target 牵扯整棵 Configuration，层层 Map 互指。
     */
    @Test
    void deepNestedMapsAreCappedByDepthLimit() {
        // 构造 200 层 Map 套 Map —— 旧实现会一路递归下去
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> cur = root;
        for (int i = 0; i < 200; i++) {
            Map<String, Object> next = new LinkedHashMap<>();
            cur.put("level" + i, next);
            cur = next;
        }
        cur.put("leaf", "END");

        long t0 = System.nanoTime();
        String json = ArgSerializer.serialize(new String[]{"deep"}, new Object[]{root});
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        assertNotNull(json);
        assertValidJson("200 层嵌套", json);
        // 深度上限必须真正生效：必须出现"已达深度上限"的标注
        assertTrue(json.contains("已达深度上限"),
                "超过 MAX_DEPTH 后应停止展开并标注，实际: "
                        + json.substring(0, Math.min(300, json.length())));
        // 性能护栏：旧实现会跑几十秒，这里给足够宽松的上限（3 秒）
        assertTrue(ms < 3000, "深层嵌套不应导致长时间阻塞，实际 " + ms + "ms");
    }

    /** 深层 List 嵌套同样要被拦住（旧实现里 Collection 分支也绕过了深度检查）。 */
    @Test
    void deepNestedListsAreCappedByDepthLimit() {
        List<Object> root = new ArrayList<>();
        List<Object> cur = root;
        for (int i = 0; i < 200; i++) {
            List<Object> next = new ArrayList<>();
            cur.add(next);
            cur = next;
        }
        cur.add("END");

        long t0 = System.nanoTime();
        String json = ArgSerializer.serialize(new String[]{"deep"}, new Object[]{root});
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        assertNotNull(json);
        assertValidJson("200 层 List", json);
        assertTrue(json.contains("已达深度上限"), "List 嵌套也必须受深度限制");
        assertTrue(ms < 3000, "不应长时间阻塞，实际 " + ms + "ms");
    }

    /**
     * 回归：敏感字段判断必须是 O(词表) 而非"每次编译正则"。
     *
     * <p>线上 87 层递归 × 每层多字段的调用量下，正则重复编译是主要耗时来源。
     * 这里用大量调用做粗粒度护栏：若又退回 replaceAll(String,String)，
     * 耗时会明显上升。
     */
    @Test
    void sensitiveKeyCheckIsCheapEnoughForHotPath() {
        long t0 = System.nanoTime();
        int n = 200_000;
        for (int i = 0; i < n; i++) {
            SensitiveFieldMasker.isSensitiveKey("someFieldName" + (i & 0x3F));
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        // 20 万次判断；正常实现只需几十毫秒，旧的"每次编译正则"要数秒
        assertTrue(ms < 3000, "isSensitiveKey 是热路径，20 万次应远快于 3 秒，实际 " + ms + "ms");
    }

    /**
     * 回归：多个参数时，不能因为第一个参数很大就把后面的参数整个丢掉。
     *
     * <p>旧实现在循环里判断长度后 {@code break}，分析时看到的信息是残缺的，
     * 而且完全不知道少了什么。
     */
    @Test
    void allParametersAreKeptEvenWhenOneIsHuge() {
        List<Integer> huge = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            huge.add(i);
        }
        String json = ArgSerializer.serialize(
                new String[]{"bigList", "orderId", "pageNum"},
                new Object[]{huge, "ORDER-123", 7});

        assertNotNull(json);
        assertValidJson("多参数含超大值", json);
        assertTrue(json.contains("orderId"), "后面的参数不能丢: " + json);
        assertTrue(json.contains("ORDER-123"), "后面的参数值不能丢: " + json);
        assertTrue(json.contains("pageNum"), "后面的参数不能丢: " + json);
        // 大集合本身按元素个数截断，但必须标注剩余数量
        assertTrue(json.contains("剩下 4800 项未展开"), "大集合应标注剩余数量: " + json.substring(json.length() - 80));
        
    }

    /**
     * 大 Map 必须完整保留且是合法 JSON。
     *
     * <p>旧实现超限时写 {@code {"a":1,...(5)}} —— 那个 {@code ...(5)}
     * 不是键值对，整体语法错误，前端 parse 直接崩。
     * 现在不裁剪，全部键值对都保留。
     */
    @Test
    void largeMapIsPreservedCompletely() {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            m.put("k" + i, i);
        }
        String json = ArgSerializer.serialize(new String[]{"m"}, new Object[]{m});
        assertNotNull(json);
        assertValidJson("大 Map", json);
        assertTrue(json.contains("\"k99\":99"), "所有键值对都要保留: " + json);
    }

    // ---------- 安全性：不能影响业务 ----------

    @Test
    void circularReferenceDoesNotStackOverflow() {
        Node a = new Node();
        Node b = new Node();
        a.next = b;
        b.next = a;   // 循环引用
        String json = ArgSerializer.serialize(new String[]{"node"}, new Object[]{a});
        assertNotNull(json);
        assertTrue(json.contains("<duplicate>"), "循环引用应被标记而不是无限递归: " + json);
    }

    /**
     * 回归（线上二次卡死）：<b>菱形引用</b>不能导致指数级展开。
     *
     * <h2>为什么上一版修完还卡</h2>
     * 上一版只把"深度检查"提前了，但 seen 集合仍在 finally 里 remove(v)，
     * 语义是"只防当前路径上的真循环"。
     *
     * <p>结果：同一个对象被多个字段引用时（菱形引用），每条路径都会把它
     * 完整展开一遍。展开次数随引用层数<b>指数增长</b> ——
     * 实测 40 层、每层两个字段指向同一子对象，序列化耗时 9.4 秒且结果被丢弃。
     *
     * <p>真实框架对象恰恰是这种结构：MyBatis 的 Configuration 里
     * resultMaps / caches / mappedStatements 大量互相引用；
     * Spring BeanDefinition、JDBC 驱动内部结构同理。
     *
     * <p>修复：seen 登记后不再移除 —— 一个对象在整个序列化过程中只展开一次。
     */
    @Test
    void diamondReferenceDoesNotExplode() {
        // 40 层，每层两个字段指向同一个子 Map —— 朴素展开是 2^40 次
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> cur = root;
        for (int i = 0; i < 40; i++) {
            Map<String, Object> shared = new LinkedHashMap<>();
            shared.put("v", "x");
            cur.put("p", shared);
            cur.put("q", shared);   // 同一对象被两个字段引用
            cur = shared;
        }

        long t0 = System.nanoTime();
        String json = ArgSerializer.serialize(new String[]{"root"}, new Object[]{root});
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        assertNotNull(json, "不能因超时/超长而整体失败");
        assertValidJson("菱形引用", json);
        // 旧实现 40 层要 9.4 秒；修复后应是毫秒级
        assertTrue(ms < 500, "菱形引用必须线性收敛，实际 " + ms + "ms");
        assertTrue(json.contains("<duplicate>"), "重复引用应被标记");
    }

    @Test
    void throwingToStringDoesNotPropagate() {
        String json = ArgSerializer.serialize(new String[]{"bad"}, new Object[]{new BadToString()});
        assertNotNull(json, "toString 抛异常时也应产出结果，绝不能把异常抛给业务代码");
    }

    @Test
    void noArgsReturnsNull() {
        assertNull(ArgSerializer.serialize(new String[]{"x"}, new Object[0]));
        assertNull(ArgSerializer.serialize(null, null));
    }

    // ---------- 样例 ----------

    /** 简单实体，用于测试字段级脱敏。 */
    public static class User {
        public String name;
        public String password;
    }

    /** 双向引用，用于测试循环检测。 */
    public static class Node {
        public Node next;
    }

    /** toString 会抛异常的类。 */
    public static class BadToString {
        @Override
        public String toString() {
            throw new IllegalStateException("boom");
        }
    }
}
