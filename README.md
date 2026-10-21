# CodeCall Map

> 开源的 Java 接口调用链路记录插件 · An open-source call-chain recorder plugin for IntelliJ IDEA

用专用的 Run / Debug 模式启动 Java 程序，通过 javaagent 自动采集方法级调用链与每级耗时，
再用时序图、瀑布图、耗时热点定位性能卡点。**业务代码零侵入。**

Launch your Java app through a dedicated Run / Debug mode; it records the method-level
call chain and per-method timing via javaagent, then pinpoints bottlenecks with sequence
diagrams, waterfall charts and hot-spot views. **No changes to your business code.**

---

## 文档导航 · Documentation

| 语言 · Language | 文件 · File |
|---|---|
| 中文 | [README_zhcn.md](README_zhcn.md) |
| English | [README_en.md](README_en.md) |

完整说明（运行环境、构建步骤、分支差异、常见问题）都在上面两个文件里。
Full documentation — requirements, build steps, branch differences and FAQ — lives in the files above.

---

## 核心能力 · Highlights

| 中文 | English |
|---|---|
| 自动插桩，业务代码零侵入 | Auto instrumentation, zero code changes |
| 采集调用链、入参、返回值、耗时、异常 | Captures call chain, args, returns, timing, exceptions |
| 标准 UML 时序图 | Standard UML sequence diagram |
| 瀑布图定位时间大头 | Waterfall chart shows where time goes |
| 按自身耗时排序的热点分析 | Hot spots ranked by self time |
| 点时序图箭头 → 编辑器内联看录制现场 | Click an arrow → recorded state laid onto source code |

---

## 快速开始 · Quick start

```bash
# 需要 JDK 21 / Requires JDK 21
./gradlew compileAgentJava compileJava unitTest
./gradlew buildPlugin     # 产物: build/distributions/codeCallMap-<版本>.zip
./gradlew runIde          # 本地调试插件 / Debug the plugin locally
```

安装：`Settings → Plugins → ⚙ → Install Plugin from Disk...`，选择上述 zip 后重启。

Install: `Settings → Plugins → ⚙ → Install Plugin from Disk...`, pick the zip, restart.

> **注意**：`runIde` 沙箱不含 Ultimate 授权，Spring Boot 等插件会被禁用，
> 相关能力需装到真实 Ultimate 验证。详见 [README_zhcn.md](README_zhcn.md#关于-runide-沙箱)。
>
> **Note**: the `runIde` sandbox has no Ultimate license, so plugins such as Spring Boot are
> disabled there. Verify those features in a real Ultimate installation.
> See [README_en.md](README_en.md#about-the-runide-sandbox).

---

## 分支 · Branches

| 分支 · Branch | 平台 · Platform | 适用 IDE · Target IDE |
|---|---|---|
| `master` / `support-233` | IntelliJ Platform 2025.2 (IC) | IDEA 2025.2+ |
| `support-253` | IntelliJ Platform 2025.3.1 (IU) | IDEA Ultimate 2025.3+ |

三个分支代码完全一致，只有 `gradle.properties` 的平台配置不同。
改动一律先提交 `master`，验证通过后再合并到另外两个分支。

All branches share identical code; only the platform settings in `gradle.properties` differ.
Always commit to `master` first, then merge into the other two after verification.

---

## 发布 · Releasing

推一个 tag 即可，CI 会自动构建两个平台版本并发布到 GitHub Release：

```bash
git tag v0.4.2.46
git push origin v0.4.2.46
```

推送后自动产出两个 zip：

| 产物 | 目标 IDE |
|---|---|
| `codeCallMap-<版本>-master-IC2025.2.zip` | IDEA Community / Ultimate 2025.2+ |
| `codeCallMap-<版本>-support253-IU2025.3.1.zip` | IDEA Ultimate 2025.3+ |

两个文件互不通用 —— 装错版本时 IDEA 会提示不兼容，或出现 JCEF 相关异常。

Push a tag and CI builds both platform variants and publishes them to GitHub Release.
The two zips are **not interchangeable**: installing the wrong one makes IDEA report
an incompatibility or triggers JCEF-related runtime errors.

---

## 其他文档 · More

- [CHANGELOG.md](CHANGELOG.md) — 版本变更记录 · Release history
- [todo.md](todo.md) — 待办事项 · Backlog
- [docs/intellij-jcef-notes.md](docs/intellij-jcef-notes.md) — JCEF 开发备忘 · JCEF notes

---

## 许可 · License

本项目采用 [MIT License](LICENSE) 开源，可自由用于商业项目。

Released under the [MIT License](LICENSE). Free for commercial use.
