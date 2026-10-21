package com.github.chargeduck.codecallmap.webview;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CallMapData} 的会话管理测试。
 *
 * <p>核心关注点：agent 每次启动产生一个 JSONL 文件，
 * 面板必须<b>按会话隔离</b>加载 —— 混读会让多次运行的调用链交织，
 * 用户无法判断某条耗时属于哪一次运行。
 */
class CallMapDataSessionTest {

    private Path tempDir;
    private String originalOutputDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("ccm-session-test");
        originalOutputDir = System.getProperty("callmap.output.dir");
        System.setProperty("callmap.output.dir", tempDir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (originalOutputDir == null) {
            System.clearProperty("callmap.output.dir");
        } else {
            System.setProperty("callmap.output.dir", originalOutputDir);
        }
        try (Stream<Path> walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理失败不影响测试结论
                }
            });
        }
    }

    @Test
    void emptyDirectoryYieldsNoSessions() {
        assertTrue(CallMapData.listSessions().isEmpty());
        CallMapData.Snapshot snapshot = CallMapData.load();
        assertNull(snapshot.session);
        assertTrue(snapshot.isEmpty());
    }

    @Test
    void sessionsAreListedNewestFirst() throws Exception {
        // 排序依据是文件 mtime，而两次写入可能落在同一毫秒内导致顺序不确定。
        // 这里显式把较早的文件 mtime 往回拨，使断言与文件系统精度无关。
        Path older = writeSession("callmap-20261019-100000-001.jsonl",
                spanStart("t1", "s1", "A", "run"));
        Path newer = writeSession("callmap-20261019-120000-002.jsonl",
                spanStart("t2", "s2", "B", "run"));
        Files.setLastModifiedTime(older,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));

        List<CallMapData.Session> sessions = CallMapData.listSessions();
        assertEquals(2, sessions.size());
        // 按修改时间倒序：后写的在前
        assertEquals("callmap-20261019-120000-002", sessions.get(0).id);
        assertEquals("callmap-20261019-100000-001", sessions.get(1).id);
    }

    @Test
    void sessionLabelIsReadable() throws Exception {
        writeSession("callmap-20261019-140837-123.jsonl", spanStart("t1", "s1", "A", "run"));
        CallMapData.Session s = CallMapData.listSessions().get(0);
        // 期望形如 10-19 14:08:37
        assertEquals("10-19 14:08:37", s.label);
    }

    @Test
    void unrecognizedFileNameFallsBackToRawName() throws Exception {
        writeSession("manual-capture.jsonl", spanStart("t1", "s1", "A", "run"));
        CallMapData.Session s = CallMapData.listSessions().get(0);
        assertEquals("manual-capture", s.label);
    }

    /**
     * 关键用例：两个会话的数据不能互相污染。
     *
     * <p>旧实现把所有 JSONL 合并读取，会导致 A 会话的 span 和 B 会话的 span
     * 出现在同一棵树里。现在按会话加载，必须严格隔离。
     */
    @Test
    void sessionsAreIsolatedFromEachOther() throws Exception {
        Path first = writeSession("callmap-20261019-100000-001.jsonl",
                spanStart("t1", "s1", "FirstClass", "firstMethod"));
        Path second = writeSession("callmap-20261019-120000-002.jsonl",
                spanStart("t2", "s2", "SecondClass", "secondMethod"));

        List<CallMapData.Session> sessions = CallMapData.listSessions();
        assertEquals(2, sessions.size());

        CallMapData.Snapshot newest = CallMapData.load(sessions.get(0));
        assertEquals(1, newest.roots.size());
        assertEquals("SecondClass", newest.roots.get(0).className);

        CallMapData.Snapshot older = CallMapData.load(sessions.get(1));
        assertEquals(1, older.roots.size());
        assertEquals("FirstClass", older.roots.get(0).className);

        assertFalse(newest.roots.get(0).className.equals(older.roots.get(0).className),
                "不同会话的数据必须隔离");
    }

    @Test
    void loadPicksNewestSessionByDefault() throws Exception {
        // 同 sessionsAreListedNewestFirst：显式拉开 mtime，避免同毫秒写入导致顺序不定
        Path older = writeSession("callmap-20261019-100000-001.jsonl", spanStart("t1", "s1", "Older", "m"));
        Path newer = writeSession("callmap-20261019-120000-002.jsonl", spanStart("t2", "s2", "Newer", "m"));
        Files.setLastModifiedTime(older,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));

        CallMapData.Snapshot snapshot = CallMapData.load();
        assertNotNull(snapshot.session);
        assertEquals("Newer", snapshot.roots.get(0).className);
    }

    @Test
    void startAndEndEventsAreMergedBySpanId() throws Exception {
        writeSession("callmap-20261019-100000-001.jsonl",
                spanStart("t1", "s1", "Svc", "call"),
                spanEnd("t1", "s1", 42L));

        CallMapData.Snapshot snapshot = CallMapData.load();
        assertEquals(1, snapshot.roots.size());
        CallMapData.SpanNode node = snapshot.roots.get(0);
        assertEquals("Svc", node.className);
        assertEquals("call", node.method);
        assertEquals(Long.valueOf(42L), node.durationMs);
    }

    @Test
    void selfTimeExcludesChildDurations() throws Exception {
        // 父 100ms，子 60ms → 自身 40ms。
        // 注意子 span 必须带 parentId 才会挂到父节点下（agent 侧由 TraceContext 的 span 栈写入）
        writeSession("callmap-20261019-100000-001.jsonl",
                spanStart("t1", "p1", null, "Parent", "outer"),
                spanStart("t1", "c1", "p1", "Child", "inner"),
                spanEnd("t1", "c1", 60L),
                spanEnd("t1", "p1", 100L));

        CallMapData.Snapshot snapshot = CallMapData.load();
        assertEquals(1, snapshot.roots.size(), "子 span 有 parentId，不应成为根节点");
        CallMapData.SpanNode parent = snapshot.roots.get(0);
        assertEquals("Parent", parent.className);
        assertEquals(1, parent.children.size());
        assertEquals(100L, parent.durationMs);
        assertEquals(40L, parent.selfMs(), "自身耗时 = 总耗时 - 子调用耗时");
    }

    // ---------- 测试辅助 ----------

    private Path writeSession(String fileName, String... lines) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.write(file, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** 构造一条 span_start 事件（字段与 agent 输出保持一致）。 */
    private static String spanStart(String traceId, String spanId, String className, String method) {
        return spanStart(traceId, spanId, null, className, method);
    }

    /** 构造一条带父节点的 span_start 事件。 */
    private static String spanStart(String traceId, String spanId, String parentId,
                                    String className, String method) {
        String pid = parentId == null ? "null" : "\"" + parentId + "\"";
        return "{\"type\":\"span_start\",\"traceId\":\"" + traceId + "\",\"spanId\":\"" + spanId
                + "\",\"parentId\":" + pid + ",\"ts\":1700000000000,\"thread\":\"main\","
                + "\"className\":\"" + className + "\",\"method\":\"" + method
                + "\",\"args\":\"{}\",\"path\":null}";
    }

    /** 构造一条 span_end 事件。 */
    private static String spanEnd(String traceId, String spanId, long durationMs) {
        return "{\"type\":\"span_end\",\"traceId\":\"" + traceId + "\",\"spanId\":\"" + spanId
                + "\",\"durationMs\":" + durationMs + "}";
    }
}
