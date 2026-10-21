package com.github.chargeduck.codecallmap.agent.instrument;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InterfaceProbes} 规则匹配测试。
 *
 * <p>这套规则是"第三方调用可见性"的唯一入口，匹配错了会插到无辜的方法上，
 * 所以按接口 + 方法名两个维度严格验证。
 */
class InterfaceProbesTest {

    @Test
    void matchesStatementExecute() {
        List<String> itfs = Collections.singletonList("java.sql.Statement");
        InterfaceProbes.Rule r = InterfaceProbes.match(itfs, "execute");
        assertNotNull(r, "Statement.execute 应命中");
        assertEquals(InterfaceProbes.ACTOR_DB, r.actor);
    }

    @Test
    void matchesPreparedStatementExecuteQuery() {
        List<String> itfs = Collections.singletonList("java.sql.PreparedStatement");
        assertNotNull(InterfaceProbes.match(itfs, "executeQuery"));
    }

    @Test
    void matchesServletFilterDoFilter() {
        List<String> itfs = Arrays.asList("javax.servlet.Filter");
        InterfaceProbes.Rule r = InterfaceProbes.match(itfs, "doFilter");
        assertNotNull(r);
        assertEquals(InterfaceProbes.ACTOR_HTTP, r.actor);
    }

    @Test
    void doesNotMatchUnlistedMethod() {
        List<String> itfs = Collections.singletonList("java.sql.Statement");
        // getConnection 不在规则里 —— 只负责建立连接，不是执行入口
        assertNull(InterfaceProbes.match(itfs, "getConnection"));
        assertNull(InterfaceProbes.match(itfs, "close"));
    }

    @Test
    void doesNotMatchUnlistedInterface() {
        List<String> itfs = Collections.singletonList("java.lang.Runnable");
        assertNull(InterfaceProbes.match(itfs, "execute"), "接口不在规则表里就不该命中");
        assertFalse(InterfaceProbes.mightMatch(itfs));
    }

    @Test
    void mightMatchIsCoarseFilter() {
        assertTrue(InterfaceProbes.mightMatch(Collections.singletonList("java.sql.Statement")));
        assertFalse(InterfaceProbes.mightMatch(Collections.emptyList()));
        assertFalse(InterfaceProbes.mightMatch(null));
    }

    @Test
    void rulesCoverJdbcAndServletEntrances() {
        List<InterfaceProbes.Rule> rules = InterfaceProbes.rules();
        assertTrue(rules.stream().anyMatch(r -> "java.sql.Statement".equals(r.type)));
        assertTrue(rules.stream().anyMatch(r -> "javax.servlet.Filter".equals(r.type)));
        assertTrue(rules.stream().anyMatch(r -> "jakarta.servlet.Filter".equals(r.type)),
                "Jakarta 命名空间的 Filter 也要覆盖（Spring Boot 3+）");
    }

    /**
     * 请求入口必须能被识别出来，且标了 isEndpoint。
     *
     * <p>这是"一次接口调用 = 一条调用链"的前提。此前实测发现：
     * HTTP 入口完全没被采到，导致 trace 的根节点变成了
     * {@code LpgRunner.main}（应用启动，25.9 秒），
     * 用户误以为在看接口调用链。
     */
    @Test
    void servletEntranceIsMarkedAsEndpoint() {
        List<String> types = java.util.Collections.singletonList("javax.servlet.Servlet");
        InterfaceProbes.Rule r = InterfaceProbes.match(types, "service");
        assertNotNull(r, "HttpServlet.service 必须命中探针规则");
        assertTrue(r.isEndpoint, "Servlet 入口必须标 isEndpoint，否则不新建 trace");
        assertTrue(r.message.contains("getRequestURI"),
                "入口标识模板要能取到 URI，否则前端分不清是哪个接口");
    }

    /**
     * 方法名用正则匹配：Java 9+ 的 executeLargeQuery / executeLargeUpdate
     * 也必须命中，不能只认 execute / executeQuery / executeUpdate。
     */
    @Test
    void methodRegexCoversLargeVariants() {
        List<String> types = java.util.Collections.singletonList("java.sql.Statement");
        assertNotNull(InterfaceProbes.match(types, "executeLargeUpdate"),
                "executeLargeUpdate 应命中 execute.*Update 分支");
        assertNotNull(InterfaceProbes.match(types, "executeLargeQuery"),
                "executeLargeQuery 应命中 execute.*Query 分支");
        assertNotNull(InterfaceProbes.match(types, "executeUpdate"),
                "普通 executeUpdate 仍要命中（正则不能写错）");
        assertNull(InterfaceProbes.match(types, "executeBatch2"),
                "不应被过度匹配：executeBatch2 不在规则里");
    }

    /** Spring 的 DispatcherServlet 作为补充入口，靠【类名】匹配而不是接口。 */
    @Test
    void dispatcherServletMatchesByClassName() {
        List<String> types = java.util.Collections.singletonList(
                "org.springframework.web.servlet.DispatcherServlet");
        InterfaceProbes.Rule r = InterfaceProbes.match(types, "doDispatch");
        assertNotNull(r, "DispatcherServlet#doDispatch 必须命中（部分环境没有 Filter）");
        assertTrue(r.isEndpoint, "doDispatch 是请求入口");
    }
}
