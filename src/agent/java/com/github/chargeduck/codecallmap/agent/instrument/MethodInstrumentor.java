package com.github.chargeduck.codecallmap.agent.instrument;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 方法级插桩器：在每个受控方法的入口 / 出口插入采集探针。
 *
 * <p>插桩形态（等价的 Java 伪码）：
 * <pre>{@code
 * public void foo(int a) {
 *     SpanBridge.Frame f = SpanBridge.methodEnter("com.Foo", "foo", "(I)V");
 *     try {
 *         // ... 原方法体 ...
 *     } finally {
 *         SpanBridge.methodExit(f);        // 正常返回时
 *         // 或 methodExitThrowable(f, e)  // 抛异常时
 *     }
 * }
 * }</pre>
 *
 * <h2>为什么用 ASM Tree API 而不是 AdviceAdapter</h2>
 * 探针要把 {@code Frame} 存进一个局部变量槽，而这里有两个坑：
 * <ol>
 *   <li>{@code AdviceAdapter.newLocal()} 只按 <b>方法描述符</b> 推算「空闲槽」，
 *       看不出方法体内部局部变量（如 {@code main} 里的 {@code OrderController c}）
 *       占了哪些槽。实测 {@code main(String[])} 的 newLocal 返回槽 1，
 *       而方法体的 {@code astore_1} 同样写槽 1 → Frame 被覆盖，
 *       出口探针读到的是 {@code OrderController} → VerifyError。</li>
 *   <li>{@code AdviceAdapter} 的父类 {@code LocalVariablesSorter} 还会自行重编号局部变量，
 *       与手工后移叠加后产生二次错位，极难调试。</li>
 * </ol>
 * 改用 Tree API 后顺序变得直观可控：<b>先把整个方法读进 {@link MethodNode}，
 * 此时 {@code node.maxLocals} 是真实值，直接把探针槽定在 {@code maxLocals}</b>
 * （该槽对原方法体一定空闲，无需移动任何已有局部变量），
 * 再在指令链表头尾插入探针，最后回写。
 */
public final class MethodInstrumentor {

    /** 探针桥接类（自身不插桩，避免无限递归）。 */
    private static final String BRIDGE = "com/github/chargeduck/codecallmap/agent/instrument/SpanBridge";

    private static final String FRAME_DESC = "L" + BRIDGE + "$Frame;";

    /** 探针相关指令会额外占用栈深与局部变量，留出余量。 */
    private static final int PROBE_EXTRA_STACK = 6;
    /**
     * 探针附加局部变量槽数。
     * <p>
     * 布局：{@code [frameSlot] [retSlot] [retSlot+1]}。
     * <ul>
     *   <li>{@code frameSlot}：进入探针创建的 Frame 引用；</li>
     *   <li>{@code retSlot}~{@code retSlot+1}：出口时暂存返回值。
     *       long / double 占 <b>2 个槽</b>，所以必须预留 3 个，
     *       只留 1 个会在插桩 {@code long}/{@code double} 返回的方法时
     *       覆盖掉后续局部变量，导致业务逻辑被悄悄改坏。</li>
     * </ul>
     */
    private static final int PROBE_EXTRA_LOCALS = 3;

    private MethodInstrumentor() {
    }

    /**
     * 改造一个类的字节码。
     *
     * @param classBytes 原始字节码
     * @param className  内部名（com/foo/Bar）
     * @param filter     过滤规则
     * @param loader     目标类的类加载器
     * @param active     是否处于采集激活态（false 时不改字节码）
     * @return 改造后的字节码；未改动时返回原数组
     */
    public static byte[] instrument(byte[] classBytes, String className,
                                    InstrumentFilter filter, ClassLoader loader,
                                    boolean active) {
        return instrument(classBytes, className, filter, loader, active, null);
    }

    /**
     * 插桩的重载版本：额外把「插了几个方法、哪些方法、什么角色」写进 {@code report}。
     *
     * <p>用途：让用户能看到 agent 到底动了哪些类和方法。
     * 排查"为什么某个方法没被记录"或"为什么启动变慢"时，
     * 一份明确的清单比反复猜测有用得多。
     *
     * @param report 非 null 时，插桩结果会被记录进去（可为 null）
     */
    public static byte[] instrument(byte[] classBytes, String className,
                                    InstrumentFilter filter, ClassLoader loader,
                                    boolean active, InstrumentReport report) {
        if (classBytes == null) {
            return null;
        }
        if (!active || !filter.shouldInstrument(className, loader, classBytes)) {
            return classBytes;
        }

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode node = new ClassNode(Opcodes.ASM9);
            reader.accept(node, 0);

            // 接口一律不插桩。
            //
            // 接口里除 abstract 方法外还有 default / static 方法，它们有方法体，
            // 会被下面的循环正常插桩 —— 但这会破坏接口的字节码结构，
            // JVM 在后续解析时抛
            //   LinkageError: attempted duplicate interface definition for Xxx
            // （实测 io.micrometer.core.instrument.config.validate.Validated 就是这么炸的）。
            //
            // 接口方法本身也很少是性能瓶颈，跳过它们的收益损失可以忽略，
            // 而风险是"整个应用起不来"，不值得冒险。
            if ((node.access & Opcodes.ACC_INTERFACE) != 0) {
                return classBytes;
            }

            // 该类是否命中"接口探针"（JDBC / Servlet 等按接口精确匹配的规则）。
            //
            // 接口探针与业务白名单是两个独立通道：
            //   - 业务白名单：整个类的方法都插，用于采集用户自己的代码；
            //   - 接口探针：只插规则指定的那几个方法，用于让第三方调用（SQL、HTTP）
            //     在调用链里可见，否则它们的耗时会错误地算到调用方身上。
            List<String> itfNames = classInterfaceNames(node);
            boolean probeCandidate = InterfaceProbes.mightMatch(itfNames);
            // 只有当"类因实现探针接口才被放行"时，才限制为只插探针方法。
            // 判断依据：不命中白名单 <b>且</b> 确实是探针候选。
            //
            // 若不加 probeCandidate 这个条件、把不命中白名单的类一律限制成
            // "只插探针方法"，白名单模式下的正常业务类会被误伤（一个方法都不插）；
            // 反过来若对探针候选类放开全插，等于按包放开第三方库，
            // 会重演 0.0.1.17~0.0.1.21 的 LinkageError。
            boolean probeOnly = probeCandidate && !filter.matchesIncludePackages(node.name);

            int instrumented = 0;
            for (MethodNode method : node.methods) {
                if (!InstrumentFilter.isInstrumentableMethod(method.name, method.desc, method.access)) {
                    continue;
                }
                if (method.instructions == null || method.instructions.size() == 0) {
                    continue;
                }
                InterfaceProbes.Rule probe = probeCandidate
                        ? InterfaceProbes.match(itfNames, method.name)
                        : null;
                if (probeOnly && probe == null) {
                    // 探针专属类：未命中规则的方法不动
                    continue;
                }
                instrumentMethod(node.name, method, probe);
                instrumented++;
                if (report != null) {
                    report.add(node.name, method.name, method.desc,
                            probe == null ? "business" : probe.actor);
                }
            }
            if (instrumented == 0) {
                return classBytes;
            }

            // COMPUTE_FRAMES：本插桩追加了局部变量槽并改动了栈深，
            // 原 StackMapTable 描述的是旧布局，必须重算（否则 VerifyError: chop_frame 不匹配）。
            // COMPUTE_FRAMES 需要解析被引用类，而 agent 的类加载器看不到业务类，
            // 因此配合 ClassLoaderProvidingWriter 用「目标类的加载器」来解析。
            ClassWriter writer = new ClassLoaderProvidingWriter(reader, ClassWriter.COMPUTE_FRAMES, loader);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            SpanBridge.logInstrumentFailure(className, t);
            return classBytes;
        }
    }

    /**
     * 用「目标类的加载器」解析被引用类，让 COMPUTE_FRAMES 能真正工作。
     *
     * <p>agent 自身的类加载器看不到业务类（业务类由应用类加载器加载），
     * 因此必须用传给 transformer 的那个 loader 去读 class 字节。
     * 读取失败时退化为 Object，保证不抛异常中断插桩。
     */
    private static final class ClassLoaderProvidingWriter extends ClassWriter {
        private final ClassLoader loader;

        ClassLoaderProvidingWriter(ClassReader reader, int flags, ClassLoader loader) {
            super(reader, flags);
            this.loader = loader;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                return super.getCommonSuperClass(type1, type2);
            } catch (Throwable ignored) {
                // 退化为最保守的公共父类，牺牲少量栈图精度换取不抛异常
                return "java/lang/Object";
            }
        }

        @Override
        protected ClassLoader getClassLoader() {
            return loader != null ? loader : super.getClassLoader();
        }
    }

    /** 给单个方法套上入口 / 出口探针。 */
    /**
     * 读取类直接实现的接口名（二进制名，点号形式）。
     *
     * <p>只看<b>直接</b>实现的接口：JDBC 驱动、Servlet 实现类都是直接
     * {@code implements Statement} / {@code implements Filter}，
     * 再往上翻父类链的收益很低，却要为每个类递归解析。
     *
     * <p>返回二进制名（点号）而非内部名，是为了与 {@link InterfaceProbes}
     * 里的规则表写法一致，避免每次比较都要转换。
     */
    private static List<String> classInterfaceNames(ClassNode node) {
        List<String> names = new ArrayList<>();
        // 类自身也要参与匹配 —— DispatcherServlet 这类具体类
        // 既不是接口、也不在用户业务包内，只能靠类名命中。
        if (node.name != null) {
            names.add(node.name.replace('/', '.'));
        }
        if (node.interfaces != null) {
            for (String itf : node.interfaces) {
                if (itf != null) {
                    names.add(itf.replace('/', '.'));
                }
            }
        }
        // 只收集【直接父类】，不递归向上：递归要靠反射加载类，
        // 在 ClassFileTransformer 里加载类极易引发类加载死锁
        // （JVM 规范明确禁止在 transform 中触发类加载）。
        if (node.superName != null) {
            names.add(node.superName.replace('/', '.'));
        }
        return names;
    }

    /**
     * 从方法参数里取出附加信息并压栈（SQL 或 URI）。
     *
     * <h2>取值规则</h2>
     * <ul>
     *   <li><b>DB 探针</b>：{@code execute(String sql)} 这类重载的第一个 String 参数就是 SQL；
     *       无参重载（如 {@code PreparedStatement.executeQuery()}，SQL 在 prepare 阶段已定）
     *       取不到，压 null —— 这类仍能记录耗时，只是没有语句文本。</li>
     *   <li><b>HTTP 探针</b>：{@code doFilter(req, resp, chain)} 取第 0 个参数
     *       （通常是无状态 proxy），调用 {@code getRequestURI()} 拿 URI。
     *       为避免在字节码里做复杂调用，这里退化为压 null ——
     *       HTTP 探针的价值主要是"标记这是一次请求入口"，
     *       URI 可通过业务侧的 Controller 参数补齐，不必在此强行取值。</li>
     * </ul>
     *
     * <p>实现上用 ASM 的 {@code Type} 解析描述符定位局部变量槽，
     * 而不是硬编码槽号 —— 非静态方法的槽位从 1 开始，
     * 且前面的参数可能是 long / double（占两个槽），必须精确计算。
     */
    /**
     * 把附加信息压栈（SQL / URI / 或 null）。
     *
     * <h2>为什么 HTTP 也在这里压参数</h2>
     * 同类工具常用 {@code message: '${[0].getMethod()} ${[0].getRequestURI()}'}
     * 模板在运行时反射取值。这里采用更直接的方案：
     * <b>把第 0 个实参（request 对象）原样压栈</b>，交给
     * {@link SpanBridge#describeHttpRequest} 在 Java 里反射取 method + URI。
     *
     * <p>不在字节码里拼 {@code getRequestURI()} 调用，原因是：
     * 参数类型在不同容器下可能是 {@code ServletRequest} / {@code HttpServletRequest}
     * 甚至是有状态 proxy，字节码层面做精确类型判断很脆弱，
     * 而反射失败可以安全降级为 null，不会让目标应用崩溃。
     */
    private static void pushDetailArgument(InsnList entry, MethodNode method, InterfaceProbes.Rule probe) {
        if (InterfaceProbes.ACTOR_DB.equals(probe.actor)) {
            // DB：execute(String sql) 的第一个 String 参数就是 SQL
            int slot = firstStringArgSlot(method);
            if (slot < 0) {
                entry.add(new InsnNode(Opcodes.ACONST_NULL));
            } else {
                entry.add(new VarInsnNode(Opcodes.ALOAD, slot));
            }
            return;
        }
        // HTTP：压第 0 个实参（request 对象），由运行时反射取 URI
        int slot = firstObjectArgSlot(method);
        if (slot < 0) {
            entry.add(new InsnNode(Opcodes.ACONST_NULL));
        } else {
            entry.add(new VarInsnNode(Opcodes.ALOAD, slot));
        }
    }

    /**
     * 定位第一个对象类型参数的局部变量槽（用于 HTTP 探针取 request）。
     *
     * @return 槽号；没有对象参数时返回 -1
     */
    private static int firstObjectArgSlot(MethodNode method) {
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        int slot = isStatic ? 0 : 1;
        try {
            for (Type t : Type.getArgumentTypes(method.desc)) {
                if (t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY) {
                    return slot;
                }
                slot += t.getSize();
            }
        } catch (Throwable ignored) {
            // 描述符异常时当作"取不到"
        }
        return -1;
    }

    /**
     * 定位第一个 {@code String} 参数的局部变量槽。
     *
     * @return 槽号；没有 String 参数时返回 -1
     */
    private static int firstStringArgSlot(MethodNode method) {
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        // 非静态方法槽 0 是 this
        int slot = isStatic ? 0 : 1;
        try {
            Type[] args = Type.getArgumentTypes(method.desc);
            for (Type t : args) {
                if (t.getSort() == Type.OBJECT && "java/lang/String".equals(t.getInternalName())) {
                    return slot;
                }
                // long / double 占两个槽
                slot += t.getSize();
            }
        } catch (Throwable ignored) {
            // 描述符异常时当作"取不到"
        }
        return -1;
    }

    /**
     * 把全部实参压成一个 {@code Object[]} 放到操作数栈上。
     *
     * <p>基本类型用 {@code Integer.valueOf(int)} 这类装箱方法，而不是
     * {@code INVOKESTATIC Integer.valueOf} 之外的手段 —— 这是最直接可靠的做法：
     * 字节码里对每个参数按类型选择对应的 {@code valueOf} 描述符。
     *
     * <p>无参方法压 {@code null}（省掉一个空数组对象分配）。
     */
    private static void pushArgsArray(InsnList entry, MethodNode method) {
        Type[] argTypes;
        try {
            argTypes = Type.getArgumentTypes(method.desc);
        } catch (Throwable t) {
            entry.add(new InsnNode(Opcodes.ACONST_NULL));
            return;
        }
        if (argTypes.length == 0) {
            entry.add(new InsnNode(Opcodes.ACONST_NULL));
            return;
        }
        entry.add(intInsn(argTypes.length));
        entry.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));

        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        int slot = isStatic ? 0 : 1;
        for (int i = 0; i < argTypes.length; i++) {
            Type t = argTypes[i];
            entry.add(new InsnNode(Opcodes.DUP));
            entry.add(intInsn(i));
            // 压参数值
            entry.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot));
            // 装箱：基本类型必须转成包装类，否则 AASTORE 会抛 ArrayStoreException
            boxIfPrimitive(entry, t);
            entry.add(new InsnNode(Opcodes.AASTORE));
            slot += t.getSize();
        }
    }

    /** 基本类型装箱；引用类型原样保留。 */
    private static void boxIfPrimitive(InsnList entry, Type t) {
        switch (t.getSort()) {
            case Type.BOOLEAN:
                box(entry, "java/lang/Boolean", "(Z)Ljava/lang/Boolean;");
                break;
            case Type.CHAR:
                box(entry, "java/lang/Character", "(C)Ljava/lang/Character;");
                break;
            case Type.BYTE:
                box(entry, "java/lang/Byte", "(B)Ljava/lang/Byte;");
                break;
            case Type.SHORT:
                box(entry, "java/lang/Short", "(S)Ljava/lang/Short;");
                break;
            case Type.INT:
                box(entry, "java/lang/Integer", "(I)Ljava/lang/Integer;");
                break;
            case Type.FLOAT:
                box(entry, "java/lang/Float", "(F)Ljava/lang/Float;");
                break;
            case Type.LONG:
                box(entry, "java/lang/Long", "(J)Ljava/lang/Long;");
                break;
            case Type.DOUBLE:
                box(entry, "java/lang/Double", "(D)Ljava/lang/Double;");
                break;
            default:
                // 对象/数组无需装箱
        }
    }

    private static void box(InsnList entry, String owner, String desc) {
        entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "valueOf", desc, false));
    }

    /**
     * 压入参数名数组。
     *
     * <p>参数名来自字节码的 {@code LocalVariableTable}，只在编译时带
     * {@code -parameters} 或 {@code -g} 才存在；取不到时压 null，
     * 序列化侧会退化成 {@code arg0} / {@code arg1}。
     */
    private static void pushParamNames(InsnList entry, MethodNode method) {
        List<String> names = new ArrayList<>();
        boolean complete = true;
        try {
            Type[] argTypes = Type.getArgumentTypes(method.desc);
            boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
            int slot = isStatic ? 0 : 1;
            Map<Integer, String> bySlot = new HashMap<>();
            if (method.localVariables != null) {
                for (org.objectweb.asm.tree.LocalVariableNode lv : method.localVariables) {
                    bySlot.put(lv.index, lv.name);
                }
            }
            for (Type t : argTypes) {
                String name = bySlot.get(slot);
                if (name == null) {
                    complete = false;
                    break;
                }
                names.add(name);
                slot += t.getSize();
            }
            if (argTypes.length == 0) {
                complete = false;
            }
        } catch (Throwable t) {
            complete = false;
        }
        if (!complete) {
            // 参数名不全就整个放弃：混着 null 和真名会让前端显示混乱
            entry.add(new InsnNode(Opcodes.ACONST_NULL));
            return;
        }
        entry.add(intInsn(names.size()));
        entry.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
        for (int i = 0; i < names.size(); i++) {
            entry.add(new InsnNode(Opcodes.DUP));
            entry.add(intInsn(i));
            entry.add(new LdcInsnNode(names.get(i)));
            entry.add(new InsnNode(Opcodes.AASTORE));
        }
    }

    /** 生成小整数压栈指令（0-5 用 ICONST_n，更大用 BIPUSH/SIPUSH/LDC）。 */
    private static AbstractInsnNode intInsn(int v) {
        if (v >= -1 && v <= 5) {
            return new InsnNode(Opcodes.ICONST_0 + v);
        }
        if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
            return new IntInsnNode(Opcodes.BIPUSH, v);
        }
        if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
            return new IntInsnNode(Opcodes.SIPUSH, v);
        }
        return new LdcInsnNode(v);
    }

    private static void instrumentMethod(String owner, MethodNode method) {
        instrumentMethod(owner, method, null);
    }

    private static void instrumentMethod(String owner, MethodNode method, InterfaceProbes.Rule probe) {
        String dottedClass = owner.replace('/', '.');
        // 探针槽定在真实 maxLocals 之后 —— 该槽对原方法体一定未被占用
        int frameSlot = method.maxLocals;

        // ---- 入口探针 ----
        InsnList entry = new InsnList();
        entry.add(new LdcInsnNode(dottedClass));
        entry.add(new LdcInsnNode(method.name));
        entry.add(new LdcInsnNode(method.desc));

        if (probe != null) {
            // 命中类型探针：把附加信息对象作为第 4 个参数压栈，
            // 再压 isDb / isEndpoint 两个布尔标记，由 SpanBridge 决定如何解读。
            // 取不到时压 null，探针侧按"无附加信息"处理。
            pushDetailArgument(entry, method, probe);
            boolean isDb = InterfaceProbes.ACTOR_DB.equals(probe.actor);
            entry.add(new InsnNode(isDb ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
            entry.add(new InsnNode(probe.isEndpoint ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
            entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "methodEnterProbe",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;ZZ)"
                            + FRAME_DESC, false));
        } else {
            // 普通业务方法：把实参打包成 Object[] 一起传进去，
            // 供出口时（确认要写盘后）序列化成 span.args
            pushArgsArray(entry, method);
            pushParamNames(entry, method);
            entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "methodEnterWithArgs",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
                            + "[Ljava/lang/Object;[Ljava/lang/String;)" + FRAME_DESC, false));
        }
        entry.add(new VarInsnNode(Opcodes.ASTORE, frameSlot));
        method.instructions.insert(entry);

        // ---- 出口探针：挂在每个 return / athrow 之前 ----
        // 先收集再插入，避免遍历时修改链表导致漏项
        List<AbstractInsnNode> exitPoints = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
                exitPoints.add(insn);
            } else if (op == Opcodes.ATHROW) {
                exitPoints.add(insn);
            }
        }
        for (AbstractInsnNode exit : exitPoints) {
            int op = exit.getOpcode();
            if (op == Opcodes.ATHROW) {
                method.instructions.insertBefore(exit, buildThrowProbe(frameSlot));
            } else if (op == Opcodes.RETURN) {
                // void 方法：栈上没有返回值，走原探针
                method.instructions.insertBefore(exit, buildVoidExitProbe(frameSlot));
            } else {
                // 有返回值：先把栈顶值暂存到 retSlot，插桩后再恢复，
                // 让随后的 return 指令仍能正常消费它
                method.instructions.insertBefore(exit, buildReturnProbe(frameSlot, frameSlot + 1, op));
            }
        }

        method.maxLocals += PROBE_EXTRA_LOCALS;
        method.maxStack += PROBE_EXTRA_STACK;
    }

    /**
     * 构造「抛异常」出口探针。
     * <p>
     * 栈顶是待抛出的异常，目标参数序为 {@code (Frame, Throwable)}：
     * <pre>
     * [exc] --DUP--&gt; [exc,exc] --ALOAD frame--&gt; [exc,frame] --SWAP--&gt; [frame,exc]
     * </pre>
     * 异常本身不采集（只记摘要），因为它可能持有整条构造栈。
     */
    private static InsnList buildThrowProbe(int frameSlot) {
        InsnList list = new InsnList();
        list.add(new InsnNode(Opcodes.DUP));
        list.add(new VarInsnNode(Opcodes.ALOAD, frameSlot));
        list.add(new InsnNode(Opcodes.SWAP));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "methodExitThrowable",
                "(" + FRAME_DESC + "Ljava/lang/Throwable;)V", false));
        return list;
    }

    /** 构造 void 方法出口探针（栈无返回值）。 */
    private static InsnList buildVoidExitProbe(int frameSlot) {
        InsnList list = new InsnList();
        list.add(new VarInsnNode(Opcodes.ALOAD, frameSlot));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "methodExit",
                "(" + FRAME_DESC + ")V", false));
        return list;
    }

    /**
     * 构造「有返回值」出口探针。
     *
     * <h2>指令序列为什么这样排</h2>
     * 执行到 {@code xRETURN} 之前，栈顶正是返回值。探针要做的是
     * "把返回值也传进去"，但传完必须<b>把栈恢复原样</b>，
     * 否则随后的 {@code xRETURN} 会取到错误的值或直接栈失衡。
     *
     * <p>做法是「暂存 → 插桩 → 恢复」：
     * <pre>
     * xSTORE retSlot          // 栈: []           值进临时槽
     * ALOAD  frameSlot        // 栈: [frame]
     * xLOAD  retSlot          // 栈: [frame, value]
     * [装箱 基本类型]          // 栈: [frame, Object]
     * INVOKESTATIC methodExitWithReturn(Frame,Object)V   // 栈: []
     * xLOAD  retSlot          // 栈: [value]      ← 恢复，供 xRETURN 消费
     * </pre>
     *
     * <h2>基本类型必须装箱</h2>
     * {@code methodExitWithReturn} 第二参数是 {@code Object}，
     * 直接把 {@code int} 压上去会导致 VerifyError。
     * 装箱用 {@code Integer.valueOf} 等静态方法，与 {@code pushArgsArray} 一致。
     *
     * @param frameSlot Frame 所在槽
     * @param retSlot   返回值暂存槽（long/double 占 retSlot 与 retSlot+1）
     * @param returnOp  原始 return 指令的 opcode（ARETURN/IRETURN/LRETURN/FRETURN/DRETURN）
     */
    private static InsnList buildReturnProbe(int frameSlot, int retSlot, int returnOp) {
        InsnList list = new InsnList();
        switch (returnOp) {
            case Opcodes.ARETURN:
                list.add(new VarInsnNode(Opcodes.ASTORE, retSlot));
                list.add(new VarInsnNode(Opcodes.ALOAD, frameSlot));
                list.add(new VarInsnNode(Opcodes.ALOAD, retSlot));
                break;
            case Opcodes.IRETURN:
                list.add(new VarInsnNode(Opcodes.ISTORE, retSlot));
                list.add(new VarInsnNode(Opcodes.ALOAD, frameSlot));
                list.add(new VarInsnNode(Opcodes.ILOAD, retSlot));
                list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                        "(I)Ljava/lang/Integer;", false));
                break;
            case Opcodes.LRETURN:
                list.add(new VarInsnNode(Opcodes.LSTORE, retSlot));
                list.add(new VarInsnNode(Opcodes.ALOAD, frameSlot));
                list.add(new VarInsnNode(Opcodes.LLOAD, retSlot));
                list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf",
                        "(J)Ljava/lang/Long;", false));
                break;
            case Opcodes.FRETURN:
                list.add(new VarInsnNode(Opcodes.FSTORE, retSlot));
                list.add(new VarInsnNode(Opcodes.ALOAD, frameSlot));
                list.add(new VarInsnNode(Opcodes.FLOAD, retSlot));
                list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf",
                        "(F)Ljava/lang/Float;", false));
                break;
            case Opcodes.DRETURN:
                list.add(new VarInsnNode(Opcodes.DSTORE, retSlot));
                list.add(new VarInsnNode(Opcodes.ALOAD, frameSlot));
                list.add(new VarInsnNode(Opcodes.DLOAD, retSlot));
                list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf",
                        "(D)Ljava/lang/Double;", false));
                break;
            default:
                // 不应出现：调用方已排除 RETURN / ATHROW
                return buildVoidExitProbe(frameSlot);
        }
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "methodExitWithReturn",
                "(" + FRAME_DESC + "Ljava/lang/Object;)V", false));
        // 恢复栈顶的返回值，供紧随其后的 xRETURN 使用
        switch (returnOp) {
            case Opcodes.ARETURN:
                list.add(new VarInsnNode(Opcodes.ALOAD, retSlot));
                break;
            case Opcodes.IRETURN:
                list.add(new VarInsnNode(Opcodes.ILOAD, retSlot));
                break;
            case Opcodes.LRETURN:
                list.add(new VarInsnNode(Opcodes.LLOAD, retSlot));
                break;
            case Opcodes.FRETURN:
                list.add(new VarInsnNode(Opcodes.FLOAD, retSlot));
                break;
            case Opcodes.DRETURN:
                list.add(new VarInsnNode(Opcodes.DLOAD, retSlot));
                break;
            default:
                break;
        }
        return list;
    }

    /** 保留：判断某个 Type 是否为 void（供将来排除空方法用）。 */
    @SuppressWarnings("unused")
    private static boolean isVoid(Type type) {
        return type.getSort() == Type.VOID;
    }
}
