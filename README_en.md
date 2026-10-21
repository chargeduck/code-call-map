# CodeCall Map

> An open-source call-chain recorder plugin for IntelliJ IDEA

Launch your Java app through a dedicated Run / Debug mode; it records the method-level
call chain and per-method timing via javaagent, then pinpoints bottlenecks with sequence
diagrams, waterfall charts and hot-spot views. **No changes to your business code.**

**Language**: [中文](README_zhcn.md) · [English](README_en.md)
**License**: [MIT License](LICENSE)

---

## What is this

CodeCall Map is an IntelliJ IDEA plugin. Launch your Java application through a dedicated
Run / Debug mode and it instruments methods via javaagent, recording the **call chain and
per-method timing**. Sequence diagrams, waterfall charts and hot-spot views then pinpoint
where the time actually goes.

**No changes to your business code.**

| Capability | Description |
|---|---|
| Auto instrumentation | javaagent rewrites bytecode at class-load time, zero intrusion |
| Call-chain capture | Parent/child relations, arguments, return values, timing, exceptions |
| Sequence diagram | Standard UML form: actors, lifelines, call arrows |
| Waterfall chart | Horizontal timeline, spots the dominant method at a glance |
| Hot spots | Ranked by self time, finds the real bottleneck |
| Editor inlay | Click an arrow to lay the recorded state onto source code, debug-style |
| Args / returns | Externalized as JSON files, large objects don't slow the main path |

---

## Requirements

> **This section matters.** A wrong environment leads to build failures or a plugin that cannot load.

### Required

| Item | Version | Notes |
|---|---|---|
| **JDK** | **21** | `sourceCompatibility = VERSION_21` in `build.gradle.kts`. JDK 17 fails to compile |
| **Gradle** | Use the bundled `gradlew` | No separate installation needed |
| **IntelliJ IDEA** | 2025.2 (build 233) or newer | See "Branches" below |
| **Node.js** | 18+ | Only needed when rebuilding the frontend |

### Branches

All three branches share **identical code**; only the platform settings in
`gradle.properties` differ:

| Branch | platformType | platformVersion | sinceBuild | Target IDE |
|---|---|---|---|---|
| `master` | IC (Community) | 2025.2 | 233 | IDEA Community / Ultimate 2025.2+ |
| `support-233` | IC (Community) | 2025.2 | 233 | Same as master |
| `support-253` | IU (Ultimate) | 2025.3.1 | 253 | IDEA Ultimate 2025.3+ |

**Workflow**: always commit to `master` first, verify there, then merge into
`support-233` and `support-253`. This keeps platform APIs such as JCEF consistent
across versions.

### About the runIde sandbox

`gradle runIde` downloads an **unlicensed distribution** via `intellijIdea(version)`.
Every plugin depending on `com.intellij.modules.ultimate` (Spring Boot, Spring Web,
Spring Data, ...) is disabled in that sandbox:

```
Plugin 'Spring Boot' (com.intellij.spring.boot) requires plugin with
id=com.intellij.modules.ultimate to be enabled
```

**Impact**: features depending on those plugins (e.g. showing the Spring Boot server
port in the Services panel) **cannot be verified in the sandbox** — test them in a real
Ultimate installation.

**Workaround**: point to a locally installed, licensed IDE:

```properties
platformLocalPath=/path/to/your/IntelliJ IDEA
```

`build.gradle.kts` then uses `local(...)` instead of downloading. The local IDE version
must be compatible with `platformVersion`.

---

## Quick start

### 1. Build

```bash
# Compile everything and run unit tests
./gradlew compileAgentJava compileJava unitTest

# Package into a distributable zip
./gradlew buildPlugin
```

Output: `build/distributions/codeCallMap-<version>.zip`.

### 2. Debug the plugin locally

```bash
./gradlew runIde
```

Sandbox log:

```
.intellijPlatform/sandbox/codeCallMap/IU-<version>/log/idea.log
```

### 3. Install into a real IDE

`Settings → Plugins → ⚙ → Install Plugin from Disk...`,
pick `build/distributions/codeCallMap-<version>.zip`, then restart.

### 4. Start recording

1. Open the project you want to analyse
2. Add a new **CodeCall Map** entry under `Run/Debug Configurations`
3. Fill in main class, classpath, and the package prefix to record (e.g. `com.myapp`)
4. Launch with `Run with CCM` or `Debug with CCM`
5. Once running, open the **CodeCall Map** tool window in the bottom-right

Results land in `.callmap/` at the project root:

| Path | Content |
|---|---|
| `callmap-<timestamp>.jsonl` | Main event file, one event per line |
| `args/<spanId>.json` | Method arguments (externalized) |
| `ret/<spanId>.json` | Return values (externalized) |
| `instrument-report.txt` | What got instrumented: classes and methods |
| `run.log` | Full stdout of the target process |
| `stall-dump.txt` | Thread dump when recording stalls |

---

## Project layout

```
src/agent/java/            Recording side (plain Java, no IntelliJ Platform)
  AgentBootstrap           javaagent entry: premain / agentmain
  CallMapRecorder          Event persistence
  JsonlWriter              JSONL writer + args/ret external files
  Span                     Wire format
  instrument/
    InstrumentFilter       Target filtering (three gates)
    MethodInstrumentor     Method-level bytecode rewriting
    SpanBridge             Runtime context (stack, parent/child)
    ArgSerializer          Argument serialization and truncation

src/main/java/             Plugin side
  exec/                    Run / Debug executors and ProgramRunners
  run/                     Run Configuration and settings UI
  webview/                 Tool window and JCEF bridge
    bridge/JSBridge        Frontend command dispatch
    CcmEditorOverlay       Editor inlay orchestration
    CcmCallRecordReader    Reads recorded data
  ...

frontend/src/              Vue 3 frontend (build output inlined into web/index.html)
  components/              Panels: sequence / waterfall / hotspot / search / stats / diff
  utils/duration.js        Duration formatting
```

---

## FAQ

### `Unsupported class file major version`

Wrong JDK. This project requires **JDK 21**; check `JAVA_HOME`.

### Tool window is blank after installing

Frontend assets were not built:

```bash
cd frontend && npm install && npm run build
```

The build script syncs output into `src/main/resources/web/` automatically.

### No call chain captured

Check in order:

1. Open `.callmap/instrument-report.txt` and confirm your classes were instrumented
2. Verify the **package prefix** covers your business classes
3. An empty prefix falls back to default exclusion rules, which skip Spring, Tomcat, etc.
4. Make sure you launched via `Run/Debug with CCM`, not a plain Run

### No server port in the Services panel

Requires a real Ultimate installation with the Spring Boot plugin.
See "About the runIde sandbox" above.

### Frontend changes have no effect

`src/main/resources/web/index.html` is a **build artifact** — do not edit it directly.
Modify sources under `frontend/src/` and run `npm run build`.

---

## Documentation

- [`CHANGELOG.md`](CHANGELOG.md) — Release history
- [`todo.md`](todo.md) — Backlog
- [`docs/intellij-jcef-notes.md`](docs/intellij-jcef-notes.md) — JCEF communication and dev-time configuration notes

## License

Released under the [MIT License](LICENSE). Free for commercial use.
