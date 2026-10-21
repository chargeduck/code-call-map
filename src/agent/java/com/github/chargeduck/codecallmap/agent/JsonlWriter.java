package com.github.chargeduck.codecallmap.agent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JSONL 落盘器：线程安全、流式追加、UTF-8 编码。
 *
 * <p>特性：
 * <ul>
 *   <li>按文件大小（默认 10MB）或记录条数（默认 1000 条）自动轮转分片；</li>
 *   <li>分片文件名带时间戳：{@code callmap-yyyyMMdd-HHmmss-SSS.jsonl}，同毫秒冲突时追加序号；</li>
 *   <li>每条写入后立即 flush，保证进程异常退出时已写数据不丢失（实时可读）；</li>
 *   <li>使用可重入锁串行化写入，多线程并发安全。</li>
 * </ul>
 *
 * <p>纯 Java 实现，不依赖 IntelliJ Platform API。
 */
public final class JsonlWriter implements AutoCloseable {

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final String FILE_PREFIX = "callmap-";
    private static final String FILE_SUFFIX = ".jsonl";

    private final Path outputDir;
    private final long maxFileBytes;   // <=0 表示不按大小轮转
    private final long maxRecords;     // <=0 表示不按条数轮转
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong sequence = new AtomicLong();

    private BufferedWriter writer;
    private Path currentFile;
    private long bytesWritten;
    private long recordsWritten;

    public JsonlWriter(Path outputDir, long maxFileBytes, long maxRecords) throws IOException {
        this.outputDir = outputDir;
        this.maxFileBytes = maxFileBytes;
        this.maxRecords = maxRecords;
        Files.createDirectories(outputDir);
    }

    /** 追加一行 JSON（写入后立即 flush）。线程安全。 */
    public void write(String jsonLine) throws IOException {
        lock.lock();
        try {
            long incomingBytes = jsonLine.getBytes(StandardCharsets.UTF_8).length + 1L;
            boolean sizeLimitHit = maxFileBytes > 0 && bytesWritten + incomingBytes > maxFileBytes;
            boolean countLimitHit = maxRecords > 0 && recordsWritten >= maxRecords;
            if (writer == null || sizeLimitHit || countLimitHit) {
                openNextFile();
            }
            writer.write(jsonLine);
            writer.newLine();
            writer.flush();
            bytesWritten += incomingBytes;
            recordsWritten++;
        } finally {
            lock.unlock();
        }
    }

    private void openNextFile() throws IOException {
        if (writer != null) {
            writer.close();
        }
        currentFile = resolveFreshFile();
        writer = Files.newBufferedWriter(
                currentFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        bytesWritten = 0;
        recordsWritten = 0;
    }

    /** 生成一个不冲突的时间戳分片文件名。 */
    private Path resolveFreshFile() throws IOException {
        while (true) {
            String base = FILE_PREFIX + LocalDateTime.now().format(TS_FORMAT);
            Path candidate = outputDir.resolve(base + FILE_SUFFIX);
            if (!Files.exists(candidate)) {
                return candidate;
            }
            // 同毫秒冲突：追加序号重试
            Path seqCandidate = outputDir.resolve(base + "-" + sequence.incrementAndGet() + FILE_SUFFIX);
            if (!Files.exists(seqCandidate)) {
                return seqCandidate;
            }
        }
    }

    public Path currentFile() {
        lock.lock();
        try {
            return currentFile;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 把入参 JSON 写成独立文件，返回<b>相对会话目录</b>的路径（如
     * {@code args/<spanId>.json}）。
     *
     * <h2>为什么入参单独存文件</h2>
     * <ul>
     *   <li><b>完整性</b>：入参不做任何长度裁剪，1MB 的请求体也原样保全。
     *       JSONL 内联大对象会让单行达到 MB 级，一行坏掉整个会话就打不开。</li>
     *   <li><b>回放</b>：按 spanId 直接定位文件，比在 JSONL 里翻找方便。</li>
     *   <li><b>可读性</b>：主文件保持轻量，前端加载不被拖慢。</li>
     * </ul>
     *
     * <p>写入放在当前会话目录下的 {@code args/}，与 jsonl 文件同级，
     * 便于整目录打包/迁移。
     *
     * @return 相对路径；写入失败返回 null（调用方降级为不记录入参，不影响主流程）
     */
    public String writeArgs(String spanId, String argsJson) {
        if (spanId == null || spanId.isEmpty() || argsJson == null) {
            return null;
        }
        Path file = null;
        try {
            Path dir = outputDir.resolve(ARGS_DIR);
            Files.createDirectories(dir);
            file = dir.resolve(safeFileName(spanId) + ".json");
            Files.write(file, argsJson.getBytes(StandardCharsets.UTF_8));
            return ARGS_DIR + "/" + file.getFileName();
        } catch (Throwable t) {
            // 入参落盘失败不能影响业务与主链路记录
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (Throwable ignored) {
                    // 清理失败无所谓，留个空文件也能看出曾尝试写入
                }
            }
            return null;
        }
    }

    /** 入参子目录名。 */
    public static final String ARGS_DIR = "args";

    /** 返回值子目录名。 */
    public static final String RET_DIR = "ret";

    /**
     * 把方法返回值写到 {@code ret/<spanId>.json}。
     *
     * <h2>为什么返回值也要外置</h2>
     * 返回值的体积通常比入参更大（{@code List<Entity>} 一次几百条很常见）。
     * 若内联进 JSONL，单行会膨胀到 MB 级，前端逐行解析与回放都会卡死
     * —— 这正是入参当初改成外置文件的原因，返回值没有理由例外。
     *
     * <p>与 {@link #writeArgs} 的区别只有子目录：分开放才能在会话目录里
     * 一眼看出"这是返回还是传入"，也便于按需只清理其中一类。
     *
     * @param spanId   span 标识（用它做文件名，与 args 对齐便于回放关联）
     * @param retJson 返回值 JSON（由 {@code ArgSerializer} 产出，已脱敏与限量）
     * @return 相对会话目录的路径（如 {@code ret/xxx.json}）；写入失败或不需写入时 null
     */
    public String writeRet(String spanId, String retJson) {
        if (spanId == null || spanId.isEmpty() || retJson == null) {
            return null;
        }
        Path file = null;
        try {
            Path dir = outputDir.resolve(RET_DIR);
            Files.createDirectories(dir);
            file = dir.resolve(safeFileName(spanId) + ".json");
            Files.write(file, retJson.getBytes(StandardCharsets.UTF_8));
            return RET_DIR + "/" + file.getFileName();
        } catch (Throwable t) {
            // 返回值落盘失败不能影响业务与主链路记录
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (Throwable ignored) {
                    // 同 writeArgs：清理失败无所谓
                }
            }
            return null;
        }
    }

    /** spanId 由 agent 生成，只含字母数字与连字符；这里再做一次防御性替换。 */
    private static String safeFileName(String id) {
        StringBuilder sb = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            sb.append((Character.isLetterOrDigit(c) || c == '-' || c == '_') ? c : '_');
        }
        return sb.toString();
    }

    /** 关闭底层写入器（flush 并释放资源）。 */
    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            if (writer != null) {
                writer.close();
                writer = null;
            }
        } finally {
            lock.unlock();
        }
    }
}
