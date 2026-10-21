package com.github.chargeduck.codecallmap.webview.bridge;

import com.github.chargeduck.codecallmap.agent.CallMapConfig;
import com.github.chargeduck.codecallmap.webview.WebViewPanel;
import lombok.extern.slf4j.Slf4j;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.github.chargeduck.codecallmap.run.CodeCallMapSettingsState;
import com.github.chargeduck.codecallmap.run.ProjectConfigStore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;

/**
 * Java ↔ JavaScript 通信桥。
 *
 * <h2>为什么不再使用 CefMessageRouter</h2>
 * 早期实现直接构造 {@code org.cef.browser.CefMessageRouter} 与
 * {@code CefMessageRouterHandlerAdapter} 来接收前端请求。这在 IDEA 2025.x 可用，
 * 但 <b>2026.x 移除了对 {@code org.cef.*} 的暴露</b>，插件一打开工具窗口就报
 * {@code NoClassDefFoundError: org/cef/handler/CefLoadHandler}。
 *
 * <p>现在改由 {@link com.intellij.ui.jcef.JBCefJSQuery} 承载通道（见 {@code WebViewPanel}），
 * 本类退化为纯业务处理器：{@link #handleRequest(String)} 收一个请求字符串、返回一个
 * JSON 响应字符串，不接触任何 JCEF 类型，因而与 IDE 版本解耦。
 *
 * <p>支持命令：
 * <ul>
 *   <li>{@code ready} - 桥接就绪探测</li>
 *   <li>{@code loadSpans} - 读取 {@code callmap.output.dir}（默认 {@code <user.dir>/.callmap}）下全部 JSONL 事件</li>
 *   <li>{@code loadSample} - 返回内置示例数据</li>
 *   <li>{@code getOutputDir} - 返回当前数据目录</li>
 *   <li>{@code openFile?file=xx&amp;line=42} / {@code openMethod?className=xx&amp;method=yy} - 跳转源码</li>
 * </ul>
 */
@Slf4j
public class JSBridge {

    private final WebViewPanel panel;

    private volatile boolean registered = false;

    /** 定位源码所需的 Project（由 WebViewPanel.init 注入）。 */
    private volatile Project project;

    /**
     * 编辑器覆盖层：把录制数据画进代码行。
     * <p>
     * 懒创建 —— 大部分用户只用时序图不跳源码，没必要一打开工具窗口就建它。
     */
    private volatile com.github.chargeduck.codecallmap.webview.CcmEditorOverlay overlay;

    /**
     * 最近一次 {@code loadSpans} 读取的会话文件。
     * <p>
     * {@code openMethod} 需要它才能回到同一份录制里查"这个方法被调了几次、每次传了什么"。
     * 不在 openMethod 时重新解析 sessionId，是因为前端切会话后
     * 显示的图与用户点击的那条记录必然来自同一份文件 —— 记住它比再传一次更可靠。
     */
    private volatile Path currentSessionFile;

    public JSBridge(WebViewPanel panel) {
        this.panel = panel;
    }

    public boolean isRegistered() {
        return registered;
    }

    /** 由 WebViewPanel 在注入桥接脚本后调用。 */
    public void markRegistered() {
        this.registered = true;
    }

    /** 注入 Project，用于前端跳转源码定位。 */
    public void setProject(Project project) {
        this.project = project;
    }

    /**
     * 处理前端请求，返回 JSON 响应字符串。
     *
     * <p>这是本类唯一的对外入口，由 {@code WebViewPanel} 中的
     * {@code JBCefJSQuery} handler 调用。任何异常都在内部消化并转为错误 JSON，
     * 避免把异常抛回 JCEF 导致渲染进程卡死。
     */
    public String handleRequest(String request) {
        String cmd = request == null ? "" : request.trim();
        // 与前端 console 日志对照：前端打"→ 请求"，这里打"收到请求"，
        // 若前端有而这里没有，问题在 cefQuery 通道；两边都有但结果不对，才看业务逻辑。
        log.info("[JSBridge] 收到请求: " + (cmd.length() > 120 ? cmd.substring(0, 120) + "..." : cmd));
        try {
            if ("ready".equals(cmd)) {
                return "{\"ok\":true,\"bridge\":\"codeCallMap\"}";
            }
            if ("loadSpans".equals(cmd)) {
                return loadSpansJson(null);
            }
            // 前端切会话：loadSpans:<sessionId>，只读该次录制
            if (cmd.startsWith("loadSpans:")) {
                return loadSpansJson(cmd.substring("loadSpans:".length()).trim());
            }
            if ("listSessions".equals(cmd)) {
                return listSessionsJson();
            }
            if ("loadSample".equals(cmd)) {
                return sampleSpansJson();
            }
            if ("getOutputDir".equals(cmd)) {
                return "{\"outputDir\":" + jsonString(outputDir().toString()) + "}";
            }
            // 前端渲染统计时需要知道"多少毫秒算慢"。
            // 这是展示层判定，与采集过滤（minDurationMs）是两回事，
            // 所以单独下发，不塞进 agent 参数。
            if ("getSettings".equals(cmd)) {
                return getSettingsJson();
            }
            if (cmd.startsWith("openMethod")) {
                return handleOpenMethod(cmd);
            }
            if (cmd.startsWith("openFile")) {
                return handleOpenFile(cmd);
            }
            return "{\"ok\":false,\"error\":\"unknown command: " + jsonString(cmd) + "\"}";
        } catch (Throwable e) {
            log.error("[JSBridge] 处理请求失败, cmd={}", cmd, e);
            return "{\"ok\":false,\"error\":" + jsonString(String.valueOf(e.getMessage())) + "}";
        }
    }

    /**
     * 读取调用链事件并拼接为 JSON 数组。
     *
     * @param sessionId 会话标识（文件名去掉 .jsonl）；为空表示读最新一次录制。
     *                  agent 每次启动都会新开一个文件，所以「一次运行 = 一个会话」。
     */
    private String loadSpansJson(String sessionId) {
        Path dir = outputDir();
        if (!Files.isDirectory(dir)) {
            return "{\"ok\":true,\"count\":0,\"outputDir\":" + jsonString(dir.toString())
                    + ",\"spans\":[]}";
        }
        // 选定要读的文件：指定会话则精确定位，否则取最新的那个
        Path target = resolveSessionFile(dir, sessionId);
        if (target == null) {
            return "{\"ok\":true,\"count\":0,\"outputDir\":" + jsonString(dir.toString())
                    + ",\"session\":" + jsonString(sessionId == null ? "" : sessionId)
                    + ",\"spans\":[]}";
        }

        StringBuilder sb = new StringBuilder(1 << 20);
        int count = 0;
        try {
            for (String line : Files.readAllLines(target, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                if (count == 0) {
                    sb.append('[');
                } else {
                    sb.append(',');
                }
                sb.append(inlineArgs(line, target.getParent()));
                count++;
                if (count >= MAX_RECORDS) {
                    break;
                }
            }
            if (count == 0) {
                sb.append('[');
            }
            sb.append(']');
        } catch (IOException e) {
            log.warn("[JSBridge] 读取调用链数据失败, file={}", target, e);
            return "{\"ok\":false,\"error\":" + jsonString("读取调用链数据失败: " + e.getMessage()) + "}";
        }
        String sid = target.getFileName().toString();
        if (sid.endsWith(".jsonl")) {
            sid = sid.substring(0, sid.length() - ".jsonl".length());
        }
        // 记住这次读的会话文件：openMethod 要靠它回到同一份录制里找调用记录
        currentSessionFile = target;
        return "{\"ok\":true,\"count\":" + count + ",\"outputDir\":" + jsonString(dir.toString())
                + ",\"session\":" + jsonString(sid)
                + ",\"spans\":" + sb + "}";
    }

    /**
     * 定位会话文件。
     *
     * @param sessionId 会话标识；为空取最新
     * @return 文件路径；不存在返回 null
     */
    private static Path resolveSessionFile(Path dir, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            // 取"最新"的那个：先按 mtime，再按文件名兜底。
            // 文件名里带 yyyyMMdd-HHmmss-SSS 时间戳，比 mtime 更精确 ——
            // 两次录制落在同一毫秒时 mtime 相同，只靠它会导致"最新"不确定。
            try (Stream<Path> stream = Files.list(dir)) {
                return stream
                        .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                        .max(Comparator.comparingLong((Path p) -> p.toFile().lastModified())
                                .thenComparing(p -> p.getFileName().toString()))
                        .orElse(null);
            } catch (IOException e) {
                return null;
            }
        }
        // 指定会话：文件名即 <sessionId>.jsonl
        Path candidate = dir.resolve(sessionId + ".jsonl");
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    /**
     * 列出全部录制会话，供前端「会话」下拉使用。
     *
     * <p>返回按时间倒序的数组，元素含 id / label / sizeBytes / lastModified。
     */
    private String listSessionsJson() {
        Path dir = outputDir();
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"ok\":true,\"outputDir\":").append(jsonString(dir.toString())).append(",\"sessions\":[");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .toList();
        } catch (IOException e) {
            // 目录不存在时按空列表返回，但要记日志 ——
            // "0 个会话"最常见的原因就是目录找错了，日志里必须能看到实际路径
            log.info("[JSBridge] 扫描会话目录失败（可能不存在）: " + dir + "，原因: " + e.getMessage());
        }
        log.info("[JSBridge] 会话扫描结果: 目录=" + dir + "，jsonl 数量=" + files.size());
        for (int i = 0; i < files.size(); i++) {
            Path p = files.get(i);
            String name = p.getFileName().toString();
            String id = name.endsWith(".jsonl")
                    ? name.substring(0, name.length() - ".jsonl".length())
                    : name;
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(jsonString(id))
                    .append(",\"sizeBytes\":").append(p.toFile().length())
                    .append(",\"lastModified\":").append(p.toFile().lastModified())
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    /** 内置示例数据（演示时序图 / 瀑布图用）。 */
    /**
     * 把前端渲染需要的设置项下发过去。
     *
     * <p>目前只有 {@code slowThresholdMs}（慢调用阈值）。
     * 注意它<b>不是</b>采集过滤用的 {@code minDurationMs} ——
     * 后者控制"录不录"，本项控制"算不算慢"，两者互不影响。
     */
    private String getSettingsJson() {
        CodeCallMapSettingsState s = CodeCallMapSettingsState.getInstance();
        return "{\"ok\":true"
                + ",\"slowThresholdMs\":" + s.slowThresholdMs
                + ",\"minDurationMs\":" + s.minDurationMs
                + ",\"sampleRate\":" + s.sampleRate
                + "}";
    }

    private String sampleSpansJson() {
        String[] sample = {
                "{\"type\":\"span_start\",\"traceId\":\"demo-1\",\"spanId\":\"s0\",\"parentId\":null,\"ts\":1700000000000,\"thread\":\"http-nio-8080-exec-1\",\"className\":\"OrderController\",\"method\":\"create\",\"args\":{\"orderId\":10086},\"path\":\"/api/order/create\"}",
                "{\"type\":\"span_start\",\"traceId\":\"demo-1\",\"spanId\":\"s1\",\"parentId\":\"s0\",\"ts\":1700000000012,\"thread\":\"http-nio-8080-exec-1\",\"className\":\"OrderService\",\"method\":\"createOrder\",\"args\":{\"orderId\":10086}}",
                "{\"type\":\"span_end\",\"traceId\":\"demo-1\",\"spanId\":\"s1\",\"parentId\":\"s0\",\"ts\":1700000000045,\"thread\":\"http-nio-8080-exec-1\",\"className\":\"OrderService\",\"method\":\"createOrder\",\"durationMs\":33,\"sql\":\"INSERT INTO t_order (id, amount) VALUES (10086, 99.0)\"}",
                "{\"type\":\"span_start\",\"traceId\":\"demo-1\",\"spanId\":\"s2\",\"parentId\":\"s0\",\"ts\":1700000000050,\"thread\":\"http-nio-8080-exec-1\",\"className\":\"StockClient\",\"method\":\"deduct\",\"args\":{\"skuId\":9001,\"qty\":1}}",
                "{\"type\":\"span_end\",\"traceId\":\"demo-1\",\"spanId\":\"s2\",\"parentId\":\"s0\",\"ts\":1700000000390,\"thread\":\"http-nio-8080-exec-1\",\"className\":\"StockClient\",\"method\":\"deduct\",\"durationMs\":340,\"exception\":\"java.net.SocketTimeoutException: Read timed out\",\"sql\":\"SELECT ... FROM t_stock WHERE sku_id = 9001\"}",
                "{\"type\":\"span_end\",\"traceId\":\"demo-1\",\"spanId\":\"s0\",\"parentId\":null,\"ts\":1700000000410,\"thread\":\"http-nio-8080-exec-1\",\"className\":\"OrderController\",\"method\":\"create\",\"durationMs\":410,\"path\":\"/api/order/create\"}",
                "{\"type\":\"trace_summary\",\"traceId\":\"demo-1\",\"spanId\":\"s0\",\"parentId\":null,\"ts\":1700000000412,\"className\":\"OrderController\",\"method\":\"create\",\"durationMs\":410,\"path\":\"/api/order/create\",\"thread\":\"http-nio-8080-exec-1\"}"
        };
        StringBuilder sb = new StringBuilder(4096);
        sb.append('[');
        for (int i = 0; i < sample.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(sample[i]);
        }
        sb.append(']');
        return "{\"ok\":true,\"count\":" + sample.length + ",\"outputDir\":" + jsonString("(示例数据)")
                + ",\"spans\":" + sb + "}";
    }

    // ---------- 前端跳转源码 ----------

    /** 打开源码文件并定位到行。前端优先携带 agent 采集的 sourceFile/lineNumber；class 缺失时按文件名搜索。 */
    private String handleOpenFile(String cmd) {
        Project p = project;
        if (p == null || p.isDisposed()) {
            return "{\"ok\":false,\"error\":\"no active project\"}";
        }
        Map<String, String> params = parseParams(cmd);
        String fileName = params.get("file");
        String className = params.get("className");
        String method = params.get("method");
        Integer line = parseLine(params.get("line"));
        if (fileName == null || fileName.isBlank()) {
            return "{\"ok\":false,\"error\":\"missing file\"}";
        }
        if (line == null && className != null && !className.isBlank() && method != null && !method.isBlank()) {
            Integer methodLine = findMethodLine(className, method);
            if (methodLine != null) {
                line = methodLine;
            }
        }
        if (line == null) {
            line = 1;
        }
        VirtualFile vf = ReadAction.compute(() -> {
            if (className != null && !className.isBlank()) {
                PsiClass c = JavaPsiFacade.getInstance(p).findClass(className, GlobalSearchScope.allScope(p));
                if (c != null) {
                    PsiFile f = c.getContainingFile();
                    if (f != null && f.getVirtualFile() != null) {
                        return f.getVirtualFile();
                    }
                }
            }
            var files = FilenameIndex.getVirtualFilesByName(p, fileName, GlobalSearchScope.allScope(p));
            return files.isEmpty() ? null : files.iterator().next();
        });
        if (vf == null) {
            return "{\"ok\":false,\"error\":\"source file not found: " + jsonString(fileName) + "\"}";
        }
        return openInEditor(p, vf, line);
    }

    /** 按类名+方法名定位并打开（Psi 兜底，无 sourceFile 信息时使用）。 */
    private String handleOpenMethod(String cmd) {
        Project p = project;
        if (p == null || p.isDisposed()) {
            return "{\"ok\":false,\"error\":\"no active project\"}";
        }
        Map<String, String> params = parseParams(cmd);
        String className = params.get("className");
        String method = params.get("method");
        if (className == null || className.isBlank() || method == null || method.isBlank()) {
            return "{\"ok\":false,\"error\":\"missing className/method\"}";
        }
        Integer line = findMethodLine(className, method);
        if (line == null) {
            return "{\"ok\":false,\"error\":\"method not found: " + jsonString(className + "#" + method) + "\"}";
        }
        VirtualFile vf = ReadAction.compute(() -> {
            PsiClass c = JavaPsiFacade.getInstance(p).findClass(className, GlobalSearchScope.allScope(p));
            if (c == null) {
                return null;
            }
            PsiFile f = c.getContainingFile();
            return f != null ? f.getVirtualFile() : null;
        });
        if (vf == null) {
            return "{\"ok\":false,\"error\":\"source file not found for " + jsonString(className) + "\"}";
        }
        // 先跳转（保证用户一定看到源码），再由覆盖层补上录制数据的 inlay。
        // 顺序不能反：绘制 inlay 需要编辑器已经打开，且失败时不该影响跳转结果。
        String openResult = openInEditor(p, vf, line);
        try {
            attachRecordingOverlay(p, className, method, params.get("spanId"));
        } catch (Throwable e) {
            // 内联提示是增强功能，任何问题都不能影响"打开源码"这个主功能
            log.warn("[JSBridge] 绘制录制数据失败: {}", e.getMessage());
        }
        return openResult;
    }

    /**
     * 把该方法的录制现场画进编辑器（参数值 + 行尾汇总 + gutter 图标）。
     *
     * @param p         项目
     * @param className 全限定类名
     * @param method    方法名
     * @param spanId    用户点击的那次调用；为空表示取第一次
     */
    private void attachRecordingOverlay(Project p, String className, String method, String spanId) {
        Path jsonl = currentSessionFile;
        if (jsonl == null) {
            log.info("[JSBridge] 尚未加载过录制数据，跳过内联提示");
            return;
        }
        List<com.github.chargeduck.codecallmap.webview.CcmCallRecord> records =
                com.github.chargeduck.codecallmap.webview.CcmCallRecordReader.read(
                        jsonl.getParent(), jsonl, className, method);
        if (records.isEmpty()) {
            log.info("[JSBridge] {}#{} 没有录制记录，跳过内联提示", className, method);
            return;
        }
        // 定位用户点的到底是第几次调用：前端传 spanId 最准确；
        // 拿不到时退回第一次（用户明确选择过"默认显示第一次"）
        int index = 0;
        if (spanId != null && !spanId.isBlank()) {
            for (int i = 0; i < records.size(); i++) {
                if (spanId.equals(records.get(i).spanId())) {
                    index = i;
                    break;
                }
            }
        }
        com.github.chargeduck.codecallmap.webview.CcmEditorOverlay o = overlay;
        if (o == null) {
            synchronized (this) {
                o = overlay;
                if (o == null) {
                    o = new com.github.chargeduck.codecallmap.webview.CcmEditorOverlay();
                    overlay = o;
                }
            }
        }
        o.show(p, className, method, records, index);
    }

    /** 打开编辑器并导航到指定行（1-based），在 EDT 上执行。 */
    private String openInEditor(Project p, VirtualFile vf, int line) {
        int zeroBased = Math.max(0, line - 1);
        OpenFileDescriptor descriptor = new OpenFileDescriptor(p, vf, zeroBased, 0, true);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (p.isDisposed()) {
                return;
            }
            FileEditorManager.getInstance(p).openEditor(descriptor, true);
        });
        return "{\"ok\":true,\"file\":" + jsonString(vf.getPath()) + ",\"line\":" + line + "}";
    }

    /** 按类名+方法名定位方法声明起始行（1-based），找不到返回 null。 */
    private Integer findMethodLine(String className, String methodName) {
        Project p = project;
        if (p == null || p.isDisposed()) {
            return null;
        }
        return ReadAction.compute(() -> {
            PsiClass c = JavaPsiFacade.getInstance(p).findClass(className, GlobalSearchScope.allScope(p));
            if (c == null) {
                return null;
            }
            var doc = PsiDocumentManager.getInstance(p).getDocument(c.getContainingFile());
            if (doc == null) {
                return null;
            }
            for (PsiMethod m : c.findMethodsByName(methodName, true)) {
                if (m.getTextRange() != null) {
                    return doc.getLineNumber(m.getTextOffset()) + 1;
                }
            }
            return null;
        });
    }

    /** 解析 openFile?file=xx&line=42 形式的请求参数。 */
    private static Map<String, String> parseParams(String cmd) {
        Map<String, String> map = new HashMap<>();
        int q = cmd.indexOf('?');
        if (q < 0) {
            return map;
        }
        String query = cmd.substring(q + 1);
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                map.put(k, URLDecoder.decode(v, StandardCharsets.UTF_8));
            } catch (Exception ex) {
                map.put(k, v);
            }
        }
        return map;
    }

    private static Integer parseLine(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析调用链数据目录。
     *
     * <h2>为什么不能直接用 {@code CallMapConfig.fromSystemProperties()}</h2>
     * 那个方法基于 {@code user.dir}，是<b>被注入 agent 的业务进程</b>的工作目录。
     * 而本类跑在 IDE 进程里，{@code user.dir} 是 IDE 的安装目录或启动目录，
     * 与业务项目的 {@code .callmap} 毫无关系 —— 结果是
     * "jsonl 明明已经生成了，面板却一个会话都列不出来"。
     *
     * <h2>优先级</h2>
     * <ol>
     *   <li>系统属性 {@code callmap.output.dir}（用户显式指定）；</li>
     *   <li><b>项目配置里的 outputDir</b>（{@code .callmap/callmap-config.yml}）——
     *       这是"即使这次没用 CCM 跑过，也能回看历史录制"的关键；</li>
     *   <li>{@code <当前项目根>/.callmap}（默认位置）；</li>
     *   <li>{@code user.dir/.callmap}（无项目上下文时兜底）。</li>
     * </ol>
     */
    private Path outputDir() {
        String configured = System.getProperty(CallMapConfig.PROP_OUTPUT_DIR);
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured.trim());
        }
        Project p = contextProject();
        // 项目配置文件里记录的目录：换机器 / 换 IDE 后仍能定位到历史录制
        String saved = ProjectConfigStore.savedOutputDir(p);
        if (saved != null) {
            Path savedPath = Paths.get(saved);
            if (Files.isDirectory(savedPath)) {
                return savedPath;
            }
        }
        if (p != null && !p.isDisposed() && p.getBasePath() != null) {
            return Paths.get(p.getBasePath(), ProjectConfigStore.DIR_NAME);
        }
        return Paths.get(System.getProperty("user.dir", "."), ProjectConfigStore.DIR_NAME);
    }

    /**
     * 取当前用于定位数据的项目。
     *
     * <p>优先用创建本桥接时注入的 project；注入失败时退回 IDE 当前打开的项目，
     * 保证多模块 / 切换项目后仍能找到正确的 {@code .callmap}。
     */
    private Project contextProject() {
        Project p = this.project;
        if (p != null && !p.isDisposed()) {
            return p;
        }
        try {
            Project[] open = ProjectManager.getInstance().getOpenProjects();
            return open.length > 0 ? open[0] : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Java→JS 推送：前端通过 CustomEvent 接收（预留，用于实时增量刷新）。 */
    public void push(String payload) {
        if (!registered) {
            return;
        }
        panel.executeJS("window.codeCallMapBridge && window.codeCallMapBridge.push(" + payload + ");");
    }

    public void dispose() {
        registered = false;
    }

    private static final int MAX_RECORDS = 50000;

    /**
     * 把 span 行里的 {@code argsFile} 引用内联成真正的 {@code args} 对象。
     *
     * <h2>为什么要内联</h2>
     * agent 侧把入参写到 {@code <会话目录>/args/<spanId>.json}，
     * JSONL 里只有引用。前端表格要直接展示入参，所以这里读取文件、
     * 把内容填回 {@code args} 字段，同时保留 {@code argsFile} 便于回放定位。
     *
     * <h2>容错</h2>
     * 文件缺失/内容不是合法 JSON 时<b>不抛异常</b>，只把 {@code args}
     * 置为一个说明性对象，页面其余数据照常显示 —— 一条坏数据不能拖垮整次加载。
     */
    private String inlineArgs(String line, Path sessionDir) {
        // 入参与返回值都是「JSONL 里只存引用、内容在独立文件」的同一套设计，
        // 前端表格要直接展示，所以这里统一把两个字段都内联进来。
        String withArgs = inlineOne(line, sessionDir, "argsFile", "args");
        return inlineOne(withArgs, sessionDir, "retFile", "ret");
    }

    /**
     * 把外置文件的内容内联回指定字段。
     *
     * @param line       JSONL 一行
     * @param sessionDir 会话目录
     * @param fileField JSONL 中记录"相对路径"的字段名（如 {@code argsFile}）
     * @param dataField 要内联成的字段名（如 {@code args}）
     * @return 处理后的行；无需处理或失败时原样返回
     */
    private String inlineOne(String line, Path sessionDir, String fileField, String dataField) {
        String marker = "\"" + fileField + "\":\"";
        int idx = line.indexOf(marker);
        if (idx < 0) {
            return line;
        }
        try {
            int start = idx + marker.length();
            int end = line.indexOf('"', start);
            if (end < 0) {
                return line;
            }
            String rel = line.substring(start, end);
            Path file = sessionDir.resolve(rel).normalize();
            // 防目录穿越：只允许读取会话目录之下的文件
            if (!file.startsWith(sessionDir.normalize())) {
                return line;
            }
            if (!Files.isRegularFile(file)) {
                return insertField(line, idx, dataField, "{\"_missing\":\"文件已不在\"}");
            }
            String content = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) {
                return insertField(line, idx, dataField, "{\"_empty\":\"内容为空\"}");
            }
            return insertField(line, idx, dataField, content);
        } catch (Throwable e) {
            log.warn("[JSBridge] 读取 {} 失败: {}", fileField, e.getMessage());
            return line;
        }
    }

    /** 在 fileField 前插入 dataField 字段（保持原字段不动，供回放使用）。 */
    private static String insertField(String line, int idx, String dataField, String json) {
        return line.substring(0, idx) + "\"" + dataField + "\":" + json + "," + line.substring(idx);
    }

    private static String jsonString(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
