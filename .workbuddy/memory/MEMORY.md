# codeCallMap 项目长期约定

## 版本号规则（用户 2026-10-20 修正）

**格式：`<大版本>.<新增功能>.<修复BUG>.<构建号>`，例 `1.3.3.0`**

| 字段 | gradle.properties 属性 | 什么时候 +1 |
|---|---|---|
| 大版本 | `pluginMajor` | 架构重构、不兼容变更、正式发版 |
| 新增功能 | `pluginFeature` | 新增一个用户可见的功能 |
| 修复BUG | `pluginFix` | 修一个 bug |
| 构建号 | `pluginBuild` | 每一次提交都 +1，用于区分同名包 |

**递增规则：语义段归零，但【构建号永远累加，绝不清零】。**

```
               构建号是流水号，只增不减
               ↓
修 bug    1.3.3.27 → 1.3.4.28
加功能    1.3.3.27 → 1.4.0.28
重构/发布  1.3.3.27 → 2.0.0.28
仅重新打包 1.3.3.27 → 1.3.3.28
```

**构建号为什么不清零**：它表达"这是第几次构建"，是单调递增的流水号，
不是语义段。清零会出现 `1.3.4.0` 这种"第 0 次构建"，语义说不通，
且发布包文件名会与历史重名、无法区分批次。
（semver 只让 patch 归零，是因为它没有构建号这一层。）

**用任务递增，不要手工改四行数字**：

```bash
./gradlew bumpVersion -Pbump=fix    # 修 bug
./gradlew bumpVersion -Pbump=feat   # 加功能
./gradlew bumpVersion -Pbump=major  # 重构/发版
./gradlew bumpVersion -Pbump=build  # 同版本内多次打包
```

任务会保住 gradle.properties 里的注释（`Properties` 类默认会丢掉注释，所以用逐行正则替换）。

> 注意：版本号在**配置阶段**读取，改完 gradle.properties 后必须重新执行 gradle 命令才生效。

### 历史：为什么改掉旧规则

旧规则是前三段合成一个 `pluginVersionBase=0.1.0` 的手工字符串，实际使用中
**退化成只有最后一位在动**：0.1.0.22 明明是新功能、0.1.0.21 是策略重构，
版本号却毫无体现。现在四段各自独立，语义和行为一致。

旧版本号到新版本号的映射（从 0.1.0.21 起按语义重算）：

| 旧 | 类型 | 新 |
|---|---|---|
| 0.1.0.21 | refactor 插桩策略改白名单 | 1.0.0.0 |
| 0.1.0.22 | feat JDBC/Servlet 探针 | 1.1.0.0 |
| 0.1.0.23 | fix 前端桥接 + 数据目录 | 1.1.1.0 |
| 0.1.0.24 | fix 桥接丢函数 + 配置持久化 | 1.1.2.0 |
| 0.1.0.25 | feat DevTools 调试 | 1.2.0.0 |
| 0.1.0.26 | feat 调用统计 + 慢阈值 | 1.3.0.0 |
| 0.1.0.27 | fix 入口采不到 + 入参采集 | 1.3.1.0 |
| 0.1.0.28 | fix 旧页面缓存 | 1.3.2.0 |
| — | fix 版本体系重构（本次） | **1.3.3.0** |

产物命名：`release/codeCallMap-<四段版本>-<分支>-<平台>.zip`
例：`codeCallMap-1.3.3.0-support253-IU2025.3.1.zip`（无 `-b` 前缀）

### ⚠️ 踩过的坑

**不要在任务配置块里用 `version.toString()`。**
Project.version 此时可能尚未解析为字符串，`toString()` 会拿到 provider 描述文本，
导致 jar 被打成 `codeCallMap-extension 'pluginConfiguration' property 'version'.jar`。
必须用顶部定义好的 `pluginFullVersion: String` 常量。

## 构建与打包

```bash
./gradlew unitTest --offline              # 单测（当前 50 例）
./gradlew clean packageRelease --offline  # 打发布包到 release/
cd frontend && npm run build              # 前端（自动同步到 src/main/resources/web）
```

## 前端工程

源码在 `frontend/`，**不要直接改 `src/main/resources/web/index.html`**（那是 Vite 构建产物）。
构建链：`vite build` → `inline-postbuild.js`（JS 内联）→ `sync-dist.js`（同步到 resources）。

## 分支

`master` / `support-233` / `support-253`（当前），三者需保持同步 cherry-pick。
