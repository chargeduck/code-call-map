package com.github.chargeduck.codecallmap.agent.instrument;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 调用链上下文：基于 ThreadLocal 维护每个线程的 span 栈，自动推导 traceId / spanId / parentId。
 *
 * <p>粗粒度 trace：以「根 span（栈底）」为一条调用链，同一线程内的所有嵌套调用共享同一 traceId。
 * 跨线程（线程池提交、异步回调）不会自动传播，属于已知限制：
 * 采集端通过线程名区分，前端按 traceId 聚合时异步分支会自成一条链，不丢数据。
 *
 * <p><b>禁止在热路径里做字符串拼接</b>——本类所有方法都必须极轻量，
 * 插桩后的目标方法每调用一次就会走一遍 {@link #enter}/{@link #exit}。
 */
public final class TraceContext {

    /** 单条调用链的自增序号（与系统时间解耦，只用于生成可读 id）。 */
    private static final AtomicLong TRACE_SEQ = new AtomicLong();

    /** 线程独占的 span 栈；栈底为根 span。 */
    private static final ThreadLocal<Deque<Frame>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private TraceContext() {
    }

    /** 一次方法调用的活动记录。 */
    public static final class Frame {
        final String traceId;
        final String spanId;
        final String parentId;
        final long startNanos;
        /** 进入时的墙上时间（毫秒），用于前端瀑布图按真实时间轴排布。 */
        final long startMillis;
        final String className;
        final String method;
        final String path;

        Frame(String traceId, String spanId, String parentId,
              long startNanos, long startMillis, String className, String method, String path) {
            this.traceId = traceId;
            this.spanId = spanId;
            this.parentId = parentId;
            this.startNanos = startNanos;
            this.startMillis = startMillis;
            this.className = className;
            this.method = method;
            this.path = path;
        }

        /** 已耗时（毫秒）。 */
        public long elapsedMs() {
            return (System.nanoTime() - startNanos) / 1_000_000L;
        }

        /** 调用开始的墙上时间（毫秒）。 */
        public long startMillis() {
            return startMillis;
        }
    }

    /**
     * 进入一个方法。
     *
     * @return 本次调用的 Frame；调用方必须在 finally 中把它交给 {@link #exit(Frame)}。
     */
    public static Frame enter(String className, String method, String path) {
        Deque<Frame> stack = STACK.get();
        String parentId = stack.isEmpty() ? null : stack.peek().spanId;
        String traceId = stack.isEmpty()
                ? "t-" + TRACE_SEQ.incrementAndGet() + "-" + Long.toHexString(System.nanoTime())
                : stack.peek().traceId;
        Frame frame = new Frame(traceId, nextSpanId(), parentId,
                System.nanoTime(), System.currentTimeMillis(), className, method, path);
        stack.push(frame);
        return frame;
    }

    /**
     * 进入一个「请求入口」方法。
     *
     * <h2>与 {@link #enter} 的差别</h2>
     * 相同的栈行为（栈空则新建 trace），但语义上明确这是<b>一次请求的开始</b>，
     * 用于 Servlet / Filter / DispatcherServlet 这类入口探针。
     *
     * <h2>为什么还需要一个独立方法</h2>
     * 看起来和 {@code enter} 完全一样，但保留独立入口的价值在于：
     * <ol>
     *   <li>未来若需要「强制另起一条 trace」（例如异步 Servlet 的
     *       第二次 dispatch 应该合并而非分裂），只改这里一处；</li>
     *   <li>日志与排查时可以一眼看出哪个 span 是接口入口 ——
     *       之前日志里出现「根节点是 DemoApplication.main，耗时 25.9 秒」，
     *       就是因为 HTTP 入口压根没被采到，只能拿启动方法当根。</li>
     * </ol>
     *
     * @param path 请求标识（如 {@code "GET /api/order"}），会写进 span.path
     */
    public static Frame enterRoot(String className, String method, String path) {
        return enter(className, method, path);
    }

    /**
     * 退出一个方法，弹出栈顶并返回其耗时。
     *
     * <p>防御性处理：若栈顶不是传入的 frame（例如被插桩的代码抛出了 Error 但 finally 未配对、
     * 或与手动埋点混用），则按栈内查找移除，避免栈被污染导致后续所有 span 归属错乱。
     */
    public static Frame exit(Frame frame) {
        Deque<Frame> stack = STACK.get();
        if (stack.isEmpty()) {
            return frame;
        }
        Frame top = stack.peek();
        if (top == frame) {
            stack.pop();
            return frame;
        }
        // 非栈顶：查找并移除（保持栈顶语义的一致性由下一层 finally 收敛）
        boolean removed = stack.remove(frame);
        if (!removed && stack.isEmpty()) {
            STACK.remove();
        }
        return frame;
    }

    /** 当前线程是否处于某个 span 内（用于避免对已在采集中的调用重复处理）。 */
    public static boolean inSpan() {
        return !STACK.get().isEmpty();
    }

    /** 清理当前线程的上下文（线程池复用线程时由采集器在根 span 结束时调用）。 */
    public static void clear() {
        Deque<Frame> stack = STACK.get();
        stack.clear();
        STACK.remove();
    }

    private static String nextSpanId() {
        return "s-" + Long.toHexString(System.nanoTime());
    }
}
