# IntelliJ 插件开发备忘：JCEF 通信与开发期配置

记录本项目在 JCEF 通信、沙箱配置上踩过的坑与最终方案。

---

## 一、JCEF 前后端通信：两种路线

### 路线 A：CefMessageRouter（本项目采用）

在 `loadURL` **之前**注册路由，`window.cefQuery` 由 CEF 自己创建：

```java
var config = new CefMessageRouter.CefMessageRouterConfig("cefQuery", "cefQueryCancel");
var router = CefMessageRouter.create(config);
router.addHandler(new CefMessageRouterHandlerAdapter() {
    @Override
    public boolean onQuery(CefBrowser b, CefFrame f, long queryId,
                           String request, boolean persistent,
                           CefQueryCallback callback) {
        // 放到线程池，避免 IO 卡住渲染进程
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            callback.success(handle(request));
        });
        return true;
    }
}, true);
browser.getJBCefClient().getCefClient().addMessageRouter(router);
```

**要点**：注册必须在 `loadURL` 之前。渲染进程启动时会检查客户端有无同名路由，
有才创建全局 `window.cefQuery`。晚一步，页面里的 `cefQuery` 就永远不存在。

**适用**：请求-响应式通信（前端调后端方法）。本项目所有前端命令都是这种。

### 路线 B：JBCefJSQuery.inject

```java
JBCefJSQuery q = JBCefJSQuery.create((JBCefBrowserBase) browser);
q.addHandler(req -> new JBCefJSQuery.Response(handle(req)));
q.inject("link");   // 产出的是【语句】，不是函数
```

该 API 的产物形态：

```js
window.openTargetLink = function(link) {
    console.log("openTargetLink", link);
    <inject 产物放在这里，作为函数体语句>
};
```

**关键理解**：`inject(varName)` 生成的是**调用表达式（一段语句）**，
必须放进 `function(args){ 这里 }` 的函数体位置。
把它当函数去调用 → `ReferenceError: request is not defined`。

**为什么不选它**：产物形态随版本变化，注入时机要自己掌握，
调试成本高。本项目改用路线 A 后一次通过。

---

## 二、两种路线对比

| 维度 | CefMessageRouter | JBCefJSQuery.inject |
|---|---|---|
| 注册时机 | `loadURL` 前，一次性 | 每次页面加载后注入 |
| 产物形态 | 平台内部创建 `window.cefQuery` | 一段 JS 语句，需自行包裹 |
| 错误定位 | 失败有明确异常 | 常见 `xxx is not defined` |
| 适用场景 | 请求-响应、双向 | 单向回调 |

---

## 三、runIde 沙箱与 Ultimate 插件

`intellijIdea(version)` 下载的是**无授权分发包**。沙箱里所有依赖
`com.intellij.modules.ultimate` 的插件（Spring Boot / Spring Web / Spring Data …）
都会被禁用，日志里表现为：

```
Plugin 'Spring Boot' (com.intellij.spring.boot) requires plugin with
id=com.intellij.modules.ultimate to be enabled
```

**影响**：依赖这些插件的能力（如 Spring Boot 端口号显示）在沙箱里无法验证。

**绕法**：在 `gradle.properties` 里指定本机已授权的 IDE：

```properties
platformLocalPath=/path/to/your/IntelliJ IDEA
```

`build.gradle.kts` 检测到该属性时会用 `local(...)` 替代下载。

---

## 四、其他踩坑

1. **Java 会把注释里的 `\u` 当 Unicode 转义解析** ——
   连「非法的反斜杠加 u」这种注释都直接编译报错「非法的 Unicode 逃逸」。

2. **`FileEditorManager.openTextEditor()` 直接返回 `Editor`**，
   不是 `FileEditor`。

3. **`InlayModel.addAfterLineEndElement(lineIndex, ...)` 的第一个参数是行号**
   （0-based），不是偏移量；`addInlineElement` 那个才是偏移量。

4. **`EditorCustomElementRenderer` 没有点击接口** ——
   想响应点击只能往 `contentComponent` 挂 `MouseListener`，
   再用 `inlay.getBounds()` 做命中判断。点击动作是 `GutterIconRenderer` 才有的。
