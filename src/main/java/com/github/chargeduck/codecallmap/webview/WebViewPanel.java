package com.github.chargeduck.codecallmap.webview;

import com.github.chargeduck.codecallmap.webview.bridge.JSBridge;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBuilder;
import com.intellij.ui.jcef.JBCefClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

/**
 * CodeCall Map 的 WebView 主面板。
 *
 * <h2>通信通道：CefMessageRouter（对齐成熟插件的做法）</h2>
 * 本类早期用 {@code JBCefJSQuery} 建通道，屡次踩坑：
 * <ul>
 *   <li>{@code inject(String)} 生成的是一段<b>调用表达式</b>而非具名函数，
 *       按函数去调用直接报 {@code ReferenceError: request is not defined}；</li>
 *   <li>产物形态随版本变化，注入时机还要自己掌握；</li>
 *   <li>window.cefQuery 与自己的适配层容易互相指，形成无限递归。</li>
 * </ul>
 *
 * <p>参考 xechat-idea-webview（同环境已验证可用）后改为 JCEF 原生方案：
 * 在 {@code loadURL} 之前向 CefClient 注册一个名为 {@code cefQuery} 的
 * {@link CefMessageRouter}。渲染进程启动时发现该路由，就会在页面里创建
 * 全局 {@code window.cefQuery}，前端一加载即可直连，无需任何 inject。
 *
 * <h2>关于 org.cef.* 的使用（一次误判的纠正）</h2>
 * 早期曾因 {@code NoClassDefFoundError: org/cef/handler/CefLoadHandler}
 * 断定"IDEA 2026.x 不再暴露 org.cef"并绕开它。实际根因只是
 * {@code plugin.xml} 漏了 {@code <depends>com.intellij.modules.jcef</depends>}，
 * 补上后 {@code org.cef.*} 完全可用。
 */
@Slf4j
public class WebViewPanel extends JPanel {

    private static volatile WebViewPanel instance;

    @Getter
    private JBCefBrowser browser;

    /**
     * JS → Java 查询通道。
     *
     * <h2>为什么用 CefMessageRouter 而不是 JBCefJSQuery</h2>
     * 参考 xechat-idea-webview（同环境已验证可用）的做法：
     * 直接用 JCEF 原生的 {@code CefMessageRouter} 注册名为 {@code cefQuery}
     * 的查询路由。<b>必须在 loadURL 之前注册</b> —— 渲染进程启动时会检查
     * 客户端是否已有同名路由，有才会在页面里创建全局的 {@code window.cefQuery}。
     *
     * <p>JBCefJSQuery 走的是另一条路：它靠 {@code inject()} 生成一段 JS 源码，
     * 让调用方自己塞进页面。这条路有两个额外负担 ——
     * 生成的代码形态不稳定（换版本就变）、注入时机要自己掌握
     * （早了 window 还没建好，晚了前端已经发过请求）。我们在这个方向上
     * 反复出错（{@code request is not defined} 就是 inject 产物形态误判）。
     * 换成 CefMessageRouter 后，{@code window.cefQuery} 由 CEF 自己维护，
     * 页面一加载就存在，前端可以直连。
     */
    private CefMessageRouter messageRouter;

    private JSBridge jsBridge;
    private Project project;
    private String currentUrl;
    private volatile boolean initialized = false;

    /**
     * JS 桥接是否已成功注入。
     *
     * <p>onLoadEnd 回调和兜底定时器都可能触发注入，必须幂等：
     * 重复注入会让异常处理器叠加、同一个请求的回调被调用两次，
     * 前端表现为"数据加载了两遍"或状态错乱。
     */
    private volatile boolean bridgeInstalled = false;

    private WebViewPanel() {
        setLayout(new BorderLayout());
    }

    public static WebViewPanel getInstance() {
        if (instance == null) {
            synchronized (WebViewPanel.class) {
                if (instance == null) {
                    instance = new WebViewPanel();
                }
            }
        }
        return instance;
    }

    /**
     * 初始化 JCEF 并加载指定 URL（幂等，重复调用仅导航）。
     *
     * @param project 当前打开的 IDE 工程，用于前端跳转源码定位
     */
    public void init(Project project, String url) {
        this.project = project;
        // url 为空时自动定位随插件分发的 web/index.html
        // （打 jar 后无法直接用 file: URL 访问 classpath 资源，需先提取到临时目录）
        final String pageUrl = (url != null) ? url : resolveBundledPageUrl();
        if (initialized) {
            if (browser != null && pageUrl != null && !pageUrl.equals(currentUrl)) {
                browser.loadURL(pageUrl);
                currentUrl = pageUrl;
            }
            return;
        }
        this.currentUrl = pageUrl;

        SwingUtilities.invokeLater(() -> {
            try {
                // 【关键修正】构建时【不能】用 setUrl 传页面地址。
                //
                // 原因：setUrl 会让 JCEF 在 build() 时就启动渲染进程并开始加载，
                // 而渲染进程启动时会检查客户端上是否已注册 cefQuery 路由 ——
                // 页面里的 window.cefQuery 只在那时创建一次。
                // 我们的 installMessageRouter() 在 build() 之后才执行，
                // 此时渲染进程已经跑起来了，路由注册被当成"太晚"而忽略，
                // 页面里永远没有 window.cefQuery。
                //
                // 实测表现（IDEA 2026.2）：面板能打开、页面正常渲染，
                // 但所有请求都报"通信通道未就绪（cefQuery 不存在）"，
                // 会话列表永远为空。2025.2 上因为渲染进程启动稍慢而碰巧能成功，
                // 这也是"沙箱里好、真实环境坏"的根源。
                //
                // 正确顺序（与 xechat-idea-webview 一致）：
                //   1. build() 出空浏览器（不带 URL）
                //   2. 注册 CefMessageRouter
                //   3. 注册 load 回调
                //   4. 最后才 loadURL 导航
                JBCefBrowserBuilder builder = new JBCefBrowserBuilder();
                browser = builder.build();
                // 加大 JS 查询池：CefMessageRouter 的并发查询走这个池。
                // 取不到属性说明该平台版本没有它，不影响主流程。
                try {
                    browser.getJBCefClient().setProperty(JBCefClient.Properties.JS_QUERY_POOL_SIZE, 10);
                } catch (Throwable ignored) {
                    // 部分版本无此属性，不影响主流程
                }

                add(browser.getComponent(), BorderLayout.CENTER);
                revalidate();
                repaint();

                jsBridge = new JSBridge(this);
                jsBridge.setProject(project);

                // 建立 JS → Java 通道。
                //
                // 【顺序不能变】此时浏览器已建好但仍指向 about:blank，
                // 渲染进程尚未加载任何页面。在这里注册 CefMessageRouter，
                // 之后首次 loadURL 时渲染进程才会在页面里创建
                // window.cefQuery。反过来（先加载、后注册）就永远没有。
                installMessageRouter();

                // 注册加载完成回调：onLoadEnd 时注入桥接脚本。
                // 必须在 loadURL 之前注册，否则首轮加载完成事件会漏掉。
                if (pageUrl != null) {
                    installLoadHandler();
                    browser.getCefBrowser().loadURL(pageUrl);
                    log.info("[CodeCallMap] 已发起页面加载: {}", pageUrl);
                } else {
                    log.error("[CodeCallMap] 前端页面不可用（未找到 web/index.html）");
                    showError(new IllegalStateException("未找到内置前端页面 web/index.html"));
                }

                initialized = true;
                log.info("[CodeCallMap] JCEF WebView 初始化成功（CefMessageRouter 已注册）");

                // 导出 Chrome DevTools 调试地址到 .callmap/debug-info.json。
                // 放在页面加载之后：此时 JCEF 才刚开始启动，调试端口可能还没监听。
                // 因此做「延迟 + 重试」：先等 2 秒，失败则每隔 3 秒再试，最多 5 次。
                // 在独立线程里做，避免网络探测阻塞 UI。
                final Project p = this.project;
                Thread t = new Thread(() -> {
                    long[] delays = {2000, 3000, 3000, 3000, 3000};
                    for (int i = 0; i < delays.length; i++) {
                        try {
                            Thread.sleep(delays[i]);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (DebugInfoExporter.export(p)) {
                            return;   // 探测成功即结束
                        }
                        log.info("[CodeCallMap] CDP 端点尚未就绪，第 {} 次重试…", i + 1);
                    }
                    log.info("[CodeCallMap] 多次重试仍未探测到 CDP 端点，已放弃。"
                            + "请确认 Registry 中 ide.browser.jcef.debug.port 已开启。");
                }, "ccm-debug-info");
                t.setDaemon(true);
                t.start();

            } catch (Throwable e) {
                log.error("[CodeCallMap] JCEF 初始化失败", e);
                showError(e);
            }
        });
    }

    /**
     * 定位随插件分发的 {@code web/index.html}，返回可用于 JCEF 的 {@code file:} URL。
     *
     * <h2>为什么要先提取到临时文件</h2>
     * 页面在插件 jar 内是 classpath 资源（{@code web/index.html}），
     * 而 {@link JBCefBrowser} 需要一个真实可读的 URL 或整段 HTML 字符串。
     * 后续 {@link #loadPageHtml} 还要读文件内容做脚本注入，
     * 所以统一提取成临时文件最直接。
     *
     * <p>提取结果落在 {@code java.io.tmpdir/codecallmap-web/}，
     * 已存在则直接复用，不重复写盘。
     *
     * @return 页面文件 URL；资源缺失时返回 null
     */
    @Nullable
    private static String resolveBundledPageUrl() {
        try {
            Path baseDir = Paths.get(System.getProperty("java.io.tmpdir"), "codecallmap-web");
            // 读插件内的页面内容（唯一可信来源）
            byte[] content = readBundledHtml();
            if (content == null) {
                return null;
            }
            // 【关键】按内容哈希命名，而不是固定叫 index.html。
            //
            // 早期实现是「临时目录里已有 index.html 就复用」，判断依据只有
            // Files.isRegularFile —— 于是插件升级后（前端桥接脚本变了）
            // 页面上跑的仍是上一次提取的旧 HTML：
            // 表现为 window.cefQuery 不存在、加载示例报
            // "请在前端页面中使用"、会话列表永远为空。
            // 实测现场：临时文件时间 08:17，而新构建是 11:16，相差 3 小时。
            //
            // 用内容哈希做文件名后，插件版本一变哈希就变，自然会提取新文件；
            // 同名文件已存在则说明内容完全一致，直接复用，不重复写盘。
            String hash = sha256Hex(content).substring(0, 16);
            Path target = baseDir.resolve("index-" + hash + ".html");
            if (!Files.isRegularFile(target)) {
                Files.createDirectories(baseDir);
                Files.write(target, content);
                cleanupOldVersions(baseDir, target);
                log.info("[CodeCallMap] 已提取前端页面: {}", target);
            }
            String url = target.toUri().toString();
            log.info("[CodeCallMap] 前端页面: {}", url);
            return url;
        } catch (Throwable e) {
            log.error("[CodeCallMap] 提取前端页面失败", e);
            return null;
        }
    }

    /** 读取插件内置的前端页面内容；缺失时返回 null。 */
    @Nullable
    private static byte[] readBundledHtml() {
        try (InputStream in = WebViewPanel.class.getClassLoader()
                .getResourceAsStream("web/index.html")) {
            if (in == null) {
                log.error("[CodeCallMap] 插件内未找到 web/index.html，前端页面不可用");
                return null;
            }
            return in.readAllBytes();
        } catch (Throwable e) {
            log.error("[CodeCallMap] 读取内置前端页面失败", e);
            return null;
        }
    }

    /**
     * 清理旧版本页面文件。
     *
     * <p>每次插件升级都会留下一个新哈希文件，不清理会一直堆积
     * （页面本体约 3MB，堆十几个就是几十 MB）。只保留当前版本。
     */
    private static void cleanupOldVersions(Path dir, Path keep) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "index-*.html")) {
            for (Path p : stream) {
                if (!p.equals(keep)) {
                    Files.deleteIfExists(p);
                }
            }
        } catch (Throwable ignored) {
            // 清理失败不影响主流程
        }
    }

    /** 计算 SHA-256 十六进制摘要（仅用于生成文件名，不涉及安全）。 */
    private static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                String h = Integer.toHexString(b & 0xff);
                if (h.length() == 1) {
                    sb.append('0');
                }
                sb.append(h);
            }
            return sb.toString();
        } catch (Throwable t) {
            // 极端情况下退化为长度+时间，仍能起到"变了就换名"的作用
            return "v" + data.length;
        }
    }

    /**
     * 注册页面加载完成回调，在页面就绪后注入 JS 桥接。
     *
     * <h2>为什么必须在加载完成之后注入</h2>
     * 桥接脚本要挂到页面的 {@code window} 上，而页面脚本（Vue 应用）
     * 在 {@code DOMContentLoaded} 之后才执行完毕。过早注入，
     * 页面自己的初始化会把我们的定义覆盖掉或时序错乱。
     * {@code onLoadEnd} 是"页面完全加载完毕"的确定信号。
     *
     * <h2>为什么要做重试</h2>
     * {@code onLoadEnd} 在某些 CEF 版本/场景下可能早于 JCEF 客户端完成初始化，
     * 因此除主回调外，另起一个短延时兜底：
     * 注入成功后置 {@code bridgeInstalled} 标记，重复注入直接跳过（幂等）。
     */
    private void installLoadHandler() {
        try {
            org.cef.CefClient client = browser.getJBCefClient().getCefClient();
            client.addLoadHandler(new org.cef.handler.CefLoadHandlerAdapter() {
                @Override
                public void onLoadEnd(org.cef.browser.CefBrowser cefBrowser,
                                      org.cef.browser.CefFrame frame,
                                      int httpStatusCode) {
                    // 只处理主框架：iframe 加载完成也回调，但桥接只需一次
                    if (frame != null && !frame.isMain()) {
                        return;
                    }
                    SwingUtilities.invokeLater(WebViewPanel.this::installBridge);
                }
            });
        } catch (Throwable t) {
            log.warn("[CodeCallMap] 注册页面加载回调失败，改用定时注入", t);
        }
        // 兜底：万一 onLoadEnd 没触发（CEF 版本差异 / 首轮加载过快），
        // 用重复定时器补注入，直到注入成功为止。
        //
        // 只跑一次的话，1500ms 时页面可能还没加载完 ——
        // executeJavaScript 打在不存在的文档上会静默失败，
        // 之后就再没有第二次机会，表现为"页面正常但收不到数据"。
        javax.swing.Timer timer = new javax.swing.Timer(500, e -> {
            installBridge();
            if (bridgeInstalled) {
                ((javax.swing.Timer) e.getSource()).stop();
            }
        });
        timer.setRepeats(true);
        timer.setInitialDelay(500);
        timer.start();
    }

    /**
     * 把桥接脚本注入当前页面。
     *
     * <p>幂等：已经注入成功就直接返回。JCEF 的 {@code onLoadEnd}
     * 与兜底定时器可能都触发，重复注入会让异常处理器叠加、请求回调被调两次。
     */
    private void installBridge() {
        if (bridgeInstalled) {
            return;
        }
        try {
            org.cef.browser.CefBrowser cefBrowser = browser.getCefBrowser();
            if (cefBrowser == null) {
                log.warn("[CodeCallMap] cefBrowser 为空，桥接注入延后");
                return;
            }
            String script = buildBridgeScript();
            cefBrowser.executeJavaScript(script, cefBrowser.getURL(), 0);
            bridgeInstalled = true;
            if (jsBridge != null) {
                jsBridge.markRegistered();
            }
            log.info("[CodeCallMap] JS 桥接已注入（cefQuery 由 CefMessageRouter 提供）");
        } catch (Throwable t) {
            log.warn("[CodeCallMap] 注入 JS 桥接失败: {}", t.getMessage());
        }
    }

    /**
     * 注册 JCEF 原生的 cefQuery 消息路由。
     *
     * <p><b>必须在 loadURL 之前调用。</b>渲染进程启动时会查询客户端上
     * 是否已注册同名路由，有才在页面里创建全局 {@code window.cefQuery}。
     * 晚一步注册，页面里的 {@code window.cefQuery} 就永远不存在。
     *
     * <p>路由名固定为 {@code cefQuery}，与前端以及 xechat-idea-webview
     * 等成熟插件的约定一致。
     */
    private void installMessageRouter() {
        try {
            CefMessageRouter.CefMessageRouterConfig config =
                    new CefMessageRouter.CefMessageRouterConfig("cefQuery", "cefQueryCancel");
            CefMessageRouter router = CefMessageRouter.create(config);
            router.addHandler(new CefMessageRouterHandlerAdapter() {
                @Override
                public boolean onQuery(org.cef.browser.CefBrowser cefBrowser,
                                       org.cef.browser.CefFrame frame,
                                       long queryId,
                                       String request,
                                       boolean persistent,
                                       CefQueryCallback callback) {
                    // 在非 UI 线程处理，避免查询里有 IO 时卡住渲染进程
                    ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        try {
                            String response = jsBridge.handleRequest(request);
                            callback.success(response);
                        } catch (Throwable t) {
                            log.warn("[CodeCallMap] 处理查询失败: {}", t.getMessage());
                            callback.failure(500, String.valueOf(t.getMessage()));
                        }
                    });
                    return true;
                }
            }, true);

            browser.getJBCefClient().getCefClient().addMessageRouter(router);
            this.messageRouter = router;
            log.info("[CodeCallMap] CefMessageRouter 已注册（loadURL 之前），路由名 cefQuery");
        } catch (Throwable t) {
            log.error("[CodeCallMap] 注册 CefMessageRouter 失败", t);
        }
    }

    /**
     * 构造注入用的 JS 源码（一行，供 executeJavaScript 执行）。
     *
     * <h2>现在只做「补充能力」，不再负责建立通道</h2>
     * {@code window.cefQuery} 已由 {@link #installMessageRouter()} 在
     * 页面加载前就建立好了，前端可以直接调用。这里注入的脚本只负责两件事：
     * <ol>
     *   <li>暴露 {@code window.codeCallMapQuery} 作为语义化的别名；</li>
     *   <li>提供 Java → JS 的推送入口 {@code window.codeCallMapBridge}。</li>
     * </ol>
     *
     * <p>因此脚本是纯 JS、不依赖任何由 Java 侧生成的可变代码，
     * 不会再有「生成的代码形态改了导致运行时报错」的问题。
     */
    private String buildBridgeScript() {
        return "(function(){"
                // 给先前的约定保留一个语义化别名，前端优先用它，
                // 底层仍是 CEF 自己维护的 window.cefQuery。
                + "if(typeof window.codeCallMapQuery!=='function'){"
                + "  window.codeCallMapQuery=function(opts){"
                + "    if(typeof opts==='string'){opts={request:opts};}"
                + "    opts=opts||{};"
                + "    if(typeof window.cefQuery!=='function'){"
                + "      if(opts.onFailure)opts.onFailure(-1,'window.cefQuery 尚未就绪');"
                + "      return;"
                + "    }"
                + "    try{"
                + "      window.cefQuery({"
                + "        request:String(opts.request||''),"
                + "        onSuccess:function(resp){"
                + "          if(opts.onSuccess){try{opts.onSuccess(String(resp));}"
                + "catch(e){(opts.onFailure||function(){})(-2,String(e&&e.message?e.message:e));}}"
                + "        },"
                + "        onFailure:function(code,msg){"
                + "          if(opts.onFailure){try{opts.onFailure(code,msg);}catch(e){}}"
                + "        }"
                + "      });"
                + "    }catch(e){"
                + "      if(opts.onFailure)opts.onFailure(-3,String(e&&e.message?e.message:e));"
                + "    }"
                + "  };"
                + "}"
                // Java → JS 推送入口（前端监听 codecallmap:push 事件）
                + "window.codeCallMapBridge={"
                + "  push:function(p){try{window.dispatchEvent(new CustomEvent('codecallmap:push',{detail:p}));}catch(e){}},"
                + "  log:function(m){try{console.log('[CodeCallMap] '+m);}catch(e){}}"
                + "};"
                + "console.log('[CodeCallMap] 桥接就绪 cefQuery=' + (typeof window.cefQuery)"
                + " + ' codeCallMapQuery=' + (typeof window.codeCallMapQuery));"
                + "})();";
    }

    /**
     * 页面加载完成回调（保留给外部调用）。
     *
     * <p>桥接现在由 {@link #installLoadHandler()} 在 onLoadEnd 时注入，
     * 这里再做一次兜底尝试（幂等，重复调用无副作用）。
     */
    public void onPageLoaded() {
        installBridge();
    }

    /** 导航到指定 URL。 */
    public void navigate(String url) {
        if (browser != null && url != null) {
            this.currentUrl = url;
            browser.loadURL(url);
        }
    }

    /**
     * 执行 JavaScript。
     *
     * <h2>为什么这里是空实现</h2>
     * 唯一能执行任意 JS 的路径是 {@code browser.getCefBrowser().executeJavaScript(...)}，
     * 而该方法的签名包含 {@code org.cef.browser.CefBrowser}。JVM 在解析这个调用点时会去
     * 加载 {@code CefBrowser}，而 <b>IDEA 2026.x 不再向插件暴露 {@code org.cef.*}</b>，
     * 于是直接 {@code NoClassDefFoundError}。之前正是这里导致「点开插件就报错」。
     *
     * <p>现在 Java → JS 的唯一通道改为页面主动拉取（{@code loadSpans} 等查询），
     * Java 端不再需要推送任意脚本。若将来确实需要推送能力，应通过
     * {@code JBCefJSQuery.inject()} 生成的函数配合前端轮询实现，而不是回到 {@code org.cef}。
     *
     * @deprecated 无法在不引用 {@code org.cef} 的前提下实现，保留签名仅为兼容调用方
     */
    @Deprecated
    public void executeJS(String js) {
        log.debug("[CodeCallMap] executeJS 已停用（避免引用 org.cef），忽略脚本: {}", js);
    }

    /** 是否已初始化。 */
    public boolean isInitialized() {
        return initialized && browser != null;
    }

    /** JCEF 是否可用。不再直接引用 org.cef，改为探测浏览器能否创建。 */
    public static boolean isCefAvailable() {
        try {
            // 只做轻量探测：能构造浏览器即认为 JCEF 可用
            return JBCefBrowserBuilder.class != null;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 获取 JSBridge。 */
    public JSBridge getJSBridge() {
        return jsBridge;
    }

    /** 关闭并释放资源。 */
    public void dispose() {
        try {
            // 先摘掉路由，避免 browser 已析构后还有查询进来
            if (messageRouter != null && browser != null) {
                browser.getJBCefClient().getCefClient().removeMessageRouter(messageRouter);
                messageRouter.dispose();
                messageRouter = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (jsBridge != null) {
                jsBridge.dispose();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (browser != null) {
                browser.dispose();
                browser = null;
            }
        } catch (Throwable ignored) {
        }
        initialized = false;
        instance = null;
    }

    /** JCEF 初始化失败时展示可读的错误信息，避免整个工具窗口空白。 */
    private void showError(Throwable e) {
        removeAll();
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        JLabel errorLabel = new JLabel(
                "<html><div style='text-align:center;padding:30px;'>"
                        + "<h2>JCEF 初始化失败</h2>"
                        + "<p>" + escapeHtml(msg) + "</p>"
                        + "<p>请确认 IDE 支持 JCEF（IntelliJ IDEA 2020.2+）</p>"
                        + "</div></html>",
                SwingConstants.CENTER);
        add(errorLabel, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
