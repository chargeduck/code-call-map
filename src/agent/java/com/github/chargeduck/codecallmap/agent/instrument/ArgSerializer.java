package com.github.chargeduck.codecallmap.agent.instrument;

import com.github.chargeduck.codecallmap.agent.SensitiveFieldMasker;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.temporal.TemporalAccessor;
import java.util.Collection;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 方法入参的轻量 JSON 序列化器（零依赖，agent 侧不引入 Jackson/Gson）。
 *
 * <h2>为什么自己写</h2>
 * agent 被注入到目标应用进程，任何额外依赖都会：
 * <ul>
 *   <li>增大 agent jar（体积会直接拖慢应用启动）；</li>
 *   <li>可能与目标应用自身的 Jackson/Gson 版本冲突（{@code NoSuchMethodError}）；</li>
 *   <li>引入类加载顺序问题。</li>
 * </ul>
 *
 * <h2>入参不做任何长度裁剪（2026-10-20 起）</h2>
 * 早先为了控制 JSONL 体积，设了 4 层深度 / 4000 字符 / 20 个元素的上限，
 * 超限就截断。结果是<b>分析时信息是残缺的</b>，而用户要的恰恰是完整信息
 * ——比如一个 1MB 的请求体，被削到几千字节就失去了分析价值。
 *
 * <p>现在改为：<b>入参完整序列化，单独写成 JSON 文件</b>
 * （见 {@code CallMapRecorder} 的 argsFile 处理），JSONL 里只放文件引用。
 * 这样两个目标同时达成 —— 数据完整、主文件轻量、回放时按 spanId 直接取。
 *
 * <p>因此本类<b>不再有 MAX_LENGTH / MAX_ELEMENTS / MAX_STRING 这类裁剪</b>。
 * 唯一保留的是深度与循环引用保护，那是防止 StackOverflowError 的安全阀，
 * 属于"避免把目标应用搞崩"，不是"裁剪信息"。
 *
 * <h2>安全边界（这些不是可选项）</h2>
 * <ol>
 *   <li><b>循环引用</b>：实体对象常有父子互指，无限制递归会 StackOverflowError，
 *       而这是在目标应用的业务线程里抛的；
 *       用 {@link IdentityHashMap} 记录访问过的对象，遇到重复引用写
 *       {@code "<circular>"} 并继续，不中断其余字段。</li>
 *   <li><b>深度上限</b>是一个很高的安全阈值（{@link #MAX_DEPTH}），
 *       正常业务对象远达不到；超深时降级为字符串描述，
 *       仍然是<b>合法 JSON 且字段名保留</b>。</li>
 *   <li><b>敏感字段脱敏</b>：字段名命中密码/证件/手机号等关键词时，
 *       只保留掩码值，复用 {@link SensitiveFieldMasker} 的规则。</li>
 *   <li><b>绝不抛异常</b>：任何反射失败都降级为字符串描述，
 *       序列化器出错不能影响业务代码执行。</li>
 *   <li><b>输出必须是合法 JSON</b>：任何分支都不能写出未闭合的结构
 *       或裸片段（如 {@code ...(5)}），否则一行坏数据会让整个会话打不开。</li>
 * </ol>
 */
final class ArgSerializer {

    /**
     * 递归深度安全阈值。
     *
     * <p>这是<b>防栈溢出</b>的，不是裁剪信息用的，所以取得很高：
     * 正常业务对象（DTO 嵌套 Map/List）很少超过 10 层。
     * 达到该深度时把对象降级成 {@code toString()} 字符串，
     * 字段名与层级结构仍然可读。
     */
    private static final int MAX_DEPTH = 64;

    /**
     * 单个集合 / Map / 数组最多展开的元素个数。
     *
     * <h2>为什么必须有这个上限（用户反馈"入参序列化超过 8MB"的根因）</h2>
     * 之前只靠 {@link #MAX_OUTPUT_CHARS} 做总长熔断，而集合分支的
     * {@code for} 循环是<b>独立</b>的 —— 长度检查发生在每次递归
     * {@code appendValue} 里，但循环本身不会提前退出。
     *
     * <p>实测触发场景：某个方法收到 {@code List<Entity>}（一次性查出几万条），
     * 展开时逐条序列化，直到累计 8MB 才被 {@code _overflow} 兜住。
     * 这期间主线程一直在 append，既拖慢业务，产出的
     * {@code {"_overflow":"..."}} 对排查也毫无价值 ——
     * 用户看到的只是一句"超限了"，不知道是哪个参数。
     *
     * <p>现在改成按<b>元素个数</b>截断：超过 200 个就停下，
     * 追加 {@code "…": "还有 N 项"} 说明剩余数量。
     * 排查调用链时，前 200 条足够判断数据类型和内容特征。
     */
    private static final int MAX_COLLECTION_ITEMS = 200;

    /**
     * 单次入参序列化的字符数硬上限（熔断阈值）。
     *
     * <p><b>这不是"裁剪"，是"止血"。</b>正常业务对象通常只有几 KB。
     *
     * <p>但线上出现过主线程卡死（递归 + 菱形引用），
     * 都是靠事后抓栈才定位的。留一道 O(1) 的保险：
     * 一旦累积超限就立刻停手，返回合法标记，既不拖垮应用，
     * 用户也能看出"这里被截了"。
     *
     * <p>取 256KB：单条调用的入参超过这个体积就属于异常情况
     * （典型是误传了大集合或大字符串），再保留更多也读不动。
     * 早期取 8MB 过宽 —— 那意味着主线程可能先 append 出 800 万字符才熔断，
     * 这段时间的 CPU 和内存开销完全是浪费。
     */
    private static final int MAX_OUTPUT_CHARS = 256 * 1024;

    /**
     * 单个字符串值的最大保留长度。
     *
     * <p>Base64 图片、整篇 HTML、超长 JSON 字符串在入参里并不罕见。
     * 保留前 4096 字符足以判断"这是什么内容"，剩余部分只标长度。
     */
    private static final int MAX_STRING_CHARS = 4096;

    private ArgSerializer() {
    }

    /**
     * 把方法实参序列化成 JSON 对象字符串，如
     * {@code {"orderId":"***","pageNum":1}}。
     *
     * @param paramNames 参数名（来自字节码，可能为空）
     * @param values     实参值
     * @return JSON 字符串；无参数或全部失败时返回 null
     */
    static String serialize(String[] paramNames, Object[] values) {
        if (values == null || values.length == 0) {
            return null;
        }
        try {
            StringBuilder sb = new StringBuilder(256);
            sb.append('{');
            boolean first = true;
            for (int i = 0; i < values.length; i++) {
                String name = (paramNames != null && i < paramNames.length
                        && paramNames[i] != null && !paramNames[i].isEmpty())
                        ? paramNames[i] : ("arg" + i);
                // 所有参数都要写，不因长度中断。
                // 入参完整性是硬要求：分析时缺一个参数就无法判断调用上下文。
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escapeJson(name)).append("\":");
                appendValue(sb, values[i], 0, new IdentityHashMap<>(), name);
            }
            sb.append('}');
            if (sb.length() > MAX_OUTPUT_CHARS) {
                // 兜底熔断：正常路径不该走到这里（seen 去重 + 深度上限已足够），
                // 但一旦出现意料外的超大对象，绝不能让主线程卡在 append 上。
                // 返回合法 JSON 并说明情况，用户能从"已超出上限"看出原因。
                return "{\"_overflow\":\"入参序列化超过 " + MAX_OUTPUT_CHARS
                        + " 字符，已中止\"}";
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void appendValue(StringBuilder sb, Object v, int depth,
                                    Map<Object, Boolean> seen, String fieldName) {
        // 熔断：已超上限就立刻停止继续展开。
        // 这个判断是 O(1) 的，且只在"确实超限"时改变行为 ——
        // 正常业务对象远达不到阈值，不会影响完整性。
        // 没有它的话，一个意料外的巨大对象会让主线程一直卡在 append 里。
        if (sb.length() > MAX_OUTPUT_CHARS) {
            sb.append("\"<aborted>\"");
            return;
        }
        if (v == null) {
            sb.append("null");
            return;
        }
        // 敏感字段：只输出掩码值，绝不落原文
        if (SensitiveFieldMasker.isSensitiveKey(fieldName)) {
            sb.append('"').append(escapeJson(SensitiveFieldMasker.maskValue(safeToString(v)))).append('"');
            return;
        }
        if (v instanceof CharSequence) {
            sb.append('"').append(escapeJson(keepString(v.toString()))).append('"');
            return;
        }
        if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
            return;
        }
        if (v instanceof Character || v instanceof Enum) {
            sb.append('"').append(escapeJson(String.valueOf(v))).append('"');
            return;
        }
        if (v instanceof Date || v instanceof TemporalAccessor) {
            sb.append('"').append(escapeJson(String.valueOf(v))).append('"');
            return;
        }
        // 循环引用 / 重复引用检测：必须在进入容器/对象之前登记。
        //
        // 【关键：登记后不再移除】
        // 早先在 finally 里 seen.remove(v)，语义是"只防当前路径上的真循环"。
        // 但这留下一个致命漏洞：同一个对象被多个字段引用时（菱形引用），
        // 每条路径都会把它完整展开一遍，展开次数随引用层数指数增长。
        //
        // 实测：40 层、每层两个字段指向同一个子 Map，序列化耗时 9.4 秒
        // 且结果因超长被丢弃 —— 而真实框架对象（MyBatis Configuration、
        // Spring BeanDefinition、JDBC 驱动的内部结构）恰恰是这种多引用结构，
        // 层数远不止 40。这就是"启动卡死 60+ 秒"的真正原因。
        //
        // 现在改为：一个对象在整个序列化过程中只展开一次，重复出现时写
        // "<duplicate>"。代价是共享对象只在首次出现处完整可见，
        // 但换来的是可控的复杂度 —— 而且重复内容对排查本来也没有额外价值。
        if (seen.put(v, Boolean.TRUE) != null) {
            sb.append("\"<duplicate>\"");
            return;
        }
        try {
            // 【深度检查必须放在所有容器分支之前】
            // 原先它排在数组/集合/Map 之后，只约束到普通 Bean ——
            // 而深层嵌套恰恰多由 Map/List 构成（如 MyBatis 的 Configuration、
            // Spring 的 BeanDefinition 相互引用），那些分支在检查之前就 return 了，
            // 等于完全没有深度限制。实测 main 线程递归到 87 层、栈上有 175 帧，
            // 直接卡死 60+ 秒。
            if (depth >= MAX_DEPTH) {
                sb.append('"').append(escapeJson(brief(v))).append('"');
                return;
            }
            if (v.getClass().isArray()) {
                appendArray(sb, v, depth, seen);
                return;
            }
            if (v instanceof Collection) {
                appendCollection(sb, (Collection<?>) v, depth, seen);
                return;
            }
            if (v instanceof Map) {
                appendMap(sb, (Map<?, ?>) v, depth, seen);
                return;
            }
            appendBean(sb, v, depth, seen);
        } catch (Throwable ignored) {
            sb.append('"').append(escapeJson(safeToString(v))).append('"');
        }
        // 注意：这里【没有】seen.remove(v)。
        // 见上方注释 —— 移除会让菱形引用被反复展开，复杂度退化为指数级。
    }

    private static void appendArray(StringBuilder sb, Object arr, int depth,
                                    Map<Object, Boolean> seen) {
        int len = Array.getLength(arr);
        // 元素个数上限：见 MAX_COLLECTION_ITEMS 的说明。
        // 一次性查出几万条记录的 List/数组是最常见的超限来源。
        int limit = Math.min(len, MAX_COLLECTION_ITEMS);
        sb.append('[');
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append(',');
            }
            appendValue(sb, Array.get(arr, i), depth + 1, seen, "item");
            // 提前退出：即使元素不多，累计体积也可能已经爆掉（元素本身很大时）
            if (sb.length() > MAX_OUTPUT_CHARS) {
                appendArrayTail(sb, len - i - 1, i > 0 || len > 0);
                return;
            }
        }
        if (len > limit) {
            appendArrayTail(sb, len - limit, limit > 0);
            return;
        }
        sb.append(']');
    }

    private static void appendCollection(StringBuilder sb, Collection<?> c, int depth,
                                         Map<Object, Boolean> seen) {
        sb.append('[');
        int total = c.size();
        int i = 0;
        for (Object o : c) {
            if (i >= MAX_COLLECTION_ITEMS) {
                appendArrayTail(sb, total - i, i > 0);
                return;
            }
            if (i > 0) {
                sb.append(',');
            }
            appendValue(sb, o, depth + 1, seen, "item");
            i++;
            if (sb.length() > MAX_OUTPUT_CHARS) {
                appendArrayTail(sb, Math.max(0, total - i), true);
                return;
            }
        }
        sb.append(']');
    }

    private static void appendMap(StringBuilder sb, Map<?, ?> m, int depth,
                                  Map<Object, Boolean> seen) {
        sb.append('{');
        int total = m.size();
        int i = 0;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (i >= MAX_COLLECTION_ITEMS) {
                appendObjectTail(sb, total - i, i > 0);
                return;
            }
            if (i > 0) {
                sb.append(',');
            }
            String key = safeToString(e.getKey());
            sb.append('"').append(escapeJson(keepString(key))).append("\":");
            appendValue(sb, e.getValue(), depth + 1, seen, key);
            i++;
            if (sb.length() > MAX_OUTPUT_CHARS) {
                appendObjectTail(sb, Math.max(0, total - i), true);
                return;
            }
        }
        sb.append('}');
    }

    /**
     * 数组 / 集合被截断时的收尾：追加一个字符串元素说明剩余数量，再闭合。
     *
     * <h2>为什么数组里放字符串而不是键值对</h2>
     * 数组的合法元素只能是值，写 {@code "_truncated":"还有 N 项"} 会变成
     * 非法的 {@code [1,2,"_truncated":"还有 N 项"]} —— 这正是第一版改动的 bug，
     * {@code assertValidJson} 立刻挂在解析上。
     *
     * <p>所以数组用字符串元素 {@code "…剩下 N 项未展开"}，
     * 对象的 {@code _truncated} 键值对形式只在 Map 里用
     * （见 {@link #appendObjectTail}）。
     *
     * @param hadContent 之前是否已写出过元素 —— 决定要不要补逗号
     */
    private static void appendArrayTail(StringBuilder sb, int remaining, boolean hadContent) {
        if (remaining > 0) {
            if (hadContent) {
                sb.append(',');
            }
            sb.append("\"…剩下 ").append(remaining).append(" 项未展开\"");
        }
        sb.append(']');
    }

    /**
     * 对象被截断时的收尾：追加 {@code "_truncated":"还有 N 项未展开"} 再闭合。
     *
     * <p>对象是键值对集合，塞这个键是合法的。
     */
    private static void appendObjectTail(StringBuilder sb, int remaining, boolean hadContent) {
        if (remaining > 0) {
            if (hadContent) {
                sb.append(',');
            }
            sb.append("\"_truncated\":\"还有 ").append(remaining).append(" 项未展开\"");
        }
        sb.append('}');
    }

    private static void appendBean(StringBuilder sb, Object bean, int depth,
                                   Map<Object, Boolean> seen) {
        Class<?> cls = bean.getClass();
        // JDK 内部类（如 LocalDate 之外的 java.* 实现）不反射字段，
        // 一来字段名无意义，二来可能触发模块系统访问限制
        if (cls.getName().startsWith("java.") || cls.getName().startsWith("javax.")) {
            sb.append('"').append(escapeJson(safeToString(bean))).append('"');
            return;
        }
        sb.append('{');
        boolean first = true;
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                    continue;
                }
                // 【不要在这里直接收尾返回】
                // 早先是 `sb.append(",..."); sb.append('}'); return;` ——
                // 既可能补出多余的逗号造成语法错误，也把后面所有字段整个丢弃，
                // 分析时完全看不到那些字段存在过。
                // 现在改为照常写键名，值由 appendValue 的降级分支输出摘要，
                // 信息量和语法正确性都保住。
                Object val;
                try {
                    f.setAccessible(true);
                    val = f.get(bean);
                } catch (Throwable ignored) {
                    // JDK 17+ 模块限制或安全管理器禁止访问，跳过该字段
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escapeJson(f.getName())).append("\":");
                appendValue(sb, val, depth + 1, seen, f.getName());
            }
        }
        sb.append('}');
    }

    /** 安全转字符串：toString 可能被业务代码重写并抛异常。 */
    private static String safeToString(Object o) {
        if (o == null) {
            return "null";
        }
        try {
            String s = o.toString();
            return s == null ? "null" : keepString(s);
        } catch (Throwable ignored) {
            return o.getClass().getName() + "@<toString failed>";
        }
    }

    /**
     * 为「达到深度上限而未展开的对象」生成<b>简短</b>描述。
     *
     * <h2>为什么不用 toString()</h2>
     * 深度截断时通常是框架对象（MyBatis Configuration、Spring BeanDefinition）,
     * 它们的 toString 往往要遍历整个对象图 —— 既慢又可能再次递归，
     * 在我们正想止损的地方再引发一次卡顿。
     *
     * <p>因此这里只输出类型名与规模，绝不调用 toString()。
     */
    private static String brief(Object v) {
        if (v == null) {
            return "null";
        }
        try {
            String type = v.getClass().getName();
            if (v instanceof Collection) {
                return "<" + type + " size=" + ((Collection<?>) v).size()
                        + " 已达深度上限，未展开>";
            }
            if (v instanceof Map) {
                return "<" + type + " size=" + ((Map<?, ?>) v).size()
                        + " 已达深度上限，未展开>";
            }
            if (v.getClass().isArray()) {
                return "<" + type + " length=" + Array.getLength(v)
                        + " 已达深度上限，未展开>";
            }
            return "<" + type + " 已达深度上限，未展开>";
        } catch (Throwable ignored) {
            return "<已达深度上限，未展开>";
        }
    }

    /**
     * 字符串值原样返回，不截断。
     *
     * <p>保留这个方法名是为了让调用点读起来仍然是"处理字符串"，
     * 但它已经不做任何裁剪 —— 入参要完整保存（见类注释）。
     * 字符串的正确性由 {@link #escapeJson(String)} 保证。
     */
    /**
     * 保留字符串值，超长时截断并标注原长。
     *
     * <p>Base64 图片、整篇 HTML、超大 JSON 字符串在入参里并不罕见，
     * 以前原样输出，一条就能把体积顶到熔断阈值。
     * 现在保留前 {@link #MAX_STRING_CHARS} 字符，后面写明总长度 ——
     * 排查时"这是什么内容"看开头就够了，"这条有多长"由标注给出。
     */
    private static String keepString(String s) {
        if (s == null) {
            return null;
        }
        if (s.length() <= MAX_STRING_CHARS) {
            return s;
        }
        return s.substring(0, MAX_STRING_CHARS)
                + "…(共 " + s.length() + " 字符)";
    }

    // 注意：这里刻意不提供「对拼接好的 JSON 做 substring」的截断工具。
    // 那种截断一定会切坏括号/引号，产出的行无法被 JSON.parse 解析，
    // 一条坏行就足以让整个会话文件打不开。超长请在拼装阶段处理
    // （见 serialize() 末尾的 _truncated 占位）。

    /** JSON 字符串转义。 */
    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
