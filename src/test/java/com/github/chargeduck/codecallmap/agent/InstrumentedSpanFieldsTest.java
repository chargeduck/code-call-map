package com.github.chargeduck.codecallmap.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插桩 span 落盘字段正确性测试。
 *
 * <h2>锁住三个曾经错过的字段</h2>
 * <ol>
 *   <li><b>path 与 sql 不能混用</b>：HTTP 探针的 URI 曾被写进 sql 列，
 *       导致前端调用链下拉只能显示 {@code t-4-xxx} 这种 id，
 *       而面板的 SQL 栏里却出现 {@code GET /api/x}。</li>
 *   <li><b>插桩路径不能写 sourceFile/lineNumber</b>：那两字段来自
 *       {@code SpanBridge.methodExit} 自己的栈帧，实测全是
 *       {@code SpanBridge.java:82}，用户点"跳转源码"永远跳错地方。</li>
 *   <li><b>慢调用要落到 slow-calls.txt</b>，而不是打印到控制台。</li>
 * </ol>
 */
class InstrumentedSpanFieldsTest {

    private static CallMapRecorder recorder(Path dir) throws Exception {
        // 用现有工厂方法（构造函数是私有的），采样 100% 保证测试确定性
        return CallMapRecorder.create(
                CallMapConfig.of(dir, 100, 10L * 1024 * 1024, 1000));
    }

    @Test
    void httpProbeWritesPathNotSql(@TempDir Path dir) throws Exception {
        try (CallMapRecorder rec = recorder(dir)) {
            rec.recordInstrumentedSpan("t1", "s1", null,
                    "org.springframework.web.servlet.DispatcherServlet", "doDispatch",
                    1000L, 42L,
                    null,                       // sql
                    "GET /api/evalProject/page", // path
                    null, null);
        }
        String line = readJsonl(dir);
        assertTrue(line.contains("\"path\":\"GET /api/evalProject/page\""),
                "HTTP 探针的请求标识必须写进 path 字段，实际: " + line);
        assertTrue(!line.contains("\"sql\":\"GET"),
                "path 绝不能跑到 sql 字段，实际: " + line);
    }

    @Test
    void dbProbeWritesSqlNotPath(@TempDir Path dir) throws Exception {
        try (CallMapRecorder rec = recorder(dir)) {
            rec.recordInstrumentedSpan("t1", "s2", null,
                    "org.postgresql.jdbc.PgStatement", "execute",
                    1000L, 15L,
                    "SELECT * FROM tb_eval_project WHERE id = 1", // sql
                    null,                                        // path
                    null, null);
        }
        String line = readJsonl(dir);
        assertTrue(line.contains("\"sql\":\"SELECT * FROM tb_eval_project"),
                "DB 探针的 SQL 必须写进 sql 字段，实际: " + line);
        assertTrue(!line.contains("\"path\":\"SELECT"),
                "SQL 不能跑到 path 字段，实际: " + line);
    }

    @Test
    void instrumentedSpanOmitsSourceFileAndLine(@TempDir Path dir) throws Exception {
        try (CallMapRecorder rec = recorder(dir)) {
            rec.recordInstrumentedSpan("t1", "s3", null,
                    "com.github.chargeduck.controller.UserController", "page",
                    1000L, 4385L, null, null, null, null);
        }
        String line = readJsonl(dir);
        // 这两个字段若出现，必然是 SpanBridge 自己的位置，只会误导前端跳转
        assertTrue(!line.contains("SpanBridge.java"),
                "插桩 span 不应写入探针自身文件名，实际: " + line);
        assertTrue(!line.contains("\"sourceFile\""),
                "插桩 span 不应写 sourceFile，前端改用 className+method 定位，实际: " + line);
    }

    @Test
    void slowCallIsWrittenToDedicatedFile(@TempDir Path dir) throws Exception {
        try (CallMapRecorder rec = recorder(dir)) {
            rec.recordSlowCall("t1", "s9",
                    "com.github.chargeduck.service.impl.UserServiceImpl",
                    "page", 3973L, "http-nio-8888-exec-9");
        }
        Path slow = dir.resolve("slow-calls.txt");
        assertTrue(Files.isRegularFile(slow), "慢调用清单文件必须存在");
        String content = new String(Files.readAllBytes(slow), StandardCharsets.UTF_8);
        assertTrue(content.contains("3973ms"), "必须记录耗时，实际: " + content);
        assertTrue(content.contains("UserServiceImpl#page"),
                "必须记录方法全名，实际: " + content);
        assertTrue(content.contains("http-nio-8888-exec-9"), "必须记录线程名");
        // 表头只写一次，便于人读
        assertTrue(content.contains("# 时间\t耗时\t方法\t线程"),
                "首行应有列名说明，实际: " + content);
    }

    private static String readJsonl(Path dir) throws Exception {
        List<Path> files;
        try (var s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList();
        }
        assertTrue(!files.isEmpty(), "应当产出 jsonl 文件");
        StringBuilder sb = new StringBuilder();
        for (Path p : files) {
            sb.append(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
