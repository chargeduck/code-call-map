package com.github.chargeduck.codecallmap.agent.instrument;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 类文件转换器：拦截所有类加载，把命中的类交给 {@link MethodInstrumentor} 改造。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>失败即放行</b>——任何一步异常都返回 null（表示不修改），保证目标应用照常启动；</li>
 *   <li><b>幂等</b>——同一类名只处理一次，记录已处理集合，避免 ClassCircularityError；</li>
 *   <li><b>可观测</b>——统计拦截数 / 插桩数 / 跳过数，退出时打印，让用户知道到底生效没有。</li>
 * </ul>
 */
public final class CallMapTransformer implements ClassFileTransformer {

    private final InstrumentFilter filter;
    private final Set<String> processed = ConcurrentHashMap.newKeySet();

    /** 插桩清单（可为 null）。用于把"到底动了哪些类和方法"落盘给用户自查。 */
    private final InstrumentReport report;

    private final AtomicLong seen = new AtomicLong();
    private final AtomicLong instrumented = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    /**
     * transform 回调的累计耗时（纳秒）。
     *
     * <p>为什么要单独统计：用户反馈"加了 agent 就卡住"，但仅凭日志无法判断
     * 到底是插桩拖慢的、还是应用自身在等外部资源。
     * 这个数字是直接证据 —— 若累计耗时只有几百毫秒，
     * 那 99 秒的空白就与插桩无关。
     */
    private final AtomicLong transformNanos = new AtomicLong();

    /** transform 回调被调用次数的耗时样本（只用于算平均，不做精确统计）。 */
    private final AtomicLong timedCount = new AtomicLong();

    public CallMapTransformer(InstrumentFilter filter) {
        this(filter, null);
    }

    /**
     * @param report 插桩清单，可为 null（不记录明细）
     */
    public CallMapTransformer(InstrumentFilter filter, InstrumentReport report) {
        this.filter = filter;
        this.report = report;
    }

    /** 插桩清单（可能为 null）。 */
    public InstrumentReport report() {
        return report;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null) {
            return null;
        }
        // 计时：只统计真正进入判断流程的部分，用于区分"插桩慢"与"应用自己慢"。
        // System.nanoTime 在热点路径上开销可忽略（约 20ns），且每类只调一次。
        long t0 = System.nanoTime();
        try {
            return doTransform(loader, className, classfileBuffer);
        } finally {
            transformNanos.addAndGet(System.nanoTime() - t0);
            timedCount.incrementAndGet();
        }
    }

    private byte[] doTransform(ClassLoader loader, String className, byte[] classfileBuffer) {
        // 自身与 ASM 相关类绝不插桩（防递归、防类加载死锁）
        if (className.startsWith("com/github/chargeduck/codecallmap/")
                || className.startsWith("org/objectweb/asm/")) {
            return null;
        }
        seen.incrementAndGet();
        if (!processed.add(className)) {
            // 已处理过：直接放行，避免重复改造
            return null;
        }
        if (!SpanBridge.isActive()) {
            return null;
        }
        // 过滤判断统一交给 filter，并传入原始字节码 ——
        // 因为"是否继承 ClassLoader"需要读字节码才能判断，
        // 而类加载器实现是插桩的绝对禁区：
        // 插桩它会改变类定义时机，JVM 报
        //   LinkageError: attempted duplicate abstract class definition
        // （Tomcat 的 WebappClassLoaderBase 就是这么炸的）。
        if (!filter.shouldInstrument(className, loader, classfileBuffer)) {
            return null;
        }
        try {
            byte[] result = MethodInstrumentor.instrument(
                    classfileBuffer, className, filter, loader, true, report);
            if (result != classfileBuffer && result != null) {
                instrumented.incrementAndGet();
                return result;
            }
        } catch (Throwable t) {
            failed.incrementAndGet();
            SpanBridge.logInstrumentFailure(className, t);
        }
        return null;
    }

    /** 已扫描类数。 */
    public long seenCount() {
        return seen.get();
    }

    /** transform 回调累计耗时（毫秒）。 */
    public long transformMillis() {
        return transformNanos.get() / 1_000_000L;
    }

    /** 实际完成插桩的类数。 */
    public long instrumentedCount() {
        return instrumented.get();
    }

    /** 插桩失败类数（已自动降级为原字节码）。 */
    public long failedCount() {
        return failed.get();
    }
}
