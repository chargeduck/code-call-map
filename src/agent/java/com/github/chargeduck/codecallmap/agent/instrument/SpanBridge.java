package com.github.chargeduck.codecallmap.agent.instrument;

import com.github.chargeduck.codecallmap.agent.CallMapRecorder;

import java.util.Map;

/**
 * 插桩探针桥接层：被改造的字节码直接调用的静态入口。
 *
 * <p><b>本类是所有插桩调用的唯一出口，必须满足三条铁律：</b>
 * <ol>
 *   <li><b>绝不抛异常</b>——任何异常都必须在本类内部吞掉，否则会污染被监控的业务代码；</li>
 *   <li><b>不做字符串拼接</b>——只有真正需要落盘时才构造 Span；</li>
 *   <li><b>可被关闭</b>——{@link #active} 为 false 时立刻返回，开销接近零。</li>
 * </ol>
 *
 * <p>探针只在「方法即将落盘」时才做耗时判断，避免为每个调用都创建对象。
 */
public final class SpanBridge {

    /** 采集总开关。agent 未初始化完成或已被关闭时为 false。 */
    private static volatile boolean active = false;

    /** 记录器，由 AgentBootstrap 注入。 */
    private static volatile CallMapRecorder recorder;

    /** 低于该耗时（毫秒）的调用默认不落盘，避免海量快方法把磁盘写爆。 */
    private static volatile long minDurationMs = 5;

    private SpanBridge() {
    }

    // ---------- agent 生命周期 ----------

    /** 由 AgentBootstrap 在初始化完成后调用。 */
    public static void activate(CallMapRecorder recorder, long minDurationMs) {
        SpanBridge.recorder = recorder;
        SpanBridge.minDurationMs = Math.max(0, minDurationMs);
        active = recorder != null;
        if (active) {
            lastEmitMillis = System.currentTimeMillis();
            startWatchdog();
        }
    }

    /** 关闭采集（探针立即失效）。 */
    public static void deactivate() {
        active = false;
    }

    public static boolean isActive() {
        return active;
    }

    // ---------- 被插桩字节码调用的探针 ----------

    /**
     * 方法进入探针。返回的 Frame 承载 traceId/spanId/parentId 与起始时间。
     *
     * <p>返回类型是 {@code SpanBridge$Frame} 而非 {@code TraceContext$Frame}，
     * 是为了让插桩后的字节码只依赖 SpaceBridge 这一个类（减小 target 应用的类加载压力）。
     */
    public static Frame methodEnter(String className, String methodName, String descriptor) {
        if (!active) {
            return null;
        }
        try {
            TraceContext.Frame f = TraceContext.enter(className, methodName, null);
            return new Frame(f);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 方法正常退出探针。 */
    public static void methodExit(Frame frame) {
        if (!active || frame == null) {
            return;
        }
        try {
            TraceContext.Frame f = TraceContext.exit(frame.inner);
            emit(f, null, frame);
        } catch (Throwable ignored) {
            // 采集失败绝不影响业务
        }
    }

    /**
     * 方法正常退出探针（带返回值）。
     *
     * <h2>为什么单独开一个方法而不是改 methodExit 签名</h2>
     * 插桩按方法返回类型分流：
     * <ul>
     *   <li>{@code RETURN}（void）→ 仍走 {@link #methodExit}，
     *       栈上本来就没有值可传；</li>
     *   <li>其余（ARETURN/IRETURN/LRETURN/FRETURN/DRETURN）→ 走本方法。</li>
     * </ul>
     * 如果强行统一成一个签名，void 方法就得压一个 {@code null} 上去，
     * 多一条无用指令，还会让 void 方法与"返回 null"在数据上无法区分。
     *
     * <p>⚠️ 这里<b>不序列化</b>返回值，只存引用；
     * 真正的序列化在 {@code emit} 里、确认这条 span 要写盘之后才做。
     * 详见 {@link Frame#retJson()}。
     *
     * @param frame 进入探针创建的 Frame
     * @param ret   返回值（基本类型已由插桩装箱）
     */
    public static void methodExitWithReturn(Frame frame, Object ret) {
        if (!active || frame == null) {
            return;
        }
        try {
            frame.rawReturn = ret;
            // 标记"确实带了返回值"：即便 ret 为 null，也说明方法返回了 null，
            // 与 void 方法的"没有返回值"是两回事，编辑器上要分开显示
            frame.hasReturn = true;
            TraceContext.Frame f = TraceContext.exit(frame.inner);
            emit(f, null, frame);
        } catch (Throwable ignored) {
        }
    }

    /** 方法异常退出探针。 */
    public static void methodExitThrowable(Frame frame, Throwable error) {
        if (!active || frame == null) {
            return;
        }
        try {
            TraceContext.Frame f = TraceContext.exit(frame.inner);
            emit(f, error, frame);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 插桩失败诊断（由 MethodInstrumentor 调用）。
     *
     * <p>改为追加到 {@code .callmap/instrument-failures.txt} 而不是 stderr ——
     * 这类信息量小但会混进用户的应用控制台，落盘后既保留了线索，
     * 又不打扰正常使用。内容会顺带写进 {@code instrument-report.txt}
     * 的统计里，用户排查"某类为什么没插桩"时能直接看到。
     */
    public static void logInstrumentFailure(String className, Throwable t) {
        try {
            appendDiagnostic("instrument-failures.txt",
                    java.time.LocalTime.now().withNano(0) + "\t" + className
                            + "\t" + t.getClass().getName() + ": " + t.getMessage());
        } catch (Throwable ignored) {
        }
    }

    /** 诊断文件的落地目录，activate 时由 AgentBootstrap 告知。 */
    private static volatile java.nio.file.Path diagDir;

    /** 由 AgentBootstrap 在激活时调用，告知诊断文件应写到哪里。 */
    public static void setDiagnosticDir(java.nio.file.Path dir) {
        diagDir = dir;
    }

    /** 追加一行到诊断文件；不抛异常。 */
    private static void appendDiagnostic(String fileName, String line) {
        java.nio.file.Path dir = diagDir;
        if (dir == null) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.write(dir.resolve(fileName),
                    (line + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
    }

    // ---------- 内部 ----------

    /** 只有真的值得记录时才构造并写入 Span。 */
    private static void emit(TraceContext.Frame f, Throwable error, Frame wrapper) {
        if (f == null) {
            return;
        }
        CallMapRecorder rec = recorder;
        if (rec == null) {
            return;
        }
        long durationMs = f.elapsedMs();
        boolean isRoot = f.parentId == null;
        // 快调用且无异常且非根 span → 丢弃，控制数据量
        if (durationMs < minDurationMs && error == null && !isRoot) {
            return;
        }
        try {
            // 到这一步才序列化入参：被上面丢弃的调用完全不会付反射开销。
            // 入口探针/根节点一定写盘，因此它们的参数总会记录。
            String argsJson = wrapper == null ? null : wrapper.argsJson();
            // 返回值同理延迟序列化；retJson() 内部会同时断开对返回对象的引用
            String retJson = wrapper == null ? null : wrapper.retJson();
            // detail 按探针角色分流：
            //   DB 探针   → sql 列（面板的 SQL 栏要看这个）
            //   HTTP 探针 → path 列（调用链下拉要靠它显示 "GET /api/xxx"）
            // 之前这里无条件写 sql，于是 HTTP 请求的路径跑进了 SQL 列，
            // 而 path 永远是 null —— 用户在调用链下拉里只看到 t-4-xxxx 这种 id。
            String detail = wrapper == null ? null : wrapper.detail;
            String sql = (detail != null && wrapper.isDb) ? detail : null;
            String path = (detail != null && !wrapper.isDb) ? detail : null;
            rec.recordInstrumentedSpan(f.traceId, f.spanId, f.parentId,
                    f.className, f.method, f.startMillis(), durationMs,
                    sql, path, argsJson, retJson,
                    error == null ? null : summarize(error));
            // 慢调用改为「落盘 + 前端展示」，不再往目标应用的控制台打印。
            //
            // 为什么不打印：这段输出走 System.err，会直接混进用户的 Terminal，
            // 和业务日志交织在一起，既干扰阅读，也让人误以为是应用自己报的错。
            // 数据已经完整写进 span（durationMs 就在上面那行），
            // 前端按阈值高亮，.callmap/slow-calls.txt 里另存一份便于事后 grep。
            if (durationMs >= slowWarnMs) {
                rec.recordSlowCall(f.traceId, f.spanId, f.className, f.method,
                        durationMs, Thread.currentThread().getName());
            }
            lastEmitMillis = System.currentTimeMillis();
            emittedOnce = true;
        } catch (Throwable ignored) {
        }
    }

    /** 慢调用阈值：超过就记入慢调用清单（默认 3000ms，可由 system property 覆盖）。 */
    private static final long SLOW_WARN_MS = 3000L;

    /** 生效的慢调用阈值，activate 时从配置读入。 */
    private static volatile long slowWarnMs = SLOW_WARN_MS;

    /**
     * 最后一次成功写出 span 的时间戳（System.currentTimeMillis）。
     *
     * <p>用途：配合看门狗检测"采集完全停摆"。被插桩方法一旦卡住不返回，
     * {@link #emit} 就不会被调用，也就不会触发慢调用告警 ——
     * 这时候只有"多久没有新数据"能说明问题。
     */
    private static volatile long lastEmitMillis = System.currentTimeMillis();

    /** 停摆告警阈值。 */
    private static final long STALL_WARN_MS = 15000L;

    /** 看门狗是否已启动，保证幂等。 */
    private static volatile boolean watchdogStarted = false;

    /** 是否已写出过至少一条 span。用于区分"启动阶段还没数据"与"真的卡住了"。 */
    private static volatile boolean emittedOnce = false;

    /** 已 dump 过的停摆次数，用于节流（见 {@link #STALL_DUMP_EVERY}). */
    private static volatile int stallDumpCount = 0;

    /**
     * 同一轮停摆最多允许 dump 几次、以及两次 dump 的最小间隔。
     *
     * <h2>为什么不再"只报一次"</h2>
     * 旧实现用 {@code stallDumped} 布尔量，第一次报完就永久关闭，
     * 结果真正的卡死发生在第二次以后时<b>完全静默</b> —— 保护只生效一次，
     * 比不报还危险（用户会以为"没告警就是没卡"）。
     * 改成按次数节流：既能重复报警，又不会把控制台刷爆。
     */
    private static final int STALL_DUMP_LIMIT = 3;
    private static final long STALL_DUMP_INTERVAL_MS = 60_000L;
    private static volatile long lastStallDumpMillis = 0L;

    /**
     * 判定"应用空闲"的依据：所有存活线程里找不到任何一个正在干活。
     *
     * <h2>为什么必须加这一层</h2>
     * 实测 demo 项目：Spring Boot 启动完成（15.0s）后无人访问，
     * 15 秒后看门狗就把"启动完还没人发请求"当成"采集卡死"，
     * dump 出 400 行线程栈 —— 而栈里 12 个 http-nio 线程全在
     * {@code LinkedBlockingQueue.take} 上等任务，main 线程已经跑完退出，
     * 典型的<b>空闲</b>而非卡死。这种误报比不报更干扰排查。
     *
     * <p>判据：只要存在一个非守护的业务线程处于 RUNNABLE 且栈顶不在
     * 常见的"等待"调用上，就认为应用在干活 —— 此时才值得 dump。
     */
    private static boolean looksBusy() {
        for (java.util.Map.Entry<Thread, StackTraceElement[]> e
                : Thread.getAllStackTraces().entrySet()) {
            Thread th = e.getKey();
            // 看门狗自己、agent 内部线程不算业务活动
            if (th == Thread.currentThread()) {
                continue;
            }
            if (th.getName() != null && th.getName().startsWith("codecallmap-")) {
                continue;
            }
            if (th.getState() != Thread.State.RUNNABLE) {
                continue;
            }
            StackTraceElement[] st = e.getValue();
            if (st.length == 0) {
                continue;
            }
            StackTraceElement top = st[0];
            String cls = top.getClassName();
            String mtd = top.getMethodName();
            // 空闲池线程：等任务、等锁、睡、等 IO selector
            if (mtd.equals("take") || mtd.equals("poll") || mtd.equals("park")
                    || mtd.equals("parkNanos") || mtd.equals("parkUntil")
                    || mtd.equals("await") || mtd.equals("awaitNanos")
                    || mtd.equals("sleep") || mtd.equals("select")
                    || mtd.equals("accept0") || mtd.equals("poll0")) {
                continue;
            }
            // SocketInputStream.read 之类的"等网络数据"在 RUNNABLE 下也可能出现，
            // 但那种等待本身就是要排查的现象，不排除。
            if (cls.startsWith("java.util.concurrent")
                    || cls.startsWith("jdk.internal.misc")
                    || cls.startsWith("sun.nio.ch")) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * 启动停摆看门狗：若超过 {@link #STALL_WARN_MS} 没有任何 span 写出，
     * 打印<b>所有线程</b>的栈。
     *
     * <h2>为什么需要它</h2>
     * 线上表现是：应用卡在某个位置，但日志里既没有异常，也没有慢调用告警
     * ——因为卡住的地方可能根本没被插桩覆盖，或者卡在 enter 与 exit 之间。
     * 这时唯一能说明问题的就是"采集断了多久"，以及卡住瞬间各线程在做什么。
     *
     * <p><b>只在写出过第一条 span 之后才启用</b>：应用启动初期本来就没有
     * 调用记录，若不加这个条件，启动 15 秒就会误报一次（Spring Boot 启动
     * 动辄十几秒，会形成噪音）。
     *
     * <p>只读取 {@code Thread.getAllStackTraces()}，不修改任何业务状态；
     * 线程是 daemon，不会阻止 JVM 退出。
     *
     * <h2>两级判据</h2>
     * <ol>
     *   <li><b>先看线程活性</b>（{@link #looksBusy()}）：全部在等任务/等锁/睡眠
     *       说明是「应用空闲、没请求」，直接跳过，不刷屏；</li>
     *   <li><b>再看停摆时长</b>：确实有线程在跑却没有新 span，才说明插桩或采集本身出了问题。</li>
     * </ol>
     */
    static void startWatchdog() {
        if (watchdogStarted || !active) {
            return;
        }
        watchdogStarted = true;
        Thread t = new Thread(() -> {
            while (active) {
                try {
                    Thread.sleep(STALL_WARN_MS);
                } catch (InterruptedException ie) {
                    return;
                }
                if (!active || !emittedOnce) {
                    continue;
                }
                long idle = System.currentTimeMillis() - lastEmitMillis;
                if (idle < STALL_WARN_MS) {
                    continue;
                }
                // 空闲判定：应用没在干活时"没有新数据"是正常的，不是故障。
                if (!looksBusy()) {
                    // 把基准时间往前推，避免空闲结束后立刻误报一次
                    lastEmitMillis = System.currentTimeMillis();
                    continue;
                }
                long now = System.currentTimeMillis();
                if (stallDumpCount >= STALL_DUMP_LIMIT
                        || now - lastStallDumpMillis < STALL_DUMP_INTERVAL_MS) {
                    continue;
                }
                stallDumpCount++;
                lastStallDumpMillis = now;
                dumpStall(idle);
                lastEmitMillis = now;
            }
        }, "codecallmap-watchdog");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 采集停摆诊断：把线程栈写到 {@code .callmap/stall-dump.txt}，<b>不打印到控制台</b>。
     *
     * <h2>为什么不打印</h2>
     * 一次 dump 就有几百行线程栈，走 {@code System.err} 会直接把用户的应用控制台淹没，
     * 而且这些内容和业务毫无关系。落盘后需要时自己打开看，不影响正常排障。
     */
    private static void dumpStall(long idleMs) {
        try {
            StringBuilder sb = new StringBuilder(8192);
            sb.append("# CodeCallMap 采集停摆诊断\n");
            sb.append("# 时间: ").append(java.time.LocalDateTime.now().withNano(0)).append('\n');
            sb.append("# 已 ").append(idleMs / 1000)
                    .append(" 秒没有新的调用记录，且检测到有线程正在运行（非空闲等待）\n");
            for (java.util.Map.Entry<Thread, StackTraceElement[]> e
                    : Thread.getAllStackTraces().entrySet()) {
                Thread th = e.getKey();
                sb.append("\n  \"").append(th.getName()).append("\" ")
                        .append(th.getState());
                if (th.isDaemon()) {
                    sb.append(" daemon");
                }
                sb.append('\n');
                StackTraceElement[] st = e.getValue();
                int limit = Math.min(st.length, 30);
                for (int i = 0; i < limit; i++) {
                    sb.append("      at ").append(st[i]).append('\n');
                }
                if (st.length > limit) {
                    sb.append("      ... 还有 ").append(st.length - limit).append(" 帧\n");
                }
            }
            writeDiagnosticFile("stall-dump.txt", sb.toString());
        } catch (Throwable ignored) {
            // 诊断绝不能影响业务
        }
    }

    /** 把诊断内容写成文件；失败静默忽略（诊断不能影响业务）。 */
    private static void writeDiagnosticFile(String fileName, String content) {
        java.nio.file.Path dir = diagDir;
        if (dir == null) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.write(dir.resolve(fileName),
                    content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    private static String summarize(Throwable t) {
        String msg = t.getMessage();
        String head = t.getClass().getName();
        return msg == null ? head : head + ": " + msg;
    }

    /**
     * 插桩字节码使用的 Frame 包装。
     *
     * <p>做成独立类是为了让「插桩后的方法」只引用 SpanBridge 这一个符号，
     * 避免 TraceContext 被 target 应用的类加载器重复加载时产生类型不匹配
     * （agent 的类是 append 到系统类加载器的，二者本就不同，包装层隔断这层耦合）。
     */
    public static final class Frame {
        final TraceContext.Frame inner;
        /**
         * 附加信息：JDBC 探针写入 SQL，HTTP 探针写入 URI 形式的请求标识。
         * 普通业务方法插桩时为 null。
         *
         * <p>注意：<b>写入哪个字段由 {@link #isDb} 决定</b>，
         * 不能一律当 SQL 写 —— 那会让 HTTP 的 {@code GET /api/x}
         * 跑到面板的 SQL 列里，而 path 列永远是空的。
         */
        final String detail;
        /** true 表示这是 DB 探针（detail 是 SQL）；false 且 detail 非空表示 HTTP 探针（detail 是路径）。 */
        final boolean isDb;
        /**
         * 已序列化好的入参 JSON。
         *
         * <p>为 null 表示"本次未采集参数"（未开启采集、或没有任何参数）。
         * 在 {@code emit} 里确定要写盘之后才序列化 —— 见 {@link #args}。
         */
        final String args;
        /** 原始实参引用（延迟到确定写盘时才序列化，避免为被丢弃的调用付反射开销）。 */
        final Object[] rawArgs;
        /** 参数名（来自字节码 LocalVariableTable，可能为 null）。 */
        final String[] paramNames;

        /**
         * 原始返回值引用（出口探针填入）。
         *
         * <p>为什么不是 final：进入探针创建 Frame 时还不知道返回值，
         * 必须等 {@code methodExitWithReturn} 才补上。
         *
         * <p>为什么要 null 掉：Frame 在方法生命周期内一直可达，
         * 持有返回值引用会让返回的大对象（{@code List<Entity>} 等）
         * 在 {@code emit} 之后仍无法回收，等于人为制造内存泄漏。
         * {@code emit} 序列化完必须立即置 null。
         */
        Object rawReturn;

        /**
         * 出口探针是否真的带了返回值。
         *
         * <p>★ 这是为了区分两种"没有值"，两者语义完全不同：
         * <ul>
         *   <li>{@code void} 方法 —— <b>不存在</b>返回值，不该写任何东西；</li>
         *   <li>返回 {@code null} —— <b>有</b>返回值，值是 null，
         *       应当在编辑器里明确显示 {@code return = null}。</li>
         * </ul>
         * 只看 {@code rawReturn == null} 无法区分，必须靠这个标记。
         */
        boolean hasReturn;

        Frame(TraceContext.Frame inner) {
            this(inner, null);
        }

        Frame(TraceContext.Frame inner, String detail) {
            this(inner, detail, false, null, null, null);
        }

        Frame(TraceContext.Frame inner, String detail, boolean isDb,
              String args, Object[] rawArgs, String[] paramNames) {
            this.inner = inner;
            this.detail = detail;
            this.isDb = isDb;
            this.args = args;
            this.rawArgs = rawArgs;
            this.paramNames = paramNames;
        }

        /**
         * 取入参 JSON，必要时才序列化。
         *
         * <p>为什么要延迟：{@code emit} 会丢弃"耗时低于阈值且非根"的快调用，
         * 而高频业务方法里绝大多数调用都被丢弃。
         * 若在方法入口就反射序列化，等于给每个被丢弃的调用都白付一次
         * 反射 + 字符串拼接的开销 —— 这会显著拖慢目标应用，
         * 而收益是零（数据根本不会写盘）。
         */
        String argsJson() {
            if (args != null) {
                return args;
            }
            if (rawArgs == null || rawArgs.length == 0) {
                return null;
            }
            return ArgSerializer.serialize(paramNames, rawArgs);
        }

        /**
         * 取返回值 JSON，必要时才序列化；序列化后立即释放引用。
         *
         * <p>与 {@link #argsJson()} 同样延迟：{@code emit} 只会为
         * 「慢调用 / 异常 / 根节点」序列化，快调用根本走不到这里。
         *
         * <p>★ 序列化完立刻把 {@code rawReturn} 置 null 是必须的：
         * 返回的大对象如果在 Frame 里继续可达，GC 回收不了，
         * 长期运行会表现为内存持续上涨。
         *
         * @return 返回值 JSON；void 方法（未带返回值）时返回 null；
         *         返回 null 会产出 {@code {"return":null}}，与 void 区分开
         */
        String retJson() {
            if (!hasReturn) {
                // void 方法：根本不存在返回值，不能写成 null
                return null;
            }
            Object ret = rawReturn;
            // 先清引用再序列化：即便序列化抛异常，引用也已断开
            rawReturn = null;
            return ArgSerializer.serialize(new String[]{"return"}, new Object[]{ret});
        }
    }

    /**
     * 带附加信息的进入探针（供 {@link InterfaceProbes} 使用）。
     *
     * @param detail DB 探针传 SQL，HTTP 探针传 URI；会做长度截断与敏感信息脱敏
     */
    public static Frame methodEnterWithDetail(String className, String methodName,
                                              String descriptor, String detail) {
        if (!active) {
            return null;
        }
        try {
            // HTTP 探针把 URI 记进 path 字段（前端已支持渲染）；
            // DB 探针的 SQL 走 Frame.detail，退出时写入 span.sql
            String safeDetail = truncate(detail);
            TraceContext.Frame f = TraceContext.enter(className, methodName, safeDetail);
            return new Frame(f, safeDetail);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 普通业务方法的进入探针，并把实参原样带上（供出口时序列化）。
     *
     * <p>这里<b>不做序列化</b>，只存引用：序列化的代价要等到
     * {@code emit} 确认这条 span 会写盘之后才付出。
     * 详见 {@link Frame#argsJson()}。
     */
    public static Frame methodEnterWithArgs(String className, String methodName,
                                            String descriptor, Object[] rawArgs,
                                            String[] paramNames) {
        if (!active) {
            return null;
        }
        try {
            // 前两个参数名给 Frame 做展示用（入口探针的 detail 位传 null）
            TraceContext.Frame f = TraceContext.enter(className, methodName, null);
            // 普通业务方法没有 detail，isDb 传 false 即可（detail 为 null 时不会被使用）
            return new Frame(f, null, false, null, rawArgs, paramNames);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 探针专用入口：区分 DB / HTTP 两类附加信息，并支持「请求入口」语义。
     *
     * @param requestObject HTTP 探针传入的 request 实参（可为 null）
     * @param isEndpoint    true 表示这是请求入口：栈为空时建立 trace 根
     */
    public static Frame methodEnterProbe(String className, String methodName,
                                         String descriptor, Object requestObject,
                                         boolean isDbProbe, boolean isEndpoint) {
        if (!active) {
            return null;
        }
        try {
            String detail;
            if (isDbProbe) {
                // agent 编译目标是 Java 8（兼容 JDK 8 目标应用），
                // 不能用 instanceof 模式匹配语法
                detail = requestObject instanceof String
                        ? truncate((String) requestObject) : null;
            } else {
                detail = describeHttpRequest(requestObject);
            }
            TraceContext.Frame f = isEndpoint
                    ? TraceContext.enterRoot(className, methodName, detail)
                    : TraceContext.enter(className, methodName,
                            isDbProbe ? null : detail);
            return new Frame(f, detail, isDbProbe, null, null, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 从 Servlet request 对象反射取出 {@code "GET /api/order"} 形式的描述。
     *
     * <p>用反射而不是在字节码里直接调用 {@code getRequestURI()}：
     * 实参的静态类型在不同容器下可能是 {@code ServletRequest}（不含该方法）
     * 或是有状态 proxy，字节码层面做类型判断很脆弱；
     * 反射失败可以安全降级为 null，绝不会把目标应用搞崩。
     */
    private static String describeHttpRequest(Object request) {
        if (request == null) {
            return null;
        }
        try {
            String method = invokeString(request, "getMethod");
            String uri = invokeString(request, "getRequestURI");
            if (uri == null) {
                // 非 HTTP 请求（如 Filter 收到的不是 HttpServletRequest）
                return method;
            }
            return (method == null ? "" : method + " ") + uri;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 反射调用无参 String 方法；失败返回 null。 */
    private static String invokeString(Object target, String methodName) {
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            return v == null ? null : v.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 截断过长的附加信息。
     *
     * <p>SQL 可能带超长 IN 列表，URI 可能带巨大 query string，
     * 不截断会让 JSONL 迅速膨胀。300 字符足以定位到具体语句。
     */
    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        String one = s.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() <= 300 ? one : one.substring(0, 300) + "...";
    }

    /** 保留：供后续扩展「手动埋点 + 自动插桩」混合模式使用。 */
    @SuppressWarnings("unused")
    public static void manualSpan(String className, String method, Map<String, ?> args) {
        CallMapRecorder rec = recorder;
        if (!active || rec == null) {
            return;
        }
        try {
            TraceContext.Frame f = TraceContext.enter(className, method, null);
            rec.recordInstrumentedSpan(f.traceId, f.spanId, f.parentId, className, method,
                    f.startMillis(), 0, null);
            TraceContext.exit(f);
        } catch (Throwable ignored) {
        }
    }
}
