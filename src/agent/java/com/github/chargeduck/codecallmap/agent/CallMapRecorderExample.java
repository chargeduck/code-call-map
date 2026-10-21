package com.github.chargeduck.codecallmap.agent;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调用链路 JSONL 落盘模块的简单调用示例（main 可独立运行验证）。
 *
 * <p>运行方式：
 * <pre>{@code
 *   java -Dcallmap.output.dir=/tmp/callmap-demo com.github.chargeduck.codecallmap.agent.CallMapRecorderExample
 * }</pre>
 *
 * <p>演示 span_start / span_end / trace_summary 三类事件、敏感字段脱敏与多线程写入。
 *
 * <p><b>本类必须用 Java 8 语法</b>（见 build.gradle.kts 中 agentJar 的 release=8）：
 * 它随 agent jar 一起分发，而 agent 会被加载进用户任意 JDK 版本的目标进程。
 */
@Slf4j
public final class CallMapRecorderExample {

    private static final Path DEMO_BASE_DIR = Paths.get(System.getProperty("user.dir"));

    /** Java 8 兼容的 Map 构造辅助（替代 Java 9 的 Map.of）。 */
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    public static void main(String[] args) throws Exception {
        log.info("== CodeCallMap - JSONL 落盘示例 ==");

        try (CallMapRecorder recorder = CallMapRecorder.create(DEMO_BASE_DIR)) {
            // 1. span_start：接口调用开始，入参含敏感字段（password 会被脱敏）
            recorder.spanStart(
                    "trace-1001", "span-1", null,
                    "com.github.OrderController", "createOrder",
                    map(
                            "orderId", "A10086",
                            "userId", 9527L,
                            "remark", "加急订单",
                            "password", "supersecret123",
                            "access_token", "abc.def.ghi"
                    ),
                    "/api/order/create");

            // 2. 模拟业务执行
            Thread.sleep(20);

            // 3. span_end：耗时 + SQL + 异常摘要
            recorder.spanEnd("trace-1001", "span-1", 23_456L,
                    "INSERT INTO t_order (id, user_id) VALUES (?, ?)", null);

            // 4. 子 span（可嵌套，通过 parentId 表达调用树）
            recorder.spanStart("trace-1001", "span-2", "span-1",
                    "com.github.PayClient", "pay", map("amount", 99.9), "/internal/pay");
            recorder.spanEnd("trace-1001", "span-2", 8_120L,
                    "UPDATE t_pay SET status = 1 WHERE order_id = ?", null);

            // 5. trace_summary：链路汇总（AI 分析卡点用）
            recorder.traceSummary("trace-1001", 2, 31_576L, 1);

            // 6. 并发写入验证（8 个线程各自写一行）
            Thread[] threads = new Thread[8];
            for (int i = 0; i < threads.length; i++) {
                final int idx = i;
                threads[i] = new Thread(() -> {
                    try {
                        recorder.spanStart("trace-" + idx, "span-" + idx, null,
                                "com.github.Worker", "work",
                                map("task", "job-" + idx, "apiKey", "k-" + idx + "-very-long-secret"),
                                "/api/worker/" + idx);
                        recorder.spanEnd("trace-" + idx, "span-" + idx, 1_000L + idx, null, null);
                    } catch (Exception e) {
                        log.error("[CodeCallMap] 示例并发写入失败: {}", e.toString(), e);
                    }
                }, "demo-thread-" + i);
                threads[i].start();
            }
            for (Thread t : threads) {
                t.join();
            }

            log.info("输出目录: " + recorder.config().outputDir().toAbsolutePath());
            log.info("当前分片: " + recorder.currentFile().toAbsolutePath());
            log.info("采样率: " + recorder.config().sampleRate() + "%");
            log.info("== 示例完成，可查看上述 JSONL 文件内容 ==");
        }
    }
}
