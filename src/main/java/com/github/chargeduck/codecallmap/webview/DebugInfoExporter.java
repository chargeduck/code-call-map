package com.github.chargeduck.codecallmap.webview;

import com.github.chargeduck.codecallmap.run.ProjectConfigStore;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 把前端调试所需的地址写到项目 {@code .callmap/debug-info.json}。
 *
 * <h2>用途</h2>
 * JCEF 打开 {@code ide.browser.jcef.debug.port} 后，会启动一个
 * DevTools Protocol 端点。用户可以用 Chrome 打开
 * {@code devtools://devtools/bundled/inspector.html?ws=...} 直接调试
 * 前端页面的 Console / Network，比在 IDE 日志里猜快得多。
 *
 * <p>但这个端口的<b>具体调试目标 ID</b>是运行时分配的，用户无法自己拼出来。
 * 所以插件在页面加载完成后主动探测一次，把可用的地址写成文件；
 * 用户打开文件、复制 URL 到 Chrome 即可。
 *
 * <h2>为什么写到 .callmap 而不是 IDE 配置目录</h2>
 * 跟着项目走：多项目同时开时不会互相覆盖，也方便用户直接找到。
 *
 * <h2>探测失败不算错误</h2>
 * 用户可能没开 Registry 开关，或端口被占用。这种情况下文件里会写明
 * "未启用"以及开启方法，而不是静默不生成 —— 用户至少知道该怎么做。
 */
final class DebugInfoExporter {

    private static final Logger LOG = Logger.getInstance(DebugInfoExporter.class);

    /** Registry 里 JCEF 调试端口的键名。 */
    private static final String PROP_DEBUG_PORT = "ide.browser.jcef.debug.port";

    /**
     * Registry 未配置时的兜底端口。
     *
     * <p>注意是 <b>9333</b>（四位），不是 93333（五位）。
     * 早期误写成 93333，导致探测永远连不上、debug-info.json 一直显示"未启用"。
     */
    private static final int DEFAULT_DEBUG_PORT = 9333;

    /** 探测端口时依次尝试的候选值（兜底端口 + 常见惯例端口）。 */
    private static final int[] FALLBACK_PORTS = {9333, 9222};

    private static final String FILE_NAME = "debug-info.json";

    private DebugInfoExporter() {
    }

    /**
     * 探测并写出调试信息文件。
     *
     * @param project 当前项目，用于确定输出目录
     * @return true 表示成功探测到 CDP 端点（调用方据此决定是否重试）
     */
    static boolean export(@Nullable Project project) {
        Path dir = ProjectConfigStore.dataDir(project);
        if (dir == null) {
            return false;
        }
        // 先试用户配置的端口，再依次试候选端口。
        // 只信"配置值"太脆弱：Registry 没开、或用户记错了数字，
        // 就会一直显示"未启用"而实际端口是通的。
        Target target = null;
        int port = resolveDebugPort();
        java.util.LinkedHashSet<Integer> candidates = new java.util.LinkedHashSet<>();
        candidates.add(port);
        for (int p : FALLBACK_PORTS) {
            candidates.add(p);
        }
        for (int p : candidates) {
            target = probe(p);
            if (target != null) {
                port = p;
                break;
            }
        }

        StringBuilder sb = new StringBuilder(512);

        sb.append("{\n");
        sb.append("  \"generatedAt\": \"").append(esc(now())).append("\",\n");
        sb.append("  \"debugPort\": ").append(port).append(",\n");

        if (target != null) {
            // 直接打到 idea.log，用户在 Help → Show Log 里就能看到，
            // 不必再去翻 .callmap/debug-info.json
            LOG.info("[CodeCallMap] ===== JCEF CDP 调试入口（端口 " + port + "）=====");
            LOG.info("[CodeCallMap] 目标页面: " + target.title);
            LOG.info("[CodeCallMap] 页面地址: " + target.pageUrlHint());
            LOG.info("[CodeCallMap] Chrome 打开(推荐): " + target.chromeDevtoolsUrl());
            LOG.info("[CodeCallMap] 目标列表(备用): " + target.listUrl());
            LOG.info("[CodeCallMap] WebSocket: " + target.webSocketDebuggerUrl);
            LOG.info("[CodeCallMap] =============================================");
            sb.append("  \"enabled\": true,\n");
            sb.append("  \"devtoolsFrontendUrl\": \"")
                    .append(esc(target.chromeDevtoolsUrl())).append("\",\n");
            // 备用入口：Chrome 有时会拦截 chrome-devtools:// 协议，
            // 这时打开这个 HTTP 地址，页面上有可点击的目标列表
            sb.append("  \"targetListUrl\": \"").append(esc(target.listUrl())).append("\",\n");
            sb.append("  \"webSocketDebuggerUrl\": \"")
                    .append(esc(target.webSocketDebuggerUrl)).append("\",\n");
            sb.append("  \"pageTitle\": \"").append(esc(target.title)).append("\",\n");
            sb.append("  \"pageUrl\": \"").append(esc(target.pageUrlHint())).append("\",\n");
        } else {
            LOG.info("[CodeCallMap] 未探测到 JCEF 调试端点。已尝试端口: " + candidates
                    + "。请确认 Registry 中 ide.browser.jcef.debug.port=" + port
                    + " 已开启，且工具窗口已打开（JCEF 启动后端口才可用）。");
            sb.append("  \"enabled\": false,\n");
            sb.append("  \"hint\": \"")
                    .append(esc("未探测到 JCEF 调试端点。请确认已在 Registry 中开启 "
                            + "ide.browser.jcef.debug.port=" + port
                            + " 并打开 CodeCallMap 工具窗口（JCEF 启动后端口才可用）。"))
                    .append("\",\n");
        }

        // 附上常见操作提示，省去用户查文档
        sb.append("  \"usage\": \"")
                .append(esc("用 Chrome 打开 devtoolsFrontendUrl，即可在 Console 中看到 [CCM] 前缀的日志；"
                        + "也可点页面右键菜单的 Inspect 打开 DevTools。"))
                .append("\",\n");
        sb.append("  \"logPrefix\": \"[CCM]\"\n");
        sb.append("}\n");

        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(FILE_NAME);
            Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
            LOG.info("[CodeCallMap] 调试信息已写入: " + file);
        } catch (IOException e) {
            LOG.warn("[CodeCallMap] 写入调试信息失败", e);
        }
        return target != null;
    }

    /**
     * 读取 JCEF 调试端口。
     *
     * <p>优先级：
     * <ol>
     *   <li>{@code ide.browser.jcef.debug.port} 系统属性（部分环境会暴露）</li>
     *   <li>Registry 里的同名键 —— 这是用户在 Registry 里实际设置的值，
     *       比猜测可靠</li>
     *   <li>{@link #DEFAULT_DEBUG_PORT} 兜底</li>
     * </ol>
     *
     * <p>探测失败不影响主流程：写进 debug-info.json 的信息只是给人看的。
     */
    private static int resolveDebugPort() {
        // 有些 IDE 版本会把 Registry 值暴露成系统属性
        String fromProp = System.getProperty(PROP_DEBUG_PORT);
        if (fromProp != null) {
            try {
                return Integer.parseInt(fromProp.trim());
            } catch (NumberFormatException ignored) {
                // 忽略，继续往下试
            }
        }
        // 直接读 Registry：用户在这里设置的值才是真正生效的端口
        try {
            int v = com.intellij.openapi.util.registry.Registry.intValue(PROP_DEBUG_PORT);
            if (v > 0) {
                return v;
            }
        } catch (Throwable ignored) {
            // Registry 在部分版本/上下文不可用，退回默认值
        }
        return DEFAULT_DEBUG_PORT;
    }

    /**
     * 请求 DevTools Protocol 的 {@code /json} 端点，取第一个页面目标。
     *
     * <p>JCEF 的调试端点与 Chrome 兼容，返回 JSON 数组，
     * 每项含 {@code webSocketDebuggerUrl} 等字段。
     * 这里只做极简解析（找字段值），不引入 JSON 库。
     */
    @Nullable
    private static Target probe(int port) {
        for (String host : new String[]{"127.0.0.1", "localhost"}) {
            try {
                URL url = new URL("http://" + host + ":" + port + "/json");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(800);
                conn.setReadTimeout(1200);
                conn.setRequestMethod("GET");
                if (conn.getResponseCode() != 200) {
                    continue;
                }
                String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                conn.disconnect();

                String ws = extract(body, "webSocketDebuggerUrl");
                if (ws == null || ws.isEmpty()) {
                    continue;
                }
                String title = extract(body, "title");
                String pageUrl = extract(body, "url");
                return new Target(port, ws,
                        title == null ? "" : title,
                        pageUrl == null ? "" : pageUrl);
            } catch (Throwable ignored) {
                // 换下一个 host 继续尝试
            }
        }
        return null;
    }

    /** 从 JSON 文本里取某个字符串字段的值（极简实现）。 */
    @Nullable
    private static String extract(String json, String key) {
        String k = "\"" + key + "\"";
        int idx = json.indexOf(k);
        if (idx < 0) {
            return null;
        }
        int colon = json.indexOf(':', idx + k.length());
        if (colon < 0) {
            return null;
        }
        int start = json.indexOf('"', colon + 1);
        if (start < 0) {
            return null;
        }
        int end = json.indexOf('"', start + 1);
        if (end < 0) {
            return null;
        }
        return json.substring(start + 1, end);
    }

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /** JSON 字符串转义。 */
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static final class Target {
        final int port;
        final String webSocketDebuggerUrl;
        final String title;
        final String url;

        Target(int port, String webSocketDebuggerUrl, String title, String url) {
            this.port = port;
            this.webSocketDebuggerUrl = webSocketDebuggerUrl;
            this.title = title;
            this.url = url;
        }

        /**
         * 可直接在 Chrome 地址栏打开的 DevTools 前端地址。
         *
         * <h2>为什么用本地 HTTP 而不是 chrome-devtools://</h2>
         * {@code chrome-devtools://} 是 Chrome 的<b>内部专用协议</b>，
         * 从外部（尤其从别的程序里复制）粘到地址栏时经常被当成搜索词，
         * 直接跳到 Google 搜索结果页 —— 实际经验中这条路不可靠。
         *
         * <p>而 JCEF 自己在调试端口上就提供了 DevTools 前端：
         * <pre>http://127.0.0.1:&lt;port&gt;/devtools/inspector.html?ws=...</pre>
         * 实测返回 200，是普通 HTTP 页面，粘到地址栏一定能打开，
         * 也不需要联网访问 chrome-devtools-frontend.appspot.com
         * （JCEF 在 /json/list 里返回的是那个外网地址，内网/离线环境会失败）。
         */
        @NotNull
        String chromeDevtoolsUrl() {
            // webSocketDebuggerUrl 形如 ws://127.0.0.1:9333/devtools/page/xxx
            // 它的 path 部分正是 DevTools 前端需要的 ws 参数值
            String wsParam = webSocketDebuggerUrl;
            int schemeEnd = wsParam.indexOf("://");
            if (schemeEnd > 0) {
                wsParam = wsParam.substring(schemeEnd + 3);   // 去掉 ws://
            }
            return "http://127.0.0.1:" + port + "/devtools/inspector.html?ws=" + wsParam;
        }

        /** 目标列表页（JSON）：确认"当前有几个页面目标、它们是什么"。 */
        @NotNull
        String listUrl() {
            return "http://127.0.0.1:" + port + "/json/list";
        }

        /**
         * 页面自身的真实 URL。
         *
         * <p>如果这里是 {@code about:blank} 或 {@code file:///jbcefbrowser/...#url=about:blank}，
         * 说明前端页面<b>根本没加载成功</b> —— 此时打开 DevTools 只会看到一个空白页，
         * 自然也就没有任何 {@code window.codeCallMapQuery}。
         * 这一条比地址本身更有诊断价值，所以单独暴露出来。
         */
        @NotNull
        String pageUrlHint() {
            if (url == null || url.isEmpty()) {
                return "(未知)";
            }
            if (url.contains("about:blank")) {
                return url + "   ← 页面未加载成功！前端脚本不存在，请检查 web/index.html 是否被正确加载";
            }
            return url;
        }
    }
}
