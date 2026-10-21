package com.github.chargeduck.codecallmap.agent.instrument;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 插桩过滤规则的外部配置加载器。
 *
 * <h2>为什么要外置</h2>
 * 过滤规则曾经硬编码在 {@link InstrumentFilter} 里，改一条排除项就要改 Java、
 * 重新编译、重新打包、重新安装插件 —— 换个项目就可能要再来一遍。
 * 外置后，用户只需编辑一个 YAML 文件。
 *
 * <h2>加载优先级（越靠前越优先，命中即停）</h2>
 * <ol>
 *   <li>系统属性 {@code -Dcallmap.filter.file=<路径>} —— 显式指定，适合调试与 CI；</li>
 *   <li>{@code <项目根>/.callmap/instrument-filter.yml} —— 项目级，可提交到 git 让团队共享；</li>
 *   <li>{@code <用户主目录>/.codecallmap/instrument-filter.yml} —— 用户级，跨项目生效；</li>
 *   <li>jar 内置 {@code instrument-filter-default.yml} —— 兜底默认值。</li>
 * </ol>
 *
 * <h2>为什么优先项目级而不是用户级</h2>
 * 同一个开发者常在不同项目间切换，各项目的包结构、框架各不相同。
 * 项目级配置跟着代码走（还能进版本库），比一个全局配置更符合实际使用。
 *
 * <h2>格式</h2>
 * <pre>
 * # 只采集这些包前缀；留空或不写表示"除 exclude 外全部采集"
 * include:
 *   - com.acme.lhp
 *   - com.acme.frm
 *
 * # 永不插桩的包前缀
 * exclude:
 *   - org.apache.catalina
 *   - com.github.generated
 * </pre>
 *
 * <p>也接受简单文本格式（每行一个前缀，{@code #} 开头为注释），
 * 用 {@code include:} / {@code exclude:} 行切换段落。
 * 这样不熟悉 YAML 的人也能直接改。
 *
 * <h2>关于解析器</h2>
 * 这里<b>没有</b>引入 snakeyaml：agent 会被注入到用户进程，体积和额外依赖
 * 都是负担，而本配置只需要"两个字符串列表"，自己解析足够且可控。
 */
public final class InstrumentFilterConfig {

    /** 显式指定配置文件的系统属性。 */
    public static final String PROP_FILTER_FILE = "callmap.filter.file";

    /** 项目级配置的相对路径（相对项目根）。 */
    private static final String PROJECT_RELATIVE = ".callmap/instrument-filter.yml";

    /** 用户级配置（相对用户主目录）。 */
    private static final String USER_RELATIVE = ".codecallmap/instrument-filter.yml";

    /** jar 内置默认配置。 */
    private static final String BUNDLED = "/instrument-filter-default.yml";

    /** 采集白名单；空表示"除排除项外全部"。 */
    public final List<String> include;
    /** 永不插桩的黑名单。 */
    public final List<String> exclude;
    /** 实际生效的配置来源，用于日志排查。 */
    public final String source;

    private InstrumentFilterConfig(List<String> include, List<String> exclude, String source) {
        this.include = include;
        this.exclude = exclude;
        this.source = source;
    }

    /**
     * 按优先级加载配置。
     *
     * @param projectDir 项目根目录；为 null 时跳过项目级配置
     */
    public static InstrumentFilterConfig load(String projectDir) {
        // 1) 显式指定的文件
        String explicit = System.getProperty(PROP_FILTER_FILE);
        if (explicit != null && !explicit.trim().isEmpty()) {
            InstrumentFilterConfig c = readFile(Paths.get(explicit.trim()), "显式指定");
            if (c != null) {
                return c;
            }
        }

        // 2) 项目级
        if (projectDir != null && !projectDir.trim().isEmpty()) {
            Path p = Paths.get(projectDir).resolve(PROJECT_RELATIVE);
            InstrumentFilterConfig c = readFile(p, "项目级");
            if (c != null) {
                return c;
            }
        }

        // 3) 用户级
        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            Path p = Paths.get(home).resolve(USER_RELATIVE);
            InstrumentFilterConfig c = readFile(p, "用户级");
            if (c != null) {
                return c;
            }
        }

        // 4) 内置默认
        InstrumentFilterConfig c = readBundled();
        if (c != null) {
            return c;
        }
        // 全都没有时返回空规则：不限制 include，exclude 由 InstrumentFilter 的内置常量兜底
        return new InstrumentFilterConfig(new ArrayList<>(), new ArrayList<>(), "无（使用内置默认）");
    }

    /** 读取磁盘文件；不存在或解析失败返回 null，让调用方继续尝试下一优先级。 */
    private static InstrumentFilterConfig readFile(Path path, String sourceLabel) {
        try {
            if (!Files.isRegularFile(path)) {
                return null;
            }
            try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                return parse(r, sourceLabel + "：" + path);
            }
        } catch (Throwable ignored) {
            // 读不到就当没有，不影响插件主流程
            return null;
        }
    }

    /** 读取 jar 内默认配置。 */
    private static InstrumentFilterConfig readBundled() {
        try (InputStream in = InstrumentFilterConfig.class.getResourceAsStream(BUNDLED)) {
            if (in == null) {
                return null;
            }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(r, "内置默认");
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 解析配置文本。
     *
     * <p>同时兼容 YAML 列表与简单文本：两种写法本质都是
     * "段落标题 + 若干行前缀"，所以一套逻辑即可。
     */
    static InstrumentFilterConfig parse(Reader reader, String source) throws Exception {
        List<String> include = new ArrayList<>();
        List<String> exclude = new ArrayList<>();
        // 当前段落：0 = 未开始，1 = include，2 = exclude
        int section = 0;

        BufferedReader br = new BufferedReader(reader);
        String line;
        while ((line = br.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            // 段落标题
            if (trimmed.startsWith("include:")) {
                section = 1;
                addInline(trimmed.substring("include:".length()), include);
                continue;
            }
            if (trimmed.startsWith("exclude:")) {
                section = 2;
                addInline(trimmed.substring("exclude:".length()), exclude);
                continue;
            }
            // 列表项：去掉 YAML 的 "- " 前缀与可能的引号
            String item = trimmed.startsWith("-") ? trimmed.substring(1).trim() : trimmed;
            item = stripQuotes(item);
            if (item.isEmpty() || section == 0) {
                continue;
            }
            (section == 1 ? include : exclude).add(item);
        }
        return new InstrumentFilterConfig(include, exclude, source);
    }

    /** 支持 {@code include: [a, b]} 或 {@code exclude: a,b} 这种单行写法。 */
    private static void addInline(String rest, List<String> target) {
        String s = stripQuotes(rest.trim());
        if (s.isEmpty()) {
            return;
        }
        // 去掉 YAML 的方括号
        if (s.startsWith("[")) {
            s = s.substring(1);
        }
        if (s.endsWith("]")) {
            s = s.substring(0, s.length() - 1);
        }
        for (String part : s.split("[,;\\s]+")) {
            String p = stripQuotes(part.trim());
            if (!p.isEmpty()) {
                target.add(p);
            }
        }
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}
