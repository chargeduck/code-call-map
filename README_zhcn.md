# CodeCall Map

> 开源的 Java 接口调用链路记录插件

用专用的 Run / Debug 模式启动 Java 程序，通过 javaagent 自动采集方法级调用链与每级耗时，
再用时序图、瀑布图、耗时热点定位性能卡点。**业务代码零侵入。**

**语言**：[中文](README_zhcn.md) · [English](README_en.md)
**许可**：[MIT License](LICENSE)

---

## 这是什么

CodeCall Map 是一个 IntelliJ IDEA 插件。用专用的 Run / Debug 模式启动 Java 程序后，
它通过 javaagent 自动对方法做字节码插桩，采集**方法级调用链与每一级的耗时**，
再用时序图、瀑布图、耗时热点等视图把性能卡点标出来。

**不需要改一行业务代码。**

核心能力：

| 能力 | 说明 |
|---|---|
| 自动插桩 | javaagent 在类加载时改造字节码，业务代码零侵入 |
| 调用链采集 | 记录每个方法的父子关系、入参、返回值、耗时、异常 |
| 时序图 | 标准 UML 形态，参与者 + 生命线 + 调用箭头 |
| 瀑布图 | 横向时间轴，一眼看出哪个方法占了大头 |
| 耗时热点 | 按自身耗时（self time）排序，定位真正的卡点 |
| 编辑器内联 | 点时序图箭头 → 把录制现场铺在源码上，像 debug 一样看数据 |
| 入参 / 返回值 | 外置为 JSON 文件，大对象不拖累主流程 |

---

## 运行环境

> **这一节很重要。** 环境不对会导致构建失败或插件装不上。

### 必需

| 项目 | 版本 | 说明 |
|---|---|---|
| **JDK** | **21** | `build.gradle.kts` 里 `sourceCompatibility = VERSION_21`。JDK 17 会编译失败 |
| **Gradle** | 使用仓库自带的 `gradlew` | 无需单独安装 |
| **IntelliJ IDEA** | 2025.2（build 233）或更高 | 见下方「分支说明」 |
| **Node.js** | 18+ | 仅在需要重新构建前端时使用 |

### 分支说明

三个分支的**代码完全相同**，只有 `gradle.properties` 里的平台配置不同：

| 分支 | platformType | platformVersion | sinceBuild | 适用 IDE |
|---|---|---|---|---|
| `master` | IC（Community） | 2025.2 | 233 | IDEA Community / Ultimate 2025.2+ |
| `support-233` | IC（Community） | 2025.2 | 233 | 同上，与 master 一致 |
| `support-253` | IU（Ultimate） | 2025.3.1 | 253 | IDEA Ultimate 2025.3+ |

**开发流程**：改动一律先提交到 `master`，在 master 上验证通过后再合并到
`support-233` 与 `support-253`。这样可以避免 JCEF 等平台 API 在两个版本间出现不一致。

### 关于 runIde 沙箱

`gradle runIde` 会用 `intellijIdea(version)` 下载一个**不带授权的分发包**。
在这个沙箱里，所有依赖 `com.intellij.modules.ultimate` 的插件
（Spring Boot、Spring Web、Spring Data 等）都会被禁用，日志中表现为：

```
Plugin 'Spring Boot' (com.intellij.spring.boot) requires plugin with
id=com.intellij.modules.ultimate to be enabled
```

**影响**：依赖这些插件的能力（例如在 Services 面板显示 Spring Boot 端口号）
**无法在沙箱中验证**，需要装到真实的 Ultimate 里测。

**绕法**：在 `gradle.properties` 里指定本机已授权的 IDE 目录：

```properties
platformLocalPath=/path/to/your/IntelliJ IDEA
```

配置后 `build.gradle.kts` 会用 `local(...)` 替代下载。
注意本机 IDE 版本需要与 `platformVersion` 兼容。

---

## 快速开始

### 1. 构建

```bash
# 编译全部代码并跑单元测试
./gradlew compileAgentJava compileJava unitTest

# 打包成可分发的 zip
./gradlew buildPlugin
```

产物在 `build/distributions/codeCallMap-<版本>.zip`。

### 2. 本地调试插件

```bash
# 启动一个带本插件的沙箱 IDE
./gradlew runIde
```

沙箱的日志在：

```
.intellijPlatform/sandbox/codeCallMap/IU-<版本>/log/idea.log
```

### 3. 安装到真实 IDE

`Settings → Plugins → ⚙ → Install Plugin from Disk...`，
选中 `build/distributions/codeCallMap-<版本>.zip`，重启 IDE。

### 4. 开始采集

1. 打开要分析的项目
2. 在 `Run/Debug Configurations` 里新增一个 **CodeCall Map** 配置
3. 填主类、classpath、要采集的包前缀（例如 `com.myapp`）
4. 用 `Run with CCM` 或 `Debug with CCM` 启动
5. 应用跑起来后，打开右下角的 **CodeCall Map** 工具窗口

采集结果写在项目根目录的 `.callmap/` 下：

| 路径 | 内容 |
|---|---|
| `callmap-<时间戳>.jsonl` | 调用记录主文件，一行一个事件 |
| `args/<spanId>.json` | 方法入参（外置，避免撑大主文件） |
| `ret/<spanId>.json` | 方法返回值（外置） |
| `instrument-report.txt` | 插桩清单：哪些类、哪些方法被改造了 |
| `run.log` | 目标进程的完整输出 |
| `stall-dump.txt` | 采集停摆时的全线程栈快照 |

---

## 项目结构

```
src/agent/java/            采集侧（纯 Java，不依赖 IntelliJ 平台）
  AgentBootstrap           javaagent 入口，premain / agentmain
  CallMapRecorder          事件落盘
  JsonlWriter              JSONL 写入 + args/ret 外置文件
  Span                     传输格式
  instrument/
    InstrumentFilter       插桩目标过滤（三道关）
    MethodInstrumentor     方法级字节码改造
    SpanBridge             运行时上下文（栈、父子关系）
    ArgSerializer          入参序列化与裁剪

src/main/java/             插件侧
  exec/                    Run / Debug 执行器与 ProgramRunner
  run/                     Run Configuration 与配置界面
  webview/                 工具窗口与 JCEF 桥
    bridge/JSBridge        前端命令分发
    CcmEditorOverlay       编辑器内联展示编排
    CcmCallRecordReader    读录制数据
  ...

frontend/src/              Vue 3 前端（构建产物内联进 web/index.html）
  components/              各面板：时序图 / 瀑布图 / 热点 / 搜索 / 统计 / 对比
  utils/duration.js        时长格式化
```

---

## 常见问题

### 构建报 `Unsupported class file major version`

JDK 版本不对。本项目要求 **JDK 21**，请检查 `JAVA_HOME`。

### 插件装上但工具窗口是空白

前端资源没构建。执行：

```bash
cd frontend && npm install && npm run build
```

构建脚本会自动把产物同步到 `src/main/resources/web/`。

### 采集不到调用链

按顺序排查：

1. 看 `.callmap/instrument-report.txt`，确认目标类是否被插桩
2. 检查配置里的**包前缀**是否覆盖了你的业务类
3. 包前缀为空时走默认排除规则，会跳过 Spring、Tomcat 等框架类
4. 确认启动方式用的是 `Run/Debug with CCM`，而不是普通 Run

### Services 面板看不到端口号

需要真实的 Ultimate + Spring Boot 插件。
见上方「关于 runIde 沙箱」。

### 前端改了没生效

`src/main/resources/web/index.html` 是**构建产物**，不要直接改。
改 `frontend/src/` 下的源文件，然后 `npm run build`。

---

## 文档

- [`CHANGELOG.md`](CHANGELOG.md) — 版本变更记录
- [`todo.md`](todo.md) — 待办事项
- [`docs/intellij-jcef-notes.md`](docs/intellij-jcef-notes.md) — JCEF 通信与开发期配置备忘

## 许可

本项目采用 [MIT License](LICENSE) 开源，可自由用于商业项目。

---
