package com.github.chargeduck.codecallmap.run;

import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 包名探测器：从 Run 配置反推「应该插桩哪些包」，对应 Spring 的 {@code @ComponentScan} 语义。
 *
 * <p>用户不需要手打包名——插件在启动前就能从 IDEA 的索引里知道：
 * <ol>
 *   <li><b>首选：Main class 所在包</b>。Spring Boot 的约定就是启动类放在根包，
 *       其所在包及其子包构成整个应用的扫描范围。例如 Main class 是
 *       {@code com.mycompany.order.OrderApplication} → 首选包 {@code com.mycompany.order}；</li>
 *   <li><b>补充：向上回溯到「应用根包」</b>。若 Main class 是
 *       {@code com.mycompany.order.config.Boot}，真实业务包往往是 {@code com.mycompany}。
 *       本类会沿包路径向上回溯，找到仍然「像应用根」的层级（见 {@link #inferRootPackages}）；</li>
 *   <li><b>兜底：模块源码根下的顶层包</b>。当拿不到 Main class（或用户手填的类名解析失败）时，
 *       扫描模块的源码根目录，列出所有含 Java 源码的顶层包作为候选。</li>
 * </ol>
 *
 * <p>所有 Psi / 索引访问都是只读的；调用方需保证在 ReadAction 内执行
 * （{@link #probe} 内部已用 {@code ReadAction} 兜底）。
 */
public final class PackageProbe {

    /** 判定为「库 / 框架」的包前缀，不参与候选（避免把 Spring 自身扫进来）。 */
    private static final List<String> LIBRARY_PREFIXES = Arrays.asList(
            "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.", "kotlin.", "scala.",
            "org.springframework.", "org.apache.", "com.fasterxml.", "org.slf4j.", "ch.qos.",
            "com.intellij.", "org.jetbrains.", "org.junit.", "org.mockito.", "lombok.");

    /** 深层包名特征：这些包通常不是应用根包（而是子包），向上回溯时应排除。 */
    private static final List<String> NON_ROOT_MARKERS = Arrays.asList(
            "config", "configuration", "controller", "controllers", "service", "services",
            "dao", "mapper", "repository", "repositories", "entity", "entities", "model",
            "dto", "vo", "po", "util", "utils", "common", "component", "components",
            "filter", "interceptor", "aspect", "handler", "task", "job", "scheduled");

    /** 组织域名（包名第一段常见取值）。 */
    private static final java.util.Set<String> ORG_TLDS = new java.util.HashSet<>(Arrays.asList(
            "com", "org", "net", "cn", "io", "gov", "edu", "me", "dev", "app", "co"));

    /**
     * 通用二级域名。
     *
     * <p>出现在第二段说明是「域名倒置 + 补 com」的写法（如 {@code cn.com.企业}），
     * 此时前两段属于公共域名空间，不能作为插桩范围。
     */
    private static final java.util.Set<String> GENERIC_SLDS = new java.util.HashSet<>(Arrays.asList(
            "com", "co", "net", "org", "gov", "edu", "mil", "info", "biz", "name"));

    private PackageProbe() {
    }

    /** 探测结果：候选包名 + 说明信息（供 UI 展示「为什么给这些建议」）。 */
    public static final class Result {
        /** 推荐的包前缀列表，按可信度降序。 */
        public final List<String> packages;
        /** 人类可读的探测说明。 */
        public final String explanation;

        Result(List<String> packages, String explanation) {
            this.packages = packages;
            this.explanation = explanation;
        }

        public boolean isEmpty() {
            return packages.isEmpty();
        }
    }

    /**
     * 主入口：为给定配置探测候选包名。
     *
     * @param project   当前项目
     * @param config    Run 配置（用于读 Main class 与模块）
     * @param mainClass 显式传入的 Main class 全限定名；为 null 时尝试从 config 读
     */
    @NotNull
    public static Result probe(@Nullable Project project,
                               @Nullable ApplicationConfiguration config,
                               @Nullable String mainClass) {
        if (project == null || project.isDisposed()) {
            return new Result(new ArrayList<>(), "无可用项目上下文");
        }

        String effectiveMain = mainClass;
        if ((effectiveMain == null || effectiveMain.isBlank()) && config != null) {
            effectiveMain = config.getMainClassName();
        }

        Set<String> candidates = new LinkedHashSet<>();
        StringBuilder why = new StringBuilder();

        // 1) 从 Main class 推导
        if (effectiveMain != null && !effectiveMain.isBlank() && effectiveMain.contains(".")) {
            String mainPkg = packageOf(effectiveMain);
            if (!mainPkg.isEmpty() && !isLibraryPackage(mainPkg)) {
                candidates.add(mainPkg);
                why.append("Main class 所在包: ").append(mainPkg);
                // 向上回溯，找更像应用根的层级
                List<String> roots = inferRootPackages(mainPkg);
                if (!roots.isEmpty() && !roots.contains(mainPkg)) {
                    candidates.addAll(roots);
                    why.append("；向上回溯到: ").append(String.join(", ", roots));
                }
            }
        }

        // 2) 补充：模块源码根下的顶层包
        List<String> moduleTop = topLevelPackagesOfModule(config);
        if (!moduleTop.isEmpty()) {
            // 顶层候选放最后（可信度最低，但能兜住 Main class 缺失的情况）
            for (String p : moduleTop) {
                candidates.add(p);
            }
            if (why.length() > 0) {
                why.append("；");
            }
            why.append("模块源码根下的顶层包: ").append(String.join(", ", moduleTop));
        }

        List<String> result = new ArrayList<>(candidates);
        // 去掉被其它候选包含的子包（父包已覆盖子包，冗余）
        result = removeRedundant(result);
        if (result.isEmpty()) {
            why.append("未能探测到包名，请手工填写（例如 com.yourcompany）");
        }
        return new Result(result, why.toString());
    }

    /**
     * 沿包路径向上回溯，返回「更像应用根包」的层级。
     *
     * <p>策略：从 Main class 的包开始，逐级向上；只要当前层级是
     * {@link #NON_ROOT_MARKERS} 里的典型子包名，就继续向上；
     * 遇到第一个「不像子包」的层级时停下，并把它 + 必要的父层作为候选返回。
     *
     * <p><b>为什么不能一路回溯到顶层</b>：把 {@code com} / {@code cn} 当作插桩包名会命中
     * {@code com.fasterxml}、{@code com.sun} 等全部第三方库，插桩量爆炸且噪音无法使用。
     *
     * <p><b>为什么不能固定保留两段</b>：中文企业常用 {@code cn.com.企业名.项目名}
     * （域名倒置且公司再加一层 com），固定两段会得到 {@code cn.com} —— 这几乎是所有
     * 中国公司共用的前缀，覆盖范围过大。实测 {@code com.acme.demo} 被截成
     * {@code cn.com} 就是这个 bug。
     *
     * <p>因此改为<b>按顶层域名的实际形态动态决定最小深度</b>：
     * <ul>
     *   <li>第二段是通用二级域（{@code com} / {@code co} / {@code net} / {@code org} …）→
     *       说明是域名倒置写法，至少保留 3 段，如 {@code cn.com.acme}</li>
     *   <li>否则保持 2 段，如 {@code com.acme} / {@code io.github}</li>
     * </ul>
     *
     * <p>例：
     * <ul>
     *   <li>{@code com.acme.demo} → {@code [com.acme, com.acme.demo]}</li>
     *   <li>{@code com.acme.shop} → {@code [com.acme, com.acme.shop]}</li>
     *   <li>{@code com.acme.shop.config} → 上溯后同上</li>
     * </ul>
     */
    static List<String> inferRootPackages(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return new ArrayList<>();
        }
        String[] parts = packageName.split("\\.");
        if (parts.length == 0) {
            return new ArrayList<>();
        }

        // 从最深一层往前，跳过所有「子包特征」层级
        int anchor = parts.length - 1;
        while (anchor > 0 && isNonRootMarker(parts[anchor])) {
            anchor--;
        }

        // 动态决定最小保留深度，避免把包名截得过宽：
        //   cn.com.acme   → 第二段 com 是通用二级域 → 保留 3 段 → cn.com.acme
        //   com.acme.shop → 第二段 acme 不是         → 保留 2 段 → com.acme
        int minDepth = Math.min(minRootDepth(parts), parts.length);

        List<String> out = new ArrayList<>();
        // 从最浅（外层）到最深（Main class 所在层）依次加入，外层优先
        for (int depth = minDepth; depth <= anchor + 1; depth++) {
            out.add(join(parts, depth));
        }
        // 若 anchor 本身比 minDepth 更浅（例如包名只有 1 段），兜底返回原包名
        if (out.isEmpty()) {
            out.add(packageName);
        }
        // 确保 Main class 所在包一定在候选里（用户最直接的预期）
        if (!out.contains(packageName)) {
            out.add(packageName);
        }
        return out;
    }

    /**
     * 计算「最小保留段数」。
     *
     * <p>判断依据是第二段是否为通用二级域名（{@code com} / {@code co} / {@code net} /
     * {@code org} / {@code gov} / {@code edu} 等）。这类写法几乎都是
     * {@code 国家码.通用域.企业.项目}（如 {@code com.acme.demo}），
     * 只保留两段会得到 {@code cn.com} —— 所有中国公司共用，毫无区分度。
     *
     * <p>普通写法 {@code com.acme.shop} 第二段是企业名，保留两段即 {@code com.acme}，
     * 已经能准确圈定业务代码。
     */
    private static int minRootDepth(String[] parts) {
        if (parts.length <= 2) {
            return parts.length;
        }
        // 第一段是组织域名（com/org/cn/io…），第二段是通用二级域 → 域名倒置，保留 3 段
        boolean firstIsOrgTld = ORG_TLDS.contains(parts[0].toLowerCase());
        boolean secondIsGenericSld = GENERIC_SLDS.contains(parts[1].toLowerCase());
        if (firstIsOrgTld && secondIsGenericSld) {
            return 3;
        }
        return 2;
    }

    /** 扫描模块源码根，列出含 Java 源的顶层包（默认排除 test 目录）。 */
    static List<String> topLevelPackagesOfModule(@Nullable ApplicationConfiguration config) {
        List<String> out = new ArrayList<>();
        if (config == null) {
            return out;
        }
        try {
            // 优先用配置绑定的模块；取不到时退回该配置涉及的所有模块
            Module module = null;
            var configModule = config.getConfigurationModule();
            if (configModule != null) {
                module = configModule.getModule();
            }
            List<Module> modules = new ArrayList<>();
            if (module != null) {
                modules.add(module);
            } else {
                Module[] all = config.getModules();
                if (all != null) {
                    modules.addAll(Arrays.asList(all));
                }
            }
            for (Module m : modules) {
                collectTopLevelPackages(m, out);
            }
        } catch (Exception ignored) {
            // 索引未就绪等情况下返回空列表，由 UI 提示用户手填
        }
        return out;
    }

    /** 收集单个模块源码根下的顶层包。 */
    private static void collectTopLevelPackages(Module module, List<String> out) {
        if (module == null) {
            return;
        }
        ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
        VirtualFile[] sourceRoots = rootManager.getSourceRoots(false);
        for (VirtualFile root : sourceRoots) {
            if (root == null || !root.isDirectory()) {
                continue;
            }
            // sourceRoot 通常是 src/main/java，其它非 java 源根（kotlin/groovy）跳过
            String rootPath = root.getPath();
            if (!rootPath.endsWith("/java") && !rootPath.endsWith("\\java")) {
                continue;
            }
            VirtualFile[] children = root.getChildren();
            if (children == null) {
                continue;
            }
            for (VirtualFile child : children) {
                if (child == null || !child.isDirectory()) {
                    continue;
                }
                String top = child.getName();
                if (top == null || top.isBlank() || top.startsWith(".")) {
                    continue;
                }
                if (containsJavaSource(child, 4) && !out.contains(top)) {
                    out.add(top);
                }
            }
        }
    }

    /** 递归判断目录下（限定深度）是否存在 .java 文件。 */
    private static boolean containsJavaSource(VirtualFile dir, int maxDepth) {
        if (maxDepth <= 0) {
            return false;
        }
        Deque<VirtualFile> queue = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        queue.add(dir);
        depths.add(0);
        while (!queue.isEmpty()) {
            VirtualFile cur = queue.poll();
            int depth = depths.poll();
            VirtualFile[] kids = cur.getChildren();
            if (kids == null) {
                continue;
            }
            for (VirtualFile k : kids) {
                if (k == null) {
                    continue;
                }
                if (k.isDirectory()) {
                    if (depth + 1 < maxDepth) {
                        queue.add(k);
                        depths.add(depth + 1);
                    }
                } else if (k.getName().endsWith(".java")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 取全限定名的包部分。 */
    static String packageOf(String fqcn) {
        int idx = fqcn.lastIndexOf('.');
        return idx > 0 ? fqcn.substring(0, idx) : "";
    }

    /** 是否为库 / 框架包。 */
    static boolean isLibraryPackage(String pkg) {
        for (String prefix : LIBRARY_PREFIXES) {
            if (pkg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 包名的最后一段是否是典型「子包」特征。 */
    static boolean isNonRootMarker(String segment) {
        if (segment == null) {
            return false;
        }
        String lower = segment.toLowerCase();
        return NON_ROOT_MARKERS.contains(lower);
    }

    private static String join(String[] parts, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count && i < parts.length; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /**
     * 去掉冗余候选：若候选 A 是候选 B 的前缀（A 是父包），则 B 可删——
     * 因为插桩是按前缀匹配的，父包已经覆盖子包。
     */
    static List<String> removeRedundant(List<String> packages) {
        List<String> out = new ArrayList<>();
        for (String p : packages) {
            if (p == null || p.isBlank()) {
                continue;
            }
            boolean covered = false;
            for (String other : packages) {
                if (other == null || other.equals(p)) {
                    continue;
                }
                if (p.startsWith(other + ".")) {
                    covered = true;
                    break;
                }
            }
            if (!covered && !out.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** 供 UI 展示：把探测结果格式化为逗号分隔的字符串。 */
    public static String formatAsSpec(List<String> packages) {
        return String.join(",", packages);
    }

    /** 把逗号 / 分号 / 空格分隔的包名规格解析为列表（与 InstrumentFilter 的解析口径一致）。 */
    public static List<String> parseSpec(String spec) {
        List<String> out = new ArrayList<>();
        if (spec == null || spec.isBlank()) {
            return out;
        }
        for (String raw : spec.split("[,;\\s]+")) {
            String p = raw.trim();
            if (!p.isEmpty() && !out.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** 保留：供将来扩展「从 Spring 的 @ComponentScan 注解直接读取扫描包」使用。 */
    @SuppressWarnings("unused")
    private static OrderEnumerator unusedApiAnchor(Module module) {
        return ModuleRootManager.getInstance(module).orderEntries();
    }
}
