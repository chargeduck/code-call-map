package com.github.chargeduck.codecallmap.agent.instrument;

import org.objectweb.asm.ClassReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 插桩目标过滤规则：决定哪些类需要被改造。
 *
 * <p>三道关，全部通过才插桩：
 * <ol>
 *   <li><b>包前缀白名单</b>——{@code callmap.include.packages}，为空时默认排除 JDK/框架包；</li>
 *   <li><b>类名黑名单</b>——跳过 lambda、内部类、合成类、CGLIB/代理类；</li>
 *   <li><b>类加载器黑名单</b>——跳过 JDK 引导类加载器。</li>
 * </ol>
 *
 * <p>默认排除规则是「宁可少插也不能插崩目标程序」：一旦把 JDK 或框架核心类改坏，
 * 目标应用会以各种诡异的方式启动失败，排查成本极高。
 */
public final class InstrumentFilter {

    /** {@code java.lang.ClassLoader} 的内部名，用于判断某类是否直接继承它。 */
    private static final String CLASS_LOADER_INTERNAL = "java/lang/ClassLoader";

    /**
     * 最小兜底黑名单：当外部配置完全不可用时才会用到。
     *
     * <p>正常路径下规则来自 {@link InstrumentFilterConfig}（用户可编辑的 YAML），
     * 这里的条目只是"连内置默认文件都读不到"时的保险。
     * 因此刻意只保留最关键的几条，避免出现"两套规则说法不一致"。
     */
    private static final String[] FALLBACK_NEVER_INSTRUMENT = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "com.github.chargeduck.codecallmap.",
            "org.springframework.",
            // 容器类加载器：插了必崩，任何情况下都不能碰
            "org.apache.catalina.", "org.apache.tomcat.",
    };

    private final List<String> includePackages;
    private final boolean includeEmpty;
    /** 生效的黑名单：来自外部配置，未配置时用内置兜底。 */
    private final List<String> excludePackages;
    /** 规则来源，用于日志。 */
    private final String configSource;

    /**
     * 使用内置兜底规则构造（兼容旧调用点）。
     */
    public InstrumentFilter(String includePackagesSpec) {
        this(includePackagesSpec, null);
    }

    /**
     * 用外部配置构造。
     *
     * @param includePackagesSpec {@code callmap.include.packages} 指定的白名单；
     *                            非空时优先于配置文件里的 include
     * @param projectDir          项目根目录，用于定位项目级配置文件；可为 null
     */
    public InstrumentFilter(String includePackagesSpec, String projectDir) {
        InstrumentFilterConfig config = InstrumentFilterConfig.load(projectDir);
        List<String> spec = parse(includePackagesSpec);

        // 白名单的三个来源，优先级从高到低：
        //   1) agentArgs / 设置面板显式指定
        //   2) 配置文件里的 include
        //   3) 从主类名自动推导（见 deriveFromMainClass）
        //
        // 为什么要有第 3 条：本项目早期采用"黑名单排除 + 默认全插"，
        // 结果每排除一批三方库就又冒出新的崩溃（Tomcat → micrometer → flowable…），
        // 因为三方库数量无穷，黑名单永远列不完。
        // 改成白名单后，默认只碰"用户自己的业务包"，风险从根上消失。
        String source;
        List<String> resolved;
        if (!spec.isEmpty()) {
            resolved = spec;
            source = "agentArgs 白名单";
        } else if (!config.include.isEmpty()) {
            resolved = config.include;
            source = config.source;
        } else {
            resolved = deriveFromMainClass();
            source = resolved.isEmpty()
                    ? "未配置（无主类信息，退化为黑名单模式）"
                    : "自动推导自启动类";
        }
        this.includePackages = resolved;
        this.includeEmpty = this.includePackages.isEmpty();

        // 黑名单：配置为空时退回内置兜底，保证"至少不会插崩"
        if (config.exclude.isEmpty()) {
            List<String> fallback = new ArrayList<>();
            for (String p : FALLBACK_NEVER_INSTRUMENT) {
                fallback.add(p);
            }
            this.excludePackages = fallback;
            source = source + "（黑名单用内置兜底）";
        } else {
            this.excludePackages = config.exclude;
        }
        this.configSource = source;
    }

    /** 规则来源描述，写进启动日志便于排查。 */
    public String configSource() {
        return configSource;
    }

    /**
     * 实际生效的白名单前缀（可能来自 agentArgs、配置文件或主类推导）。
     *
     * <p>日志要打这个而不是用户配置里的原始值 ——
     * 两者在"自动推导"场景下并不相同。
     */
    public String effectiveIncludeSpec() {
        return includePackages.isEmpty() ? "(无，退化为黑名单模式)" : String.join(",", includePackages);
    }

    /**
     * 从启动类名推导业务包前缀。
     *
     * <h2>为什么要自动推导</h2>
     * 用户第一次装插件时不会先去看文档写配置，期望"直接能用"。
     * 而"哪些包算业务代码"这个问题，JVM 其实已经告诉我们了：
     * 启动类（{@code sun.java.command} 的第一段）通常就在业务根包下。
     *
     * <h2>推导规则</h2>
     * 取主类名的<b>前两段</b>包名，例如：
     * <ul>
     *   <li>{@code com.github.chargeduck.DemoApplication} → {@code com.github.chargeduck}</li>
     *   <li>{@code com.github.chargeduck.App} → {@code com.github.chargeduck}</li>
     * </ul>
     * 只取到主类所在包（不含主类本身），这样同包下的 Controller / Service
     * 都能覆盖。
     *
     * <p>为什么不用"前两段"这种更宽的规则（{@code cn.com}）：
     * 会把一堆无关键名（如 {@code cn.com.xxx.other}）也纳入，失去白名单的意义。
     * 而用户的业务模块通常都在主类所在包或其子包下。
     *
     * <p>如果推导失败（拿不到主类、主类在默认包等），返回空列表，
     * 此时退化为黑名单模式（保持旧行为，不能什么都不采集）。
     */
    private static List<String> deriveFromMainClass() {
        List<String> result = new ArrayList<>();
        try {
            // sun.java.command 形如 "com.github.chargeduck.DemoApplication --spring.profiles.active=local"
            // 或 "xxx.jar"（可执行 jar），取第一段即可
            String cmd = System.getProperty("sun.java.command", "").trim();
            if (cmd.isEmpty()) {
                return result;
            }
            String mainClass = cmd.split("\\s+")[0];
            if (!mainClass.contains(".") || mainClass.endsWith(".jar")) {
                return result;
            }
            int lastDot = mainClass.lastIndexOf('.');
            String pkg = mainClass.substring(0, lastDot);
            if (pkg.isEmpty()) {
                return result;
            }
            result.add(pkg);
        } catch (Throwable ignored) {
            // 推导失败不抛出，交给上层退化为黑名单模式
        }
        return result;
    }

    /** 生效的黑名单条数（日志用）。 */
    public int excludeCount() {
        return excludePackages.size();
    }

    /**
     * 生效的排除规则前若干条（写进插桩清单，供用户核对）。
     *
     * <p>返回副本，避免调用方误改内部状态。规则可能上百条，
     * 报告里只展示前几条 + 总数即可，全文太长反而看不出重点。
     */
    public List<String> excludeSample(int limit) {
        List<String> out = new ArrayList<>();
        int n = Math.min(Math.max(0, limit), excludePackages.size());
        for (int i = 0; i < n; i++) {
            out.add(excludePackages.get(i));
        }
        return out;
    }

    /** 是否配置了显式的包前缀白名单。 */
    public boolean hasIncludePackages() {
        return !includeEmpty;
    }

    /**
     * 类是否应当被插桩（不带字节码的轻量判断）。
     *
     * <p>无法做"是否继承 ClassLoader"这类需要读字节码的判断，
     * 因此只做包名与结构层面的过滤。
     *
     * @param className 内部名形式（com/foo/Bar）或点号形式均可
     */
    public boolean shouldInstrument(String className, ClassLoader loader) {
        return shouldInstrument(className, loader, null);
    }

    /**
     * 类是否应当被插桩。
     *
     * @param className   内部名形式（com/foo/Bar）或点号形式均可
     * @param loader      加载该类的 ClassLoader
     * @param classBytes  类的原始字节码；为 null 时跳过需要读字节码的判断
     *                    （如"是否继承 ClassLoader"）
     */
    public boolean shouldInstrument(String className, ClassLoader loader, byte[] classBytes) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        // 引导类加载器（JDK 自身）永不插桩
        if (loader == null) {
            return false;
        }
        String dotted = className.replace('/', '.');

        // 黑名单永远优先，白名单模式也不例外。
        //
        // 早期实现只在"无白名单"时检查黑名单，导致用户配了白名单后
        // （哪怕是 com.acme.* 这种很窄的前缀）仍可能命中 Tomcat 类加载器，
        // 触发 LinkageError。黑名单里的条目都是"插了就出问题"的基础设施，
        // 不该由用户的包前缀配置来覆盖。
        //
        // 规则来自外部配置文件（见 InstrumentFilterConfig），
        // 用户可以在项目里放一份 .callmap/instrument-filter.yml 自行调整，
        // 无需改插件。
        //
        // ===========================================================
        // 【例外：接口探针豁免】（2026-10-21 修复）
        //
        // 黑名单里同时写有 org.springframework.（防框架内部噪音）和
        // DispatcherServlet 是 HTTP 入口探针的唯一来源 —— 两者直接冲突，
        // 结果是 DispatcherServlet 永远插不上桩，每个请求都没有根节点、
        // 没有 path，前端看到一堆互不相连的碎片，
        // 用户报的现象是"我调了接口，但页面上没有调用链"。
        //
        // 因此这里做一次<b>白名单式豁免</b>：类名/父类/接口命中 InterfaceProbes
        // 规则的，允许越过包黑名单。安全性由"精确到方法名"保证 ——
        // 探针类只插规则列出的那几个方法（见 MethodInstrumentor 的 probeOnly），
        // 不会像"按包开放"那样把整个框架拖进来。
        //
        // 判定顺序很重要：先算 couldBeProbe（纯字符串，极廉价），
        // 只有可能是探针的极少数类才去解析字节码。
        // ===========================================================
        boolean probeExempt = false;
        if (startsWithAny(dotted, excludePackages)) {
            if (couldBeProbe(dotted) && implementsProbeInterface(classBytes)) {
                probeExempt = true;
            } else {
                return false;
            }
        }

        // ---------------------------------------------------------------
        // 【性能关键】把"不读字节码就能排除"的判断全部前置。
        //
        // 实测某 Spring Boot 项目启动时 transform 回调 7909 个类，
        // 而原实现里 isClassLoaderSubclass 与 implementsProbeInterface
        // 各 new 一次 ClassReader（完整解析常量池），且排在白名单判断之前 ——
        // 等于对 7909 个和本项目毫无关系的三方类都做了两遍 ASM 解析。
        //
        // 正确顺序：先用字符串前缀做廉价过滤，能排除的先排除；
        // 只有"可能命中"的少数类才值得去读字节码。
        // ---------------------------------------------------------------
        boolean inWhitelist = includeEmpty || startsWithAny(dotted, includePackages);

        // 类加载器实现一律不插桩 —— 不论包名。
        //
        // 这是"最后一道防线"，理由是包名黑名单永远列不全：
        // 用户自定义的 ClassLoader、第三方库里的加载器、
        // 甚至 JDK 未来新增的实现，都可能踩同样的坑。
        // 判定依据是 ASM 读父类链，不触发类加载。
        //
        // 注意：类不一定在白名单里，所以这一步不能简单跳过；
        // 但可以先排掉"既不在白名单、也明显不是探针候选"的类，
        // 见下面的快速返回。
        if (!inWhitelist && !couldBeProbe(dotted)) {
            // 既不是业务包，也不可能命中探针（JDBC/Servlet 都在固定几个包里），
            // 直接排除，两次 ClassReader 都省掉。这是绝大多数学的情况。
            return false;
        }

        if (isClassLoaderSubclass(classBytes)) {
            return false;
        }

        // ---- 接口探针通道 ----
        //
        // 白名单只管"用户自己的业务类"。但 JDBC / Servlet 这些第三方调用
        // 如果完全不可见，调用链中间会留空白，而且它们的耗时会算到调用方头上
        // （一个 300ms 的 SQL 会让调用方"看起来很慢"）。
        //
        // 所以这里额外放行"实现了探针接口"的类：它们即便不在白名单里也放进来，
        // 但真正插桩时只碰规则指定的那几个方法（见 MethodInstrumentor）。
        //
        // 安全性：探针接口就那几个（java.sql.Statement / javax.servlet.Filter 等），
        // 命中面窄；且这些类的对应方法本身就是独立的执行入口，
        // 不涉及类加载等基础设施，风险可控。
        if (implementsProbeInterface(classBytes)) {
            return true;
        }

        if (!inWhitelist) {
            return false;
        }

        // 结构层面跳过：lambda / 匿名内部类 / 合成类 / 代理类
        if (dotted.contains("$$") || dotted.contains("$Lambda")) {
            return false;
        }
        // 具名内部类（Outer$Inner）默认跳过：内部类方法往往是被外部类直接内联调用的热点，
        // 插桩后栈深爆炸且噪音大。若用户显式指定了包前缀，则不再跳过，交给用户负责。
        if (includeEmpty && isNamedInnerClass(dotted)) {
            return false;
        }
        return true;
    }

    /**
     * 该类是否实现了探针接口（{@link InterfaceProbes}）。
     *
     * <p>只看直接实现的接口，理由同 {@code classInterfaceNames}：
     * JDBC 驱动 / Servlet 实现类都是直接 implements，
     * 递归解析父类链的收益低而开销高。
     */
    /**
     * 该类是否可能命中探针规则（接口 / 父类 / 类名 三种匹配维度）。
     *
     * <h2>这里曾经漏掉两个维度，导致 HTTP 入口完全采不到</h2>
     * 早期只取 {@code reader.getInterfaces()}，于是：
     * <ul>
     *   <li>{@code DispatcherServlet} 不是接口实现类，靠的是<b>类名</b>匹配 —— 被漏掉；</li>
     *   <li>用户自己继承 {@code HttpServlet} 的 Servlet，靠的是<b>父类</b>匹配 —— 被漏掉。</li>
     * </ul>
     * 结果是每个 HTTP 请求都不进插桩流程，一次请求在 trace 里根本没有根节点；
     * 用户看到的"调用链"其实是应用启动过程（实测根节点是
     * {@code LpgRunner.main}，耗时 25.9 秒，子节点合计仅 0.5 秒，
     * 中间 25 秒全是没插桩的空洞）。
     */
    private static boolean implementsProbeInterface(byte[] classBytes) {
        if (classBytes == null || classBytes.length == 0) {
            return false;
        }
        try {
            ClassReader reader = new ClassReader(classBytes);
            List<String> names = new ArrayList<>();
            // 维度一：类名本身（DispatcherServlet 这类具体类）
            String self = reader.getClassName();
            if (self != null) {
                names.add(self.replace('/', '.'));
            }
            // 维度二：直接实现的接口
            String[] itfs = reader.getInterfaces();
            if (itfs != null) {
                for (String itf : itfs) {
                    names.add(itf.replace('/', '.'));
                }
            }
            // 维度三：直接父类（继承 HttpServlet 的自定义 Servlet）
            String superName = reader.getSuperName();
            if (superName != null) {
                names.add(superName.replace('/', '.'));
            }
            return InterfaceProbes.mightMatch(names);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 廉价预判：这个类名有没有可能命中探针规则。
     *
     * <p><b>为什么要这一层</b>：{@link #implementsProbeInterface(byte[])} 会完整解析
     * 一遍字节码。如果对每个类都调用它，一个中型 Spring Boot 项目启动时
     * （实测 7909 个类）就要白做 7909 次 ASM 解析 —— 全部花在和本项目
     * 毫无关系的三方库上。
     *
     * <p>这里只用类名字符串做前缀比较，能挡掉绝大部分。判据不是手写的清单，
     * 而是<b>从探针规则本身推导</b>（{@link InterfaceProbes#rules()}）：
     * 规则里出现的类型名，其所属包就是"值得深查"的范围。
     * 这样规则增删后不需要同步维护两份清单 —— 那种重复早晚会不一致。
     *
     * <p>为保守起见额外放宽：类名里只要含探针关键字的常见包段
     * （servlet / jdbc / mybatis / postgresql / mysql 等）也放行，
     * 避免漏掉某个驱动自己包的实现类。
     */
    private static boolean couldBeProbe(String dotted) {
        for (String prefix : PROBE_PACKAGE_PREFIXES) {
            if (dotted.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 测试用：暴露前缀预判结果。
     *
     * <p>这条判断是性能优化的关键，但它决定"要不要读字节码"，
     * 一旦前缀清单漏项，探针会静默失效且没有任何报错。
     * 因此需要一个可直接断言的入口，而不是只靠间接行为推测。
     */
    boolean couldBeProbeForTest(String dottedClassName) {
        return dottedClassName != null && couldBeProbe(dottedClassName);
    }

    /**
     * 可能的探针实现类所处包前缀，来自 {@link InterfaceProbes#rules()}。
     *
     * <p>规则里的类型长这样：{@code javax.servlet.Filter}、
     * {@code java.sql.PreparedStatement}、{@code org.springframework.web.servlet.DispatcherServlet}。
     * 取到倒数第二段作为包边界即可覆盖同包内的其他实现。
     */
    private static final List<String> PROBE_PACKAGE_PREFIXES;

    static {
        List<String> prefixes = new ArrayList<>();
        try {
            for (InterfaceProbes.Rule r : InterfaceProbes.rules()) {
                if (r == null || r.type == null) {
                    continue;
                }
                String t = r.type;
                int cut = t.lastIndexOf('.');
                if (cut > 0) {
                    // 再往前收一层，覆盖 org.springframework.web.servlet 这类多段包
                    String pkg = t.substring(0, cut);
                    prefixes.add(pkg + ".");
                    int cut2 = pkg.lastIndexOf('.');
                    if (cut2 > 0) {
                        prefixes.add(pkg.substring(0, cut2) + ".");
                    }
                }
            }
        } catch (Throwable ignored) {
            // 规则读取失败就退化为"全部放行"，宁慢勿漏
        }
        // 常见数据源/ORM 驱动：实现类未必在规则声明的包内
        for (String extra : new String[]{
                "org.postgresql.", "com.mysql.", "com.alibaba.druid.",
                "oracle.jdbc.", "com.microsoft.sqlserver.", "org.h2.",
                "org.apache.ibatis.", "com.baomidou.", "org.hibernate.",
                "org.springframework.jdbc.", "com.zaxxer.hikari."
        }) {
            prefixes.add(extra);
        }
        PROBE_PACKAGE_PREFIXES = Collections.unmodifiableList(prefixes);
    }

    /**
     * 该类是否命中包名前缀白名单（只看包名，不考虑探针接口放行）。
     *
     * <p>用途：区分"整个类都要插"（业务类）与"只插探针方法"（第三方 JDBC/Servlet 实现）。
     * {@link #shouldInstrument} 对两者都返回 true，插桩器需要这个信息来决定插桩范围。
     *
     * <p>白名单为空（黑名单模式）时一律返回 true —— 该模式下所有类都完整插桩。
     */
    public boolean matchesIncludePackages(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        if (includeEmpty) {
            return true;
        }
        return startsWithAny(className.replace('/', '.'), includePackages);
    }

    /**
     * 该类是否直接继承 {@code java.lang.ClassLoader}。
     *
     * <h2>为什么这是最后一道防线</h2>
     * 包名黑名单永远列不全。任何 ClassLoader 实现（含用户自定义的、
     * 第三方库的、将来 JDK 新增的）被插桩都可能改变类定义时机，
     * 导致 {@code LinkageError: attempted duplicate abstract class definition}
     * —— Tomcat 的 {@code WebappClassLoaderBase} 正是这么崩的。
     *
     * <h2>只看直接父类的原因</h2>
     * 实践中最危险的是直接继承 ClassLoader 的加载器实现
     * （Tomcat / Jetty / 应用自定义的都是直接继承）。
     * 更深的继承链（如 {@code Foo extends WebappClassLoaderBase}）本身
     * 通常不在应用包路径上，交给包名黑名单处理即可。
     * 只读一层也能避免为每个类递归解析父类，控制启动开销。
     *
     * @param classBytes 类字节码；为 null 时返回 false（无法判断，不拦截）
     */
    private static boolean isClassLoaderSubclass(byte[] classBytes) {
        if (classBytes == null || classBytes.length == 0) {
            return false;
        }
        try {
            ClassReader reader = new ClassReader(classBytes);
            // getSuperName 只解析常量池，不加载类，开销很小
            return CLASS_LOADER_INTERNAL.equals(reader.getSuperName());
        } catch (Throwable ignored) {
            // 解析失败按"不是"处理：后续 ASM 改造流程自己会兜底
            return false;
        }
    }

    /** 构造器与静态初始化块不插桩（无返回值、异常语义复杂，且对耗时分析价值低）。 */
    public static boolean isInstrumentableMethod(String name, String descriptor, int access) {
        if (name == null) {
            return false;
        }
        if ("<init>".equals(name) || "<clinit>".equals(name)) {
            return false;
        }
        // 抽象方法与 native 方法无字节码可改
        if ((access & 0x0400) != 0 || (access & 0x0100) != 0) {
            return false;
        }
        if (descriptor == null) {
            return false;
        }
        return true;
    }

    /** 方法是否可能耗时——用于在入口处判定是否需要采集（静态返回 void 且无参数的空壳方法收益低）。 */
    public static boolean isWorthTracing(String name, int access) {
        if (name == null) {
            return false;
        }
        // getter/setter 噪音大但耗时可能不低，不按名字排除，交给采样率控制
        return true;
    }

    private static boolean isNamedInnerClass(String dotted) {
        int dollar = dotted.indexOf('$');
        if (dollar < 0) {
            return false;
        }
        // 排除 lambda 后的剩余 $ 视为具名内部类
        return !dotted.substring(dollar).contains("$$");
    }

    private static boolean startsWithAny(String value, List<String> prefixes) {
        for (int i = 0; i < prefixes.size(); i++) {
            String p = prefixes.get(i);
            if (p.indexOf('*') >= 0) {
                // 含通配符的条目交给 glob 匹配，见 matchesGlob
                if (matchesGlob(value, p)) {
                    return true;
                }
                continue;
            }
            // 纯前缀规则（无通配符）：startsWith("java.") 本身就不会命中 "javax."，
            // 包边界语义天然成立，无需额外判断。
            if (value.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 极简 glob 匹配，只支持 {@code *}（匹配任意字符，可跨段）。
     *
     * <h2>为什么需要它</h2>
     * 包前缀匹配只能写死完整前缀，可 {@code domain.entity} 这类"数据载体包"
     * 在每个项目里的前缀都不一样：
     * <pre>
     *   com.github.chargeduck.domain.entity
     *   com.github.order.domain.entity
     *   org.acme.shop.entity
     * </pre>
     * 想用前缀规则覆盖，就得为每个项目写一条。而它们的共同点是
     * <b>包名后缀</b>一致 —— 用 {@code *.domain.entity.} 一条规则即可覆盖全部。
     *
     * <h2>末尾的点为什么必须特殊处理</h2>
     * 规则写成 {@code *.domain.entity.}（带末尾点）是为了表达"entity 是一个完整的包段"，
     * 而不是要求类名以字面量 {@code .entity.} 结尾 —— 类名形如
     * {@code ....domain.entity.SysUser}，用字面 endsWith 永远匹配不上。
     *
     * <p>第一版就栽在这里：{@code "*.domain.entity."} 按 {@code *} 切开是
     * {@code ["", ".domain.entity."]}，末段判定拿 {@code ".domain.entity."}
     * 去做 endsWith，结果 {@code *.domain.entity.} 连一个类都匹配不到，
     * 而这不会报错 —— 表现为"排除了但没生效，启动照样慢"。
     * 因此这里把<b>末尾的点</b>视作"包边界"标记先剥掉，再按普通后缀匹配。
     *
     * <h2>实现</h2>
     * 没有引入正则（用户写的配置不该用正则语法，容易转义出错，也可能被写成
     * 灾难性回溯），而是把模式按 {@code *} 切段后做顺序子串搜索：
     * 首段必须从头匹配（若模式不以 {@code *} 开头），末段必须贴到结尾。
     * 复杂度 O(段数 × 长度)，对几十个前缀的规模完全无感。
     */
    static boolean matchesGlob(String rawValue, String pattern) {
        if (rawValue == null || pattern == null) {
            return false;
        }
        // 统一成点分：调用方可能传 ASM 内部名（com/acme/...），
        // 不统一会让 all 匹配规则对内部名全部失效。
        String value = rawValue.replace('/', '.');
        // ---- 末尾的点：包边界标记，剥掉后再做"包段"比较 ----
        //
        // 语义对照（value = "a.b.domain.entity.SysUser"）：
        //   "*.domain.entity."   → 剥成 "*.domain.entity" → 匹配（entity 是完整包段）
        //   "*.domain.entity"    → 同上                    → 匹配
        //   "*.domain.entityx"   → 不剥                    → 不匹配（防误伤）
        boolean trailingDot = pattern.endsWith(".") && !pattern.endsWith("*");
        String effective = trailingDot ? pattern.substring(0, pattern.length() - 1) : pattern;

        // 剥掉末尾点之后如果整个模式变空（即规则就是一个 "."），视为无意义规则
        if (effective.isEmpty()) {
            return false;
        }

        String[] parts = effective.split("\\*", -1);
        // 末尾"固定段"的锚点：若规则以点边界结尾，匹配的是【包名部分】而非完整类名。
        // 例：规则 *.domain.entity. 对 a.b.domain.entity.SysUser，
        // 锚点应是 "a.b.domain.entity"（去掉类名），而不是整个类名 ——
        // 否则 endsWith(".domain.entity") 永远为 false。
        String anchorValue = trailingDot ? packagePart(value) : value;
        int pos = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                continue;
            }
            boolean isFirstFixed = i == 0 && !effective.startsWith("*");
            boolean isLastFixed = i == parts.length - 1 && !effective.endsWith("*");

            if (isFirstFixed && isLastFixed) {
                // 模式里没有通配符：按前缀判断（与旧的前缀规则语义保持一致）。
                //
                // 但若原规则带着末尾点（包边界），则必须按【包段】比较：
                // 规则 "java." 剥成 "java" 后若直接 startsWith("java")，
                // 会把 "javax.swing.JFrame" 也命中 —— javax 是独立包，
                // 误伤它会让 javax.* 下的类被整片跳过（或反过来被误排除）。
                // 带点边界的正确语义："java" 必须是首段且其后紧跟点边界。
                if (trailingDot) {
                    return value.equals(part) || value.startsWith(part + ".");
                }
                return value.startsWith(part);
            }
            if (isFirstFixed) {
                // 首段锚定在开头
                if (!value.startsWith(part)) {
                    return false;
                }
                pos = part.length();
                continue;
            }
            if (isLastFixed) {
                // 末段锚定在末尾（点边界模式锚在包名部分）
                if (!anchorValue.endsWith(part)) {
                    return false;
                }
                return anchorValue.length() - part.length() >= pos;
            }
            int idx = value.indexOf(part, pos);
            if (idx < 0) {
                return false;
            }
            pos = idx + part.length();
        }
        // 走到这里说明模式以 "*" 结尾，剩余部分任意
        return true;
    }

    /**
     * 取类名的"包名部分"（去掉最后一段类名）。
     *
     * <p>同时兼容点分（{@code a.b.C}）与斜杠内部名（{@code a/b/C}）。
     * 没有分隔符时（默认包）返回空串。
     */
    private static String packagePart(String className) {
        int dot = className.lastIndexOf('.');
        int slash = className.lastIndexOf('/');
        int cut = Math.max(dot, slash);
        return cut > 0 ? className.substring(0, cut) : "";
    }

    /**
     * 该包名是否命中排除规则（支持前缀与 {@code *} 通配）。
     *
     * <p>独立成方法是为了方便单测与日志诊断：用户看到"某个类没被插桩"时，
     * 可以直接比对类名与规则，而不必读整段插桩器代码。
     */
    public static boolean isExcluded(String dottedClassName, List<String> excludeRules) {
        if (dottedClassName == null || excludeRules == null || excludeRules.isEmpty()) {
            return false;
        }
        return startsWithAny(dottedClassName.replace('/', '.'), excludeRules);
    }

    private static boolean startsWithAny(String value, String[] prefixes) {
        for (String p : prefixes) {
            if (value.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** 逗号 / 分号 / 空格分隔均可。 */
    private static List<String> parse(String spec) {
        List<String> result = new ArrayList<>();
        if (spec == null || spec.trim().isEmpty()) {
            return result;
        }
        for (String raw : spec.split("[,;\\s]+")) {
            String p = raw.trim();
            if (!p.isEmpty()) {
                result.add(p);
            }
        }
        return result;
    }
}
