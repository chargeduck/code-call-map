package com.github.chargeduck.codecallmap.agent.instrument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 「按类型匹配」的附加探针规则表。
 *
 * <h2>解决什么问题</h2>
 * 白名单模式下只采集用户自己的业务包，HTTP 入口、JDBC 等第三方调用完全不可见。
 * 表现为两个致命现象：
 * <ul>
 *   <li><b>调用链立不起来</b> —— 一次 HTTP 请求在 trace 里根本没有根节点，
 *       用户看到的是"应用启动"那条链，而不是"这个接口"的链；</li>
 *   <li><b>耗时对不上</b> —— 没插桩的地方耗时全部算进调用方自身耗时，
 *       实测出现过「根节点 25952ms、子节点合计仅 513ms」这种 25 秒凭空消失。</li>
 * </ul>
 *
 * <h2>为什么按类型而不是往白名单加包（关键教训）</h2>
 * 直接往白名单加 {@code org.springframework} / {@code org.apache.catalina}
 * 会整包插桩，必然重演 0.0.1.17~0.0.1.21 的 LinkageError 连环崩
 * （类加载器被改、接口被改）。按类型匹配只命中
 * <b>该类型的实现/子类的指定方法</b>，命中面窄得多。
 *
 * <h2>规则结构</h2>
 * <pre>
 *   type          要匹配的类型（接口、父类、或具体类名）
 *   methodRegex   方法名正则 —— 这是"按方法名筛选"的核心，
 *                 用 {@code execute|execute.*Query} 一条覆盖多个重载
 *   isEndpoint    是否请求入口。true 表示：栈为空时这里新建 trace，
 *                 即"一次接口调用 = 一条调用链"
 *   message       标识模板，从实参取值写进 span 的 sql/path，
 *                 例如 {@code ${[0].getMethod()} ${[0].getRequestURI()}}
 * </pre>
 *
 * <h2>关于安全</h2>
 * 规则只作用于已成功加载的类，且只插桩正则命中的方法。
 * 若某条规则意外出问题，用户可在 {@code instrument-filter.yml}
 * 的 exclude 里排除对应包。
 */
public final class InterfaceProbes {

    /** 探针类别：数据库。 */
    public static final String ACTOR_DB = "DB";
    /** 探针类别：HTTP 入口。 */
    public static final String ACTOR_HTTP = "HTTP";

    /** 一条探针规则。 */
    public static final class Rule {
        /** 匹配的类型名（点号形式，接口/父类/具体类均可）。 */
        public final String type;
        /** 方法名的原始正则文本（用于日志与配置展示）。 */
        public final String methodRegex;
        /** 方法名编译后的正则。 */
        public final Pattern methodPattern;
        /**
         * 是否请求入口。
         *
         * <p>语义：命中且<b>当前线程栈为空</b>时，这里就是一次请求的开始，
         * 需要新建 trace 并把 URI 等信息作为链路标识。
         * 非入口规则（如 JDBC）只作为链路中的普通节点。
         */
        public final boolean isEndpoint;
        /** 标识模板，{@code ${[n].xxx()}} 表示从第 n 个实参取值。 */
        public final String message;
        /** 类别：{@link #ACTOR_DB} 写 span.sql；{@link #ACTOR_HTTP} 写 span.path。 */
        public final String actor;

        Rule(String type, String methodRegex, boolean isEndpoint, String message, String actor) {
            this.type = type;
            this.methodRegex = methodRegex;
            this.methodPattern = Pattern.compile(methodRegex);
            this.isEndpoint = isEndpoint;
            this.message = message;
            this.actor = actor;
        }
    }

    private static final List<Rule> RULES = new ArrayList<>();

    static {
        // ---- Servlet 入口（javax 与 jakarta 两套并存）----
        //
        // service 是所有请求的必经点，是「请求进来 → 响应出去」的精确边界；
        // doFilter 也标 isEndpoint，因为 Filter 在 Servlet 之前执行，
        // 若只靠 service，过滤器链耗时会被漏在链路外。
        //
        // 两者同时标 isEndpoint 不会产生两条 trace ——
        // isEndpoint 只在【栈为空】时新建 trace：
        // Filter 先执行（栈空 → 建 trace），随后 service 同线程执行
        // （栈非空 → 复用 Filter 的 trace），一次请求仍是一条链。
        add("javax.servlet.Servlet", "service", true,
                "${[0].getMethod()} ${[0].getRequestURI()}", ACTOR_HTTP);
        add("jakarta.servlet.Servlet", "service", true,
                "${[0].getMethod()} ${[0].getRequestURI()}", ACTOR_HTTP);
        add("javax.servlet.Filter", "doFilter|doFilterInternal|doFilterNestedErrorDispatch", true,
                "${[0].getMethod()} ${[0].getRequestURI()}", ACTOR_HTTP);
        add("jakarta.servlet.Filter", "doFilter|doFilterInternal|doFilterNestedErrorDispatch", true,
                "${[0].getMethod()} ${[0].getRequestURI()}", ACTOR_HTTP);

        // ---- Spring MVC 分发器 ----
        //
        // 部分环境（MockMvc、Filter 未注册、或 Servlet 容器直接把请求交给
        // DispatcherServlet）下 Filter/Servlet 探针可能不生效，
        // doDispatch 是更靠内的一层，作为补充入口。
        add("org.springframework.web.servlet.DispatcherServlet",
                "doDispatch|doService|doGet|doPost|doPut|doDelete", true,
                "", ACTOR_HTTP);

        // ---- JDBC ----
        //
        // 用正则一条覆盖多个重载，这是"按方法名筛选"的价值：
        // execute / executeQuery / executeLargeQuery / executeUpdate / executeLargeUpdate
        // 都命中 execute.* 分支，不必逐条列举。
        add("java.sql.Statement",
                "execute|execute.*Query|execute.*Update|addBatch", false,
                "${[0]}", ACTOR_DB);
        add("java.sql.PreparedStatement",
                "execute|execute.*Query|execute.*Update", false,
                "", ACTOR_DB);
        // prepareStatement 只负责编译，但部分驱动在编译阶段也会解析 SQL，
        // 标为非入口的普通节点，用于区分"编译耗时"与"执行耗时"。
        add("java.sql.Connection",
                "prepareStatement|prepareCall", false,
                "${[0]}", ACTOR_DB);
    }

    private InterfaceProbes() {
    }

    private static void add(String type, String methodRegex, boolean isEndpoint,
                            String message, String actor) {
        RULES.add(new Rule(type, methodRegex, isEndpoint, message, actor));
    }

    /** 全部规则（只读用途）。 */
    public static List<Rule> rules() {
        return Collections.unmodifiableList(RULES);
    }

    /**
     * 判断某类的某方法是否命中探针规则。
     *
     * @param typeNames  该类可匹配的类型名集合：自身类名 + 直接接口 + 直接父类
     *                   （点号形式）
     * @param methodName 方法名
     * @return 命中的规则；未命中返回 null
     */
    public static Rule match(List<String> typeNames, String methodName) {
        if (typeNames == null || typeNames.isEmpty() || methodName == null) {
            return null;
        }
        for (Rule r : RULES) {
            if (!r.methodPattern.matcher(methodName).matches()) {
                continue;
            }
            for (String t : typeNames) {
                if (r.type.equals(t)) {
                    return r;
                }
            }
        }
        return null;
    }

    /**
     * 该类是否可能命中任何规则 —— 用于快速跳过无关类。
     *
     * <p>大部分类不匹配任何规则，先做这层粗筛，避免每个方法都跑正则。
     */
    public static boolean mightMatch(List<String> typeNames) {
        if (typeNames == null || typeNames.isEmpty()) {
            return false;
        }
        for (Rule r : RULES) {
            for (String t : typeNames) {
                if (r.type.equals(t)) {
                    return true;
                }
            }
        }
        return false;
    }
}
