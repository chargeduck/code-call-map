package com.github.chargeduck.codecallmap.agent.instrument;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「接口探针越过包黑名单」的回归测试。
 *
 * <h2>为什么必须有这条测试</h2>
 * 默认配置里同时存在：
 * <ul>
 *   <li>{@code - org.springframework.}（排除框架内部噪音）；</li>
 *   <li>{@code DispatcherServlet} 的 HTTP 入口探针规则。</li>
 * </ul>
 * 两者曾经直接冲突：黑名单无例外地优先，导致 {@code DispatcherServlet}
 * 永远插不上桩 → 每个请求都没有根节点、没有 {@code path} →
 * 前端只能看到一堆互不相连的中间层碎片。
 *
 * <p>用户的感受是「我明明调了接口，页面上却没有调用链」，
 * 而日志里没有任何报错 —— 这类静默失效必须靠测试锁住。
 *
 * <p>注意：这里只能验证「前缀预判 + 规则匹配」这一层，
 * 真正的 {@code shouldInstrument} 还需要字节码，由 InstrumentationTest 覆盖。
 */
class ProbeExemptionTest {

    /** 复用生产代码里的探针前缀表，验证 DispatcherServlet 会被"预判为可能是探针"。 */
    @Test
    void dispatcherServletIsRecognizedAsProbeCandidate() {
        assertTrue(InterfaceProbes.mightMatch(Arrays.asList(
                        "org.springframework.web.servlet.DispatcherServlet")),
                "DispatcherServlet 必须是探针候选，否则 HTTP 入口永远采不到");
    }

    /** 具名 Servlet 实现（靠父类 / 接口匹配）也要被认出来。 */
    @Test
    void customServletSubclassIsRecognized() {
        // 重要：mightMatch 是「精确相等」匹配，只认规则里写明的类型名。
        // 规则里登记的是 javax.servlet.Servlet（HttpServlet 的父接口），
        // 而 InstrumentFilter.implementsProbeInterface 会把
        // 自身类名 + 直接接口 + 直接父类三者一起喂进来，
        // 因此自定义 Servlet 靠直接父类 HttpServlet → 再靠接口 Servlet 命中。
        assertTrue(InterfaceProbes.mightMatch(Arrays.asList(
                        "com.github.web.OrderServlet",
                        "javax.servlet.Servlet")),
                "javax.servlet.Servlet 必须在规则里（Servlet 实现类的兜底命中项）");
        assertTrue(InterfaceProbes.mightMatch(Arrays.asList(
                        "com.github.filter.AuthFilter",
                        "javax.servlet.Filter")),
                "javax.servlet.Filter 必须在规则里（Filter 实现类靠它命中）");
    }

    /** JDBC 探针：PreparedStatement 的实现类同样要能被识别。 */
    @Test
    void jdbcStatementIsRecognized() {
        // PgPreparedStatement implements java.sql.PreparedStatement，
        // implementsProbeInterface 会把接口名一起传进来，靠它命中
        assertTrue(InterfaceProbes.mightMatch(Arrays.asList(
                "org.postgresql.jdbc.PgPreparedStatement",
                "java.sql.PreparedStatement")));
        assertTrue(InterfaceProbes.mightMatch(Arrays.asList(
                "java.sql.PreparedStatement")));
    }

    /** 普通业务类不该被当成探针（否则会被 probeOnly 限制成"只插几个方法"）。 */
    @Test
    void businessClassIsNotProbeCandidate() {
        assertFalse(InterfaceProbes.mightMatch(Arrays.asList(
                "com.github.chargeduck.service.impl.UserServiceImpl",
                "com.acme.frm.common.core.domain.R")));
    }

    /**
     * 探针豁免的安全边界：豁免只放宽「包黑名单」，
     * 不会把 Tomcat 类加载器之类的禁区也放进来 ——
     * 因为那类名字不会命中任何探针规则。
     */
    @Test
    void classLoaderIsNotExemptedByProbeRules() {
        List<String> names = Arrays.asList(
                "org.apache.catalina.loader.WebappClassLoaderBase",
                "org.apache.catalina.loader.WebappClassLoader");
        assertFalse(InterfaceProbes.mightMatch(names),
                "类加载器绝不能因探针豁免而被插桩，否则会 LinkageError");
    }
}
