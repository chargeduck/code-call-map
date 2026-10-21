package com.github.chargeduck.codecallmap.agent.instrument;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插桩引擎单元测试（不依赖 IDEA 平台）。
 *
 * <p>覆盖三层：过滤规则、上下文推导、真实字节码改造后的行为。
 */
class InstrumentationTest {

    /**
     * 隔离外部过滤配置。
     *
     * <p>{@link InstrumentFilter} 会按优先级读取磁盘上的
     * {@code .callmap/instrument-filter.yml}。测试机若恰好存在该文件，
     * 断言结果会随开发者的本地环境变化 —— 这是不可接受的。
     * 这里把显式配置指向一个不存在的路径，并让项目级查找拿到一个空目录，
     * 使测试始终使用内置默认规则。
     */
    @org.junit.jupiter.api.BeforeEach
    void isolateExternalFilterConfig() {
        System.setProperty(InstrumentFilterConfig.PROP_FILTER_FILE,
                System.getProperty("java.io.tmpdir") + "/ccm-nonexistent-filter.yml");
    }

    @org.junit.jupiter.api.AfterEach
    void restoreFilterConfig() {
        System.clearProperty(InstrumentFilterConfig.PROP_FILTER_FILE);
        System.clearProperty("sun.java.command");
    }

    /**
     * 白名单自动推导：未配置 include 时，从启动类推导业务包。
     *
     * <p>这是"默认就能用"的关键 —— 用户不写任何配置，也应该只采集自己的代码。
     */
    @Test
    void filterDerivesWhitelistFromMainClass() {
        System.setProperty("sun.java.command", "com.github.chargeduck.DemoApplication --spring.profiles.active=local");
        InstrumentFilter f = new InstrumentFilter("");
        ClassLoader cl = getClass().getClassLoader();

        assertTrue(f.shouldInstrument("com/github/chargeduck/service/OrderService", cl),
                "主类所在包下的业务类应被采集");
        assertTrue(f.shouldInstrument("com/github/chargeduck/controller/OrderController", cl),
                "子包同样应被采集");
        assertFalse(f.shouldInstrument("org/flowable/variable/service/impl/VariableScopeImpl", cl),
                "第三方库不在推导出的白名单内，不得插桩");
        assertFalse(f.shouldInstrument("io/micrometer/core/instrument/Meter", cl),
                "三方库不得插桩");
    }

    /**
     * 显式配置优先于自动推导。
     */
    @Test
    void explicitIncludeOverridesDerivedWhitelist() {
        System.setProperty("sun.java.command", "com.github.chargeduck.DemoApplication");
        InstrumentFilter f = new InstrumentFilter("com.myapp");
        ClassLoader cl = getClass().getClassLoader();

        assertTrue(f.shouldInstrument("com/myapp/Api", cl), "显式白名单生效");
        assertFalse(f.shouldInstrument("com/github/chargeduck/Other", cl),
                "显式配置后不应再按主类推导");
    }

    /**
     * 性能优化后的关键回归：探针候选包不能被"廉价前缀过滤"误排除。
     *
     * <p>优化把「是否值得读字节码」提前到 {@code isClassLoaderSubclass} /
     * {@code implementsProbeInterface} 之前，依据是包名前缀。
     * 一旦前缀清单漏了某个驱动，JDBC/Servlet 探针就会静默失效 ——
     * 调用链里看不到 SQL，用户却不知道是"没采集"而不是"真没执行"。
     *
     * <p>注意必须传<b>真实的类字节码</b>：探针判定要看接口/父类，
     * 没有字节码自然判不出来。之前的写法传 null，等于没测到东西。
     */
    @Test
    void probePackagesSurviveCheapPrefixFilter() throws Exception {
        System.setProperty("sun.java.command", "com.github.chargeduck.DemoApplication");
        InstrumentFilter f = new InstrumentFilter("");
        ClassLoader cl = getClass().getClassLoader();

        // 用项目里真实存在的类做字节码来源，只验证"前缀预判放行 + 后续判定能拿到字节码"
        byte[] bytes = readClassBytes(InstrumentationTest.class);

        // 这些包里的实现类必须能进入后续判断（不因前缀预判被直接排除）
        String[] probeCandidates = {
                "org/postgresql/jdbc/PgStatement",
                "com/mysql/cj/jdbc/StatementImpl",
                "com/alibaba/druid/pool/DruidPooledStatement",
                "org/springframework/web/servlet/DispatcherServlet",
                "org/apache/ibatis/executor/statement/BaseStatementHandler",
                "com/baomidou/mybatisplus/core/MybatisMapperProxy",
                "javax/servlet/Filter",
                "jakarta/servlet/Filter",
        };
        for (String cn : probeCandidates) {
            assertTrue(f.couldBeProbeForTest(cn.replace('/', '.')),
                    "探针候选包不得被前缀预判排除: " + cn);
        }

        // 反例：与探针无关的三方库必须被快速排除（即使带着字节码）
        String[] irrelevant = {
                "org/flowable/variable/service/impl/VariableScopeImpl",
                "io/micrometer/core/instrument/Meter",
                "com/google/common/collect/ImmutableList",
                "org/slf4j/LoggerFactory",
        };
        for (String cn : irrelevant) {
            assertFalse(f.shouldInstrument(cn, cl, bytes),
                    "无关三方库应被快速排除: " + cn);
        }
    }

    /** 读取类自身字节码，用于模拟 transform 回调传入的 classfileBuffer。 */

    // ---------- InstrumentFilter ----------

    @Test
    void filterRejectsJdkAndFrameworkClasses() {
        InstrumentFilter f = new InstrumentFilter("");
        ClassLoader loader = getClass().getClassLoader();

        assertFalse(f.shouldInstrument("java/lang/String", loader), "JDK 类不应插桩");
        assertFalse(f.shouldInstrument("org/springframework/context/ApplicationContext", loader), "Spring 类不应插桩");
        assertFalse(f.shouldInstrument("com/github/chargeduck/codecallmap/agent/Span", loader), "自身类不应插桩");
        // 引导类加载器
        assertFalse(f.shouldInstrument("com/foo/Bar", null), "bootstrap 加载器不应插桩");
    }

    @Test
    void filterAcceptsBusinessClassesByDefault() {
        // 策略已改为白名单：默认从启动类推导业务包。
        // 这里显式给出主类，验证"业务包被接受"这一基本行为。
        System.setProperty("sun.java.command", "com.github.App");
        InstrumentFilter f = new InstrumentFilter("");
        assertTrue(f.shouldInstrument("com/github/chargeduck/OrderService", getClass().getClassLoader()));
        assertTrue(f.shouldInstrument("com/github/chargeduck/controller/OrderController", getClass().getClassLoader()));
    }

    @Test
    void filterHonoursIncludePackagesWhitelist() {
        InstrumentFilter f = new InstrumentFilter("com.myapp, com.other");
        assertTrue(f.hasIncludePackages(), "应识别为白名单模式");
        ClassLoader loader = getClass().getClassLoader();

        assertTrue(f.shouldInstrument("com/myapp/OrderService", loader));
        assertTrue(f.shouldInstrument("com/other/Thing", loader));
        assertFalse(f.shouldInstrument("com/github/chargeduck/Unrelated", loader), "白名单外的类不应插桩");
    }

    @Test
    void filterSupportsMultipleSeparators() {
        InstrumentFilter f = new InstrumentFilter("com.a;com.b com.c");
        assertTrue(f.shouldInstrument("com/a/X", getClass().getClassLoader()));
        assertTrue(f.shouldInstrument("com/b/Y", getClass().getClassLoader()));
        assertTrue(f.shouldInstrument("com/c/Z", getClass().getClassLoader()));
    }

    @Test
    void filterSkipsLambdasAndSyntheticByDefault() {
        InstrumentFilter f = new InstrumentFilter("");
        ClassLoader loader = getClass().getClassLoader();
        assertFalse(f.shouldInstrument("com/github/chargeduck/Foo$$Lambda$1", loader), "lambda 不应插桩");
        assertFalse(f.shouldInstrument("com/github/chargeduck/Foo$Inner", loader), "具名内部类默认跳过");
    }

    @Test
    void interfaceWithDefaultMethodIsNeverInstrumented() throws Exception {
        // default 方法有方法体，若不拦接口就会被插桩；
        // 改接口字节码会让 JVM 抛
        //   LinkageError: duplicate interface definition
        // 这是真实踩过的坑（io.micrometer ...Validated）。
        InstrumentFilter filter = new InstrumentFilter("com.github.chargeduck");
        byte[] original = readClassBytes(SampleContract.class);

        byte[] transformed = MethodInstrumentor.instrument(
                original, "com/github/chargeduck/SampleContract",
                filter, SampleContract.class.getClassLoader(), true);

        assertSame(original, transformed, "接口一律不插桩，应原样返回");
    }

    /**
     * 白名单命中的类 vs 仅因探针放行的类，必须能区分。
     *
     * <p>这个区分决定插桩范围：业务类全插，第三方 JDBC/Servlet 实现只插探针方法。
     * 若不加区分地全插，等于按包放开第三方库，
     * 会重演 0.0.1.17~0.0.1.21 的 LinkageError 连环崩。
     *
     * <p>这里不构造真实 JDBC 实现类（要写几十个方法），
     * 而是验证"判定所依据的两个函数"行为正确 ——
     * 插桩器正是用它们组合出最终决策的。
     */
    @Test
    void matchesIncludePackagesDistinguishesBusinessFromProbeClasses() {
        System.setProperty("sun.java.command", "com.github.chargeduck.DemoApplication");
        InstrumentFilter f = new InstrumentFilter("");

        // 业务类：命中白名单前缀 → 整个类都插
        assertTrue(f.matchesIncludePackages("com/github/chargeduck/OrderService"),
                "业务类应命中白名单");

        // 第三方 JDBC 驱动：不在白名单 → 只插探针方法
        assertFalse(f.matchesIncludePackages("com/mysql/cj/jdbc/StatementImpl"),
                "驱动类不命中白名单，只应插探针方法");

        // 驱动类确实实现了探针接口，因此会被放行进入插桩流程
        assertTrue(InterfaceProbes.mightMatch(
                        java.util.Collections.singletonList("java.sql.Statement")),
                "实现 Statement 的类应被识别为探针候选");
    }

    @Test
    void methodLevelRules() {
        assertFalse(InstrumentFilter.isInstrumentableMethod("<init>", "()V", 0), "构造器不插桩");
        assertFalse(InstrumentFilter.isInstrumentableMethod("<clinit>", "()V", 0), "静态块不插桩");
        // ACC_ABSTRACT = 0x0400
        assertFalse(InstrumentFilter.isInstrumentableMethod("foo", "()V", 0x0400), "抽象方法不插桩");
        // ACC_NATIVE = 0x0100
        assertFalse(InstrumentFilter.isInstrumentableMethod("foo", "()V", 0x0100), "native 方法不插桩");
        assertTrue(InstrumentFilter.isInstrumentableMethod("foo", "()V", 0x0001), "普通方法应插桩");
    }

    // ---------- TraceContext ----------

    @Test
    void traceContextBuildsParentChildRelation() {
        TraceContext.Frame root = TraceContext.enter("com.Foo", "root", null);
        assertNull(root.parentId, "根 span 无父");
        assertNotNull(root.traceId);

        TraceContext.Frame child = TraceContext.enter("com.Foo", "child", null);
        assertEqualsStr(root.traceId, child.traceId, "同线程内共享 traceId");
        assertEqualsStr(root.spanId, child.parentId, "子 span 的父应为根 span");

        TraceContext.Frame grand = TraceContext.enter("com.Foo", "grand", null);
        assertEqualsStr(child.spanId, grand.parentId, "孙 span 的父应为子 span");
        assertEqualsStr(root.traceId, grand.traceId, "整条链共享 traceId");

        TraceContext.exit(grand);
        TraceContext.exit(child);
        TraceContext.exit(root);
        assertFalse(TraceContext.inSpan(), "全部退出后不应仍在 span 中");
    }

    @Test
    void traceContextSeparatesThreads() {
        TraceContext.Frame main = TraceContext.enter("com.Foo", "main", null);
        final String[] otherTrace = new String[1];
        Thread t = new Thread(() -> {
            TraceContext.Frame f = TraceContext.enter("com.Foo", "worker", null);
            otherTrace[0] = f.traceId;
            TraceContext.exit(f);
        });
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertNotEqualsStr(main.traceId, otherTrace[0], "不同线程应各自成链");
        TraceContext.exit(main);
    }

    @Test
    void traceContextToleratesOutOfOrderExit() {
        TraceContext.Frame a = TraceContext.enter("com.Foo", "a", null);
        TraceContext.Frame b = TraceContext.enter("com.Foo", "b", null);
        // 故意乱序退出（模拟插桩异常路径下的 finally 不配对）
        TraceContext.exit(a);
        assertTrue(TraceContext.inSpan(), "b 仍在栈中");
        TraceContext.exit(b);
        assertFalse(TraceContext.inSpan());
    }

    // ---------- SpanBridge 关闭态 ----------

    @Test
    void bridgeReturnsNullWhenInactive() {
        SpanBridge.deactivate();
        assertFalse(SpanBridge.isActive());
        assertNull(SpanBridge.methodEnter("com.Foo", "bar", "()V"), "未激活时不应创建 Frame");
        // 关闭态调用不应抛异常
        SpanBridge.methodExit(null);
        SpanBridge.methodExitThrowable(null, new RuntimeException("x"));
    }

    @Test
    void bridgeActivatesWithoutRecorder() {
        // 传入 null recorder 时不应激活
        SpanBridge.activate(null, 0);
        assertFalse(SpanBridge.isActive());
    }

    // ---------- 真实字节码改造 ----------

    @Test
    void instrumentedClassRecordsSpans() throws Exception {
        // 注意：不能用测试类自身的包名。
        // 插件自身包（com.github.chargeduck.codecallmap.*）在过滤规则里是永久排除的
        // —— 插桩自己会导致递归，这是刻意的保护。
        // 这里用一个"业务包"形式的类名走过滤，字节码本身取自 SampleService，
        // 因为改造只依赖字节码内容，不依赖类名与实际类是否对应。
        String include = "com.github.chargeduck";
        InstrumentFilter filter = new InstrumentFilter(include);

        AtomicBoolean active = new AtomicBoolean(true);
        assertTrue(active.get());

        byte[] original = readClassBytes(SampleService.class);
        byte[] transformed = MethodInstrumentor.instrument(
                original, "com/github/chargeduck/SampleService",
                filter, SampleService.class.getClassLoader(), true);

        assertNotNull(transformed);
        assertNotSame(original, transformed, "命中白名单的类应被改造");
        assertTrue(transformed.length != original.length, "字节码长度应发生变化（插入了探针）");

        // 改造后的字节码里应出现 SpanBridge 的引用
        String asText = new String(transformed, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(asText.contains("SpanBridge"), "改造后应引用探针桥接类");
        assertTrue(asText.contains("methodEnter"), "应插入进入探针");
    }

    @Test
    void nonMatchingClassIsNotModified() throws Exception {
        InstrumentFilter filter = new InstrumentFilter("com.definitely.not.this.package");
        byte[] original = readClassBytes(SampleService.class);
        byte[] result = MethodInstrumentor.instrument(
                original, SampleService.class.getName().replace('.', '/'),
                filter, SampleService.class.getClassLoader(), true);
        assertSame(original, result, "白名单外应原样返回");
    }

    @Test
    void inactiveAgentDoesNotModifyBytecode() throws Exception {
        InstrumentFilter filter = new InstrumentFilter("");
        byte[] original = readClassBytes(SampleService.class);
        byte[] result = MethodInstrumentor.instrument(
                original, SampleService.class.getName().replace('.', '/'),
                filter, SampleService.class.getClassLoader(), false);
        assertSame(original, result, "采集关闭时不应改动字节码");
    }

    private static byte[] readClassBytes(Class<?> clazz) throws Exception {
        // 嵌套类名含 $，资源路径需保留 $：com/.../InstrumentationTest$SampleService.class
        String path = clazz.getName().replace('.', '/') + ".class";
        // 优先用系统类加载器（测试类的真实加载器），避免 getResourceAsStream 的相对路径语义差异
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = clazz.getClassLoader();
        }
        try (java.io.InputStream in = cl.getResourceAsStream(path)) {
            assertNotNull(in, "找不到类文件: " + path);
            return in.readAllBytes();
        }
    }

    private static void assertEqualsStr(String expected, String actual, String msg) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, msg);
    }

    private static void assertNotEqualsStr(String a, String b, String msg) {
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b, msg);
    }

    /** 被测样例业务类：模拟典型的 Controller → Service 调用。 */
    public static class SampleService {
        public int compute(int a, int b) {
            return slowAdd(a, b);
        }

        private int slowAdd(int a, int b) {
            long sum = 0;
            for (int i = 0; i < 10_000; i++) {
                sum += i;
            }
            return a + b + (int) (sum & 0);
        }

        public String greet(String name) {
            return "hello, " + name;
        }
    }

    /**
     * 含 default 方法的接口。
     *
     * <p>default 方法有方法体，会被插桩循环"看中"，
     * 但改接口的字节码会导致 JVM 后续抛
     * {@code LinkageError: duplicate interface definition}。
     * 用例 {@link #interfaceWithDefaultMethodIsNeverInstrumented} 守住这条。
     */
    public interface SampleContract {
        default String describe() {
            return "sample";
        }
    }
}
