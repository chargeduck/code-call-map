package com.github.chargeduck.codecallmap.agent.instrument;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插桩清单报告测试。
 *
 * <p>这个文件是给用户排查问题用的：应用启动后打开
 * {@code .callmap/instrument-report.txt} 就能看到"agent 到底动了哪些类和 方法"。
 * 所以测试重点不是格式漂亮，而是<b>信息完整、能被 grep</b>。
 */
class InstrumentReportTest {

    @Test
    void writesClassAndMethodDetails(@TempDir Path dir) throws Exception {
        InstrumentReport rep = new InstrumentReport(dir);
        rep.add("com.github.chargeduck.OrderService", "create", "(Lcom/x/Order;)V", "business");
        rep.add("com.github.chargeduck.OrderService", "query", "(J)Lcom/x/Order;", "business");
        rep.add("org.postgresql.jdbc.PgStatement", "execute", "(Ljava/lang/String;)Z", "DB");
        rep.write();

        Path file = dir.resolve("instrument-report.txt");
        assertTrue(Files.isRegularFile(file), "应生成 instrument-report.txt");

        List<String> lines = Files.readAllLines(file);
        String all = String.join("\n", lines);

        assertEquals(2, rep.classCount(), "应统计到 2 个类");
        assertEquals(3, rep.methodCount(), "应统计到 3 个方法");

        // 类名与方法都要能直接看到，便于 grep
        assertTrue(all.contains("com.github.chargeduck.OrderService"), "应包含类名");
        assertTrue(all.contains("create"), "应包含方法名");
        assertTrue(all.contains("org.postgresql.jdbc.PgStatement"), "探针类也要列出");
        // 角色标注能让用户区分"业务类整体插桩"与"接口探针"
        assertTrue(all.contains("business"), "应标注业务类角色");
        assertTrue(all.contains("DB"), "应标注数据库探针角色");
        // 按包统计，用于一眼看出插桩范围是否失控
        assertTrue(all.contains("com.github.chargeduck") || all.contains("org.postgresql.jdbc"),
                "应有按包统计");
    }

    @Test
    void summaryCountsPerPackage(@TempDir Path dir) throws Exception {
        InstrumentReport rep = new InstrumentReport(dir);
        for (int i = 0; i < 5; i++) {
            rep.add("com.a.Svc" + i, "run", "()V", "business");
        }
        rep.add("com.b.Other", "go", "()V", "business");
        rep.write();

        String all = new String(Files.readAllBytes(dir.resolve("instrument-report.txt")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(all.contains("6"), "类数应为 6");
        assertTrue(all.contains("com.a"), "包统计应包含 com.a");
        assertTrue(all.contains("com.b"), "包统计应包含 com.b");
    }

    /**
     * 回归：插桩器传入的是 ASM 内部名（斜杠分隔），包统计不能全部落到 (default)。
     *
     * <p>线上第一次跑出来的清单里，"按包统计"只有一行
     * {@code 3258  (default)} —— 因为按 {@code '.'} 切分斜杠格式的类名，
     * 永远找不到点，于是全部归到默认包。这个统计本来是判断
     * "插桩范围是否失控"的主要依据，失效后价值归零。
     */
    @Test
    void handleSlashSeparatedClassNames(@TempDir Path dir) throws Exception {
        InstrumentReport rep = new InstrumentReport(dir);
        rep.add("com/github/chargeduck/OrderService", "create", "()V", "business");
        rep.add("com/github/chargeduck/PayService", "pay", "()V", "business");
        rep.add("org/postgresql/jdbc/PgStatement", "execute", "()Z", "DB");
        rep.write();

        String all = new String(Files.readAllBytes(dir.resolve("instrument-report.txt")),
                java.nio.charset.StandardCharsets.UTF_8);

        assertFalse(all.contains("(default)"),
                "斜杠分隔的类名不应被归到 (default) 包");
        assertTrue(all.contains("com.github.chargeduck"), "应按点分展示包名");
        assertTrue(all.contains("org.postgresql.jdbc"), "应按点分展示包名");
        // 明细里的类名也应是点分，便于与用户看到的包名对照、便于 grep
        assertTrue(all.contains("com.github.chargeduck.OrderService"), "明细类名应为点分");
    }

    @Test
    void nullOutputDirIsTolerated() {
        // 没有输出目录时不能抛异常（例如手工埋点场景没有 .callmap）
        InstrumentReport rep = new InstrumentReport(null);
        rep.add("com.a.B", "m", "()V", "business");
        rep.write();
        assertEquals(1, rep.classCount());
    }

    @Test
    void capsEntriesToAvoidBlowup(@TempDir Path dir) throws Exception {
        InstrumentReport rep = new InstrumentReport(dir);
        // 超过上限不应抛异常，且要提示"超出上限"
        for (int i = 0; i < 20050; i++) {
            rep.add("com.a.Svc", "m" + i, "()V", "business");
        }
        rep.write();
        String all = new String(Files.readAllBytes(dir.resolve("instrument-report.txt")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(all.contains("超出上限") || all.contains("未记录"),
                "超限时应明确提示，而不是静默丢弃");
        assertFalse(all.isEmpty());
    }
}
