import java.io.File
import java.util.Properties

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.github.chargeduck.codecallmap"

/**
 * 插件版本号：四段式 `<大版本>.<新增功能>.<修复BUG>.<构建号>`。
 *
 * <h2>每段的语义（必须严格遵守）</h2>
 * <table border="1">
 *   <tr><th>段</th><th>属性</th><th>什么时候 +1</th></tr>
 *   <tr><td>大版本</td><td>{@code pluginMajor}</td>
 *       <td>架构重构、不兼容变更、正式发版</td></tr>
 *   <tr><td>新增功能</td><td>{@code pluginFeature}</td>
 *       <td>新增一个用户可见的功能</td></tr>
 *   <tr><td>修复 BUG</td><td>{@code pluginFix}</td>
 *       <td>修一个 bug</td></tr>
 *   <tr><td>构建号</td><td>{@code pluginBuild}</td>
 *       <td><b>每一次</b>提交代码都 +1，用于区分同名包</td></tr>
 * </table>
 *
 * <h2>为什么不是「前三段固定、只动构建号」</h2>
 * 早期实现把前三段合成一个 {@code pluginVersionBase}，实际使用中退化成
 * "只有最后一位在动"，于是 0.1.0.23 → 0.1.0.28 看起来全是小修补，
 * 而其中 0.1.0.22 明明是新功能、0.1.0.21 是策略重构 —— 版本号完全没体现。
 * 现在四段各自独立，语义和行为一致。
 *
 * <h2>递增规则</h2>
 * 数字加一后，<b>右侧所有段必须归零</b>（如 1.2.3.4 加功能 → 1.3.0.0），
 * 这与语义化版本一致，避免出现"功能号为 5、修复号还是 27"这种自相矛盾的版本。
 */
val pluginMajor: String = providers.gradleProperty("pluginMajor").get()
val pluginFeature: String = providers.gradleProperty("pluginFeature").get()
val pluginFix: String = providers.gradleProperty("pluginFix").get()
val pluginBuild: String = providers.gradleProperty("pluginBuild").get()

/**
 * 版本号后缀，可为空。
 *
 * <p>用于区分面向不同 IDEA 版本、但代码相同的两个分支。
 * 若两个分支版本号完全相同，上传 JetBrains Marketplace 时会被判为
 * 「同一版本重复发布」而拒绝，所以 233 分支用 `-legacy` 与 253 区分开。
 *
 * <p>配置在 gradle.properties 的 `pluginVersionSuffix`，留空即不加。
 */
val pluginVersionSuffix: String =
    providers.gradleProperty("pluginVersionSuffix").getOrElse("").trim()

/**
 * 完整的四段式版本号字符串。
 *
 * <p>用明确的 String 常量而不是直接读 `version`（Project 属性）：
 * 在任务配置块里 `version.toString()` 可能取到 provider 的描述文本，
 * 曾导致 agent jar 被打成 `codeCallMap-agent-extension 'pluginConfiguration'
 * property 'version'.jar` 这种错误文件名。
 */
val pluginFullVersion: String = buildString {
    append("$pluginMajor.$pluginFeature.$pluginFix.$pluginBuild")
    if (pluginVersionSuffix.isNotEmpty()) {
        append('-').append(pluginVersionSuffix)
    }
}
version = pluginFullVersion

repositories {
    mavenCentral()
    // 离线开发兜底：指向本机 Maven 仓库（ASM 等依赖在无网环境下从这里解析）。
    //
    // 【为什么必须判断目录是否存在】
    // 这个路径是开发者本机的绝对路径，CI（Linux runner）上根本不存在。
    // 无条件声明会直接构建失败：
    //   Not a supported repository protocol 'E'
    // 因为 Gradle 把 "E:" 当成了 URL 的协议部分。
    // 所以这里只在目录真实存在时才注册，CI 上自动跳过、只用 mavenCentral。
    //
    // 路径来源（优先级从高到低）：
    //   1. -PmavenLocalRepo=/your/path
    //   2. 环境变量 MAVEN_LOCAL_REPO
    //   3. 默认不启用（联网环境下 mavenCentral 足够）
    //
    // 刻意不给默认字面量：仓库里不该出现任何开发者的本机路径，
    // 否则换个机器或上 CI 就会踩坑。
    val localRepoPath: String? = providers.gradleProperty("mavenLocalRepo").orNull
        ?: providers.environmentVariable("MAVEN_LOCAL_REPO").orNull
    if (!localRepoPath.isNullOrBlank()) {
        val localRepoDir = file(localRepoPath)
        if (localRepoDir.isDirectory) {
            maven {
                url = localRepoDir.toURI()
                isAllowInsecureProtocol = true
            }
        }
    }

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 目标 IDE 由 gradle.properties 的 platformType 决定：
        //   IC → IntelliJ IDEA Community（master / support-233 = 2025.2，since 233）
        //   IU → IntelliJ IDEA Ultimate（support-253 = 2025.3.1，since 253，与 xechat 参考一致）
        val platformType = providers.gradleProperty("platformType").get()
        val platformVersion = providers.gradleProperty("platformVersion").get()
        // localPath：指向本机已安装（且已授权）的 IDE 目录。
        //
        // 【为什么需要它】intellijIdea(version) 下载的是 JetBrains 的分发压缩包，
        // runIde 起来的沙箱**没有 Ultimate 授权**，于是所有 Ultimate 插件
        // （Spring Boot / Spring Web / Spring Data …）全部被禁用：
        //   Plugin 'Spring Boot' (com.intellij.spring.boot) requires plugin with id=
        //   com.intellij.modules.ultimate to be enabled
        // 结果是「端口号显示」这类依赖 Spring Boot 插件的能力，在沙箱里根本验证不了。
        // 指定 localPath 后，runIde 直接复用本机安装，授权与插件状态都是真实的。
        //
        // 用法：gradle.properties 里加
        //   platformLocalPath=E\:\\ide\\jetbrains\\IntelliJ IDEA
        // （Windows 路径里的反斜杠需转义，或直接用正斜杠）
        val localPath = providers.gradleProperty("platformLocalPath").orNull
        if (!localPath.isNullOrBlank()) {
            // 注意：这里必须写 File 而不是 java.io.File ——
            // Kotlin DSL 里 `java` 是 JavaPluginExtension 扩展，会遮蔽 java 包名
            local(File(localPath))
        } else if (platformType.equals("IU", ignoreCase = true)) {
            intellijIdea(platformVersion)
        } else {
            intellijIdeaCommunity(platformVersion)
        }

        // Java 模块：提供 ApplicationConfiguration / JavaParameters 等 Run Configuration API
        bundledPlugin("com.intellij.java")
    }

    // 日志框架：lombok @Slf4j + slf4j-api（插件侧复用 IDEA 平台自带 slf4j，仅编译期可见）
    compileOnly("org.projectlombok:lombok:1.18.40")
    annotationProcessor("org.projectlombok:lombok:1.18.40")
    compileOnly("org.slf4j:slf4j-api:2.0.16")

    // ASM：agent 侧做方法级字节码插桩。仅 agent 包使用，故 compileOnly + 由 agentJar 打包进独立 jar。
    compileOnly("org.ow2.asm:asm:9.7")
    compileOnly("org.ow2.asm:asm-commons:9.7")
    compileOnly("org.ow2.asm:asm-tree:9.7")

    // 单元测试：插桩引擎与过滤规则不依赖 IDEA 平台，用 JUnit 5 直接验证
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    // ASM 是 compileOnly，测试运行时需要真实字节码库，否则插桩测试会 NoClassDefFoundError
    testImplementation("org.ow2.asm:asm:9.7")
    testImplementation("org.ow2.asm:asm-commons:9.7")
    testImplementation("org.ow2.asm:asm-tree:9.7")
}

// 单元测试：插桩引擎与过滤规则不依赖 IDEA 平台，用 JUnit 5 直接验证。
// 注意：不能复用 Gradle 默认的 test 任务——IntelliJ Platform Gradle Plugin 会把 test
// 重定向到沙箱 IDE 里跑（需要 JUnit4 TestRule 与平台运行时），而本插桩模块是纯 Java，
// 因此单独注册一个走标准 JVM 的 unitTest 任务。
val unitTest by tasks.registering(Test::class) {
    description = "运行 agent 侧纯 Java 单元测试（插桩引擎 / 上下文 / 过滤规则）"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    // 测试要读 agent 的内置默认配置，必须先产出资源
    dependsOn(tasks.named("processAgentResources"))
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

java {
    // 平台版本 2025.2 / 2025.3.1 均基于 Java 21（class 65），插件源码 target 21
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

// ===== agent 字节码必须兼容低版本 JDK（关键设计） =====
// 为什么 agent 不能也用 Java 21 编译：
//   -javaagent 的 jar 由【目标应用进程自己的 JVM】在启动时加载，
//   而目标应用用的是用户的 Project SDK（JDK 8/11/17/21 任意版本都可能）。
//   用 21 编译出的 class（major 65）在 JDK 11（major 55）上会直接抛
//   UnsupportedClassVersionError，导致应用无法启动。
//
// 目的：让插件对用户项目的 JDK 版本"零要求"——用户不需要为了用本插件
// 去改 Project SDK。因此把 agent 源码放在独立目录 src/agent/java，
// 以 release=8 单独编译（major 52），即可被 JDK 8+ 所有版本加载。
//
// 约束：src/agent/java 下的代码不能出现 Java 9+ 语法/API
// （switch 箭头、var、isBlank()、List.of/Map.of/Set.of、instanceof 模式匹配、
//   Matcher.appendReplacement(StringBuilder) 等）。编译器会直接报错兜底。
val agentSourceSet = sourceSets.create("agent") {
    java.srcDir("src/agent/java")
    // 注意：自定义 sourceSet 默认已包含 src/<name>/resources，
    // 这里不要再 srcDir 追加一次，否则 processAgentResources 会报 duplicate entry。
    compileClasspath += configurations.compileClasspath.get()
    annotationProcessorPath += configurations.annotationProcessor.get()
}

tasks.named<JavaCompile>("compileAgentJava") {
    options.encoding = "UTF-8"
    // 关键：兼容 JDK 8 及以上所有目标 JVM
    options.release.set(8)
}

// 插件侧（run/webview 包）会引用 agent 包的类（如 CallMapConfig），
// 把 agent 输出加入主源集 classpath 与运行时。
// 测试源集也要加：InstrumentationTest / PackageProbeTest 直接测 agent 与 run 包的逻辑。
//
// 【关键】仅加 classpath 不够：agent 源码已从 src/main/java 移出，
// 主源集编译时能"看见"这些类，但打 jar 时不会包含它们
// （jar 的 from 是 sourceSets.main.output，不含 agent 源集）。
// 表现为编译全绿、运行时报 ClassNotFoundException: CallMapConfig。
// 因此把 agent 的 Java 8 class 复制进 main 的 classes 目录，
// 让 jar / prepareSandbox / 插件 zip 这些基于 main.output 的任务自动带上。
val copyAgentClassesIntoMain by tasks.registering(Copy::class) {
    dependsOn(tasks.named("compileAgentJava"))
    from(agentSourceSet.output.classesDirs)
    into(layout.buildDirectory.dir("classes/java/main"))
    // 只拷 agent 包，避免把 agent 的其它产物混进插件
    include("com/github/chargeduck/codecallmap/agent/**")
}

tasks.named("classes") { dependsOn(copyAgentClassesIntoMain) }

sourceSets {
    main {
        compileClasspath += agentSourceSet.output
        runtimeClasspath += agentSourceSet.output
    }
    test {
        compileClasspath += agentSourceSet.output
        // 同时把 agent 的资源目录加进来：测试要验证"内置默认过滤配置可读"，
        // 只加 classesDirs 的话 instrument-filter-default.yml 不在 classpath 上。
        runtimeClasspath += agentSourceSet.output
        runtimeClasspath += files(layout.buildDirectory.dir("resources/agent"))
    }
}

// agent jar 只打包 agent 源集产物

// 独立 javaagent jar：仅打包 com.github.chargeduck.codecallmap.agent 包（纯 Java，不依赖 IDEA 平台 API），
// manifest 声明 premain/agentmain 入口，供 Run Configuration 与自动接管以 -javaagent 方式注入。
// 日志 fallback：agent jar 内置 slf4j-api + slf4j-simple，目标应用无 slf4j 实现时由 simple 兜底输出到 stderr；
// 若目标应用自带 slf4j 实现（logback 等），注释下方 agentRuntime 中的 slf4j-simple 即可避免多 provider 告警。
val agentRuntime by configurations.creating
dependencies {
    agentRuntime("org.slf4j:slf4j-api:2.0.16")
    agentRuntime("org.slf4j:slf4j-simple:2.0.16")
    // ASM 必须随 agent jar 一起分发：agent 以 -javaagent 加载，运行在目标应用进程内，
    // 不能假设目标应用的 classpath 上有 ASM。插桩只在 agent 内存中跑一次，不参与目标应用类加载。
    agentRuntime("org.ow2.asm:asm:9.7")
    agentRuntime("org.ow2.asm:asm-commons:9.7")
    agentRuntime("org.ow2.asm:asm-tree:9.7")
}

val agentJar by tasks.registering(Jar::class) {
    archiveBaseName.set("codeCallMap-agent")
    archiveVersion.set(pluginFullVersion)
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    // 只取 agent 源集（src/agent/java，以 Java 8 编译）的产物。
    // 注意依赖 compileAgentJava 而非 compileJava：后者是 Java 21，
    // 用它的产物打 agent jar 会导致目标应用 JDK 11 加载失败（UnsupportedClassVersionError）。
    dependsOn(tasks.named("compileAgentJava"))
    // 依赖 processAgentResources 才能拿到 src/agent/resources 下的
    // instrument-filter-default.yml —— agent 启动时从 classpath 读取它作为默认过滤规则
    dependsOn(tasks.named("processAgentResources"))
    from(agentSourceSet.output.classesDirs)
    // 直接指定资源输出目录，不用 output.resourcesDir：
    // 后者是懒加载 Provider，在任务配置阶段可能尚未解析，导致文件漏进 jar。
    from(layout.buildDirectory.dir("resources/agent"))
    // 合并 slf4j-api / slf4j-simple 与 ASM 进 agent jar（排除签名文件）
    from(agentRuntime.map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Premain-Class" to "com.github.chargeduck.codecallmap.agent.AgentBootstrap",
            "Agent-Class" to "com.github.chargeduck.codecallmap.agent.AgentBootstrap",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
        )
    }
}
tasks.assemble { dependsOn(agentJar) }
// buildPlugin 不经过 assemble，需显式挂接 agentJar，确保插件 zip 构建时同步产出独立 javaagent jar
tasks.named("buildPlugin") { dependsOn(agentJar) }

intellijPlatform {
    // 跳过 buildSearchableOptions（下载的 Community 平台含 SearchableOptions 依赖异常时可规避）
    buildSearchableOptions = false

    pluginConfiguration {
        // 发布坐标：id / name / version / vendor 与 plugin.xml 对应，
        // version 由构建时从 gradle.properties 注入
        id = providers.gradleProperty("pluginId").get()
        name = providers.gradleProperty("pluginName").get()
        version = pluginFullVersion
        vendor {
            name = providers.gradleProperty("pluginVendor").get()
            url = "https://github.com/chargeduck/codeCallMap"
        }

        // 刻意不在这里设置 description。
        //
        // 【踩过的坑】这里一旦赋值，patchPluginXml 会用这一句话覆盖
        // plugin.xml 里的整个 <description>，导致：
        //   1. plugin.xml 里写的富文本（列表、加粗）在插件市场全部丢失
        //   2. 多语言完全失效 —— plugin_zh.xml / plugin_en.xml 被忽略，
        //      中英文用户看到的都是这里的这一句
        // 所以描述统一交给 plugin.xml（英文）与 plugin_zh.xml（中文）维护。

        ideaVersion {
            // 兼容版本下限由分支注入：master/support-253=253，support-233=233
            sinceBuild = providers.gradleProperty("sinceBuild").get()
        }
    }

    publishing {
        // 发布到 JetBrains Marketplace 的 Token，通过环境变量注入（如 IJ_PLUGIN_TOKEN）
        token = providers.environmentVariable("IJ_PLUGIN_TOKEN")
        channels = listOf("stable")
    }
}

// ===== 发包产物：文件名带 build 号 =====
// 每次给用户发包时递增 gradle.properties 里的 pluginBuild，
// 然后执行 ./gradlew packageRelease，release/ 下会生成：
//   codeCallMap-<version>-b<build>-<branch>-<platformType><platformVersion>.zip
// 例如 codeCallMap-0.0.1-b2-support253-IU2025.3.1.zip
//
// 为什么单独做一个任务而不是改 buildPlugin 的输出名：
// buildPlugin 的产物名被 JetBrains 插件约定（<name>-<version>.zip）约束，
// 改名会影响 verifyPlugin / publishPlugin 等官方任务的识别。
// 这里保留官方产物不变，只额外复制一份到 release/ 并附加分支/平台信息。
//
// 产物命名：codeCallMap-<四段版本>-<分支>-<平台类型><平台版本>.zip
/**
 * 按语义递增版本号并写回 gradle.properties。
 *
 * <p>用法：{@code ./gradlew bumpVersion -Pbump=fix|feat|major|build}
 *
 * <h2>核心规则：构建号只增不清</h2>
 * <pre>
 *   major.feature.fix.build
 *                       ↑
 *                       └── 第几次构建，永远 +1，绝不清零
 *
 *   修 bug      1.3.3.27 → 1.3.4.28
 *   加功能      1.3.3.27 → 1.4.0.28
 *   重构/发版    1.3.3.27 → 2.0.0.28
 *   仅重新打包   1.3.3.27 → 1.3.3.28
 * </pre>
 *
 * <p>语义段（feature / fix）递增时归零，但<b>构建号继续累加</b>。
 * 理由：构建号是"这是第几次构建"的单调流水号，
 * 跟着清零会出现 1.3.4.0 这种"第 0 次构建"，语义说不通，
 * 发布包文件名也会与历史重名，无法区分批次。
 *
 * <p>（semver 只让 patch 归零，是因为它没有构建号这一层；
 * 加上第四段后它必须独立累加。）
 */
val bumpVersion by tasks.registering {
    group = "versioning"
    description = "按语义递增版本号（构建号只增不清）：-Pbump=fix|feat|major|build"
    doLast {
        val kind = (project.findProperty("bump") as String?)
            ?: throw GradleException("缺少 -Pbump=fix|feat|major|build")
        val file = layout.projectDirectory.file("gradle.properties").asFile
        val props = Properties()
        file.inputStream().use { props.load(it) }

        var major = props.getProperty("pluginMajor").toInt()
        var feature = props.getProperty("pluginFeature").toInt()
        var fix = props.getProperty("pluginFix").toInt()
        var build = props.getProperty("pluginBuild").toInt()

        // 构建号无条件先 +1：每次 bump 都意味着"又产生了一次可交付的构建"。
        // 它绝不能被清零 —— 那是流水号，不是语义段。
        build += 1

        when (kind) {
            "build" -> Unit                      // 只重新打包，语义段不动
            "fix" -> fix += 1
            "feat" -> { feature += 1; fix = 0 }
            "major" -> { major += 1; feature = 0; fix = 0 }
            else -> throw GradleException("未知类型: $kind（应为 fix / feat / major / build）")
        }

        // Properties 不保留注释，这里用逐行替换，保住 gradle.properties 里的说明文字
        val text = file.readText(Charsets.UTF_8)
            .replace(Regex("(?m)^pluginMajor=.*$"), "pluginMajor=$major")
            .replace(Regex("(?m)^pluginFeature=.*$"), "pluginFeature=$feature")
            .replace(Regex("(?m)^pluginFix=.*$"), "pluginFix=$fix")
            .replace(Regex("(?m)^pluginBuild=.*$"), "pluginBuild=$build")
        file.writeText(text, Charsets.UTF_8)
        logger.lifecycle("[bumpVersion] $kind → $major.$feature.$fix.$build")
    }
}

// 例如 codeCallMap-0.0.1.15-support253-IU2025.3.1.zip
// （版本号本身已含 build 号，不再额外加 -b 前缀）
val packageRelease by tasks.registering {
    group = "distribution"
    description = "把插件 zip 复制到 release/，文件名带四段版本号与分支/平台信息"
    dependsOn("buildPlugin")

    // 直接用顶部的 pluginFullVersion 常量。
    // 不要在这里重新拼 providers —— 任务配置阶段拿到的 provider
    // 可能还没解析成字符串，会拼出错误文件名。
    val version = pluginFullVersion
    val platformType = providers.gradleProperty("platformType")
    val platformVersion = providers.gradleProperty("platformVersion")
    val distDir = layout.buildDirectory.dir("distributions")
    val releaseDir = layout.projectDirectory.dir("release")

    doLast {
        // 按当前版本号精确匹配，不能取"第一个 zip"。
        //
        // 【踩过的坑】build/distributions/ 会保留历次打包的产物。
        // 从 support-233 切到 support-253 后重新打包时，目录里同时存在
        // codeCallMap-1.0.1.60-legacy.zip 和 codeCallMap-1.0.1.60.zip，
        // 取 first() 会拿到上一个分支的包 —— 结果是 253 的 zip 里
        // 装的却是 233 的插件（版本号 1.0.1.60-legacy、sinceBuild 233）。
        val expected = distDir.get().asFile.resolve("codeCallMap-$version.zip")
        if (!expected.isFile) {
            val existing = distDir.get().asFile.listFiles { f -> f.extension == "zip" }
                ?.joinToString("\n  ") { it.name } ?: "（目录不存在）"
            throw GradleException(
                "未找到 $version 的插件包: ${expected.name}\n" +
                        "build/distributions 现有:\n  $existing\n" +
                        "请确认 buildPlugin 已成功执行，或先执行 clean"
            )
        }
        val source = arrayOf(expected)

        // 分支名去掉连字符，避免文件名里分隔符过多（support-253 -> support253）
        val branch = try {
            val proc = providers.exec {
                commandLine("git", "rev-parse", "--abbrev-ref", "HEAD")
                isIgnoreExitValue = true
            }
            proc.standardOutput.asText.get().trim().ifBlank { "unknown" }
        } catch (e: Exception) {
            "unknown"
        }
        val branchTag = branch.replace("-", "")

        val targetName = "codeCallMap-$version" +
                "-${branchTag}-${platformType.get()}${platformVersion.get()}.zip"
        val target = releaseDir.file(targetName).asFile
        target.parentFile.mkdirs()

        source.first().copyTo(target, overwrite = true)
        logger.lifecycle("[packageRelease] 版本 $version"
                + "（大版本.新增功能.修复BUG.构建号）")
        logger.lifecycle("[packageRelease] 已生成: release/$targetName")
    }
}

// ===== Web 前端资源注入 =====
// 前端页面位于 src/main/resources/web（随仓库提交，单文件 index.html，IIFE 格式兼容 file:///）。
// 外部独立构建产物可通过 -PwebDistDir=<路径> 覆盖（copyWebDist 校验目录存在）。
val webSrcDir = file("${projectDir}/src/main/resources/web")

private fun requireWebDist() {
    if (!webSrcDir.exists() || !file("${webSrcDir}/index.html").exists()) {
        throw GradleException("未找到前端页面目录：${webSrcDir}，请确认 src/main/resources/web/index.html 存在")
    }
}

// 校验并拷贝前端产物到构建输出（发布 zip 内 src/main/resources 已天然包含 web/）
val copyWebDist = tasks.register<Copy>("copyWebDist") {
    doFirst { requireWebDist() }
    from(webSrcDir)
    into(layout.buildDirectory.dir("generated/web"))
}

// ===== agent jar 随插件分发 =====
// Run 配置注入的 -javaagent 需要指向一个真实文件，而插件安装在 IDE 里时
// build/libs 下的 agent jar 并不存在。因此把 agent jar 作为插件资源一并打包，
// 运行时由 AgentJarLocator 从 jar 内提取到临时目录再注入。
// 这样「装完插件 → 选 CodeCallMap 运行」这条链路开箱即用，无需用户手工配置路径。
//
// 注意依赖方向：embedAgent 依赖 agentJar，而 agentJar 只依赖「已编译的 class 输出」
// （sourceSets.main.output.classesDirs），不依赖 processResources，
// 否则会形成 agentJar → classes → processResources → embedAgent → agentJar 的循环依赖。
val embedAgent by tasks.registering(Copy::class) {
    dependsOn(agentJar)
    from(agentJar.flatMap { it.archiveFile })
    into(layout.buildDirectory.dir("generated/agent"))
    rename { "codeCallMap-agent.jar" }
}

sourceSets.main {
    // 注意：这里是「追加」一个资源目录，不是替换。
    // Groovy/Kotlin DSL 的 resources.srcDir(x) 是 add，不会清空默认的 src/main/resources，
    // 因此 src/main/resources/web/index.html（Vue 前端产物）仍会正常进包。
    resources.srcDir(layout.buildDirectory.dir("generated/agent"))
}
tasks.named("processResources") { dependsOn(embedAgent) }

// 将前端资源注入 runIde 沙箱（沙箱 IDE 打开 ToolWindow 时通过 file:/// 加载 web/index.html）
//
// 【曾经踩过的坑】早期写成：
//     tasks.named<PrepareSandboxTask>("prepareSandbox") {
//         from(webSrcDir); into("${project.name}/web")
//         from(agentJar);  into("${project.name}/agent")
//     }
// 这是错的：`into()` 设置的是【整个任务的根目录】，不是「当前 from 的目标」。
// 后一个 into() 会覆盖前一个，最终 index.html 和 agent jar 被直接扔到
// sandbox/plugins/ 根目录下，插件 jar 反而没被正确安装 ——
// 沙箱里出现 codeCallMap-agent-x.x.x.jar 和 index.html 两个"伪插件"，
// 启动时报 ClassNotFoundException: ...exec.CcmRunExecutor。
//
// 正确写法：把复制动作延迟到 doLast，用 project.copy 的 from/into 配对。
// 这样每对 from-into 各自独立，不会互相覆盖。
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>("prepareSandbox") {
    doFirst { requireWebDist() }
    dependsOn(agentJar)
    doLast {
        // 沙箱插件目录：<sandbox>/<IDE 版本目录>/plugins/<插件名>/
        // IDE 版本目录（如 IU-2025.3.1）随平台版本变化，因此不写死，
        // 而是逐层向下找 "plugins/<插件名>"。
        val sandboxRoots = listOf(
            layout.projectDirectory.dir(".intellijPlatform/sandbox/${project.name}").asFile,
            layout.buildDirectory.dir("idea-sandbox").get().asFile
        )
        val pluginRoot = sandboxRoots.asSequence()
            .filter { it.isDirectory }
            .flatMap { root ->
                sequence {
                    // 直接子目录 + 一级版本目录，覆盖两种布局
                    yieldAll(root.listFiles { f -> f.isDirectory }.orEmpty().asSequence()
                        .map { it.resolve("plugins/${project.name}") })
                    yield(root.resolve("plugins/${project.name}"))
                }
            }
            .firstOrNull { it.isDirectory }

        if (pluginRoot == null) {
            logger.warn("[webSandbox] 未找到沙箱插件目录，跳过前端资源注入。"
                + " 已尝试根目录: " + sandboxRoots.joinToString { it.path })
            return@doLast
        }
        // 1) 前端页面 → <插件>/web/
        copy {
            from(webSrcDir)
            into(pluginRoot.resolve("web"))
        }
        // 2) agent jar → <插件>/agent/
        copy {
            from(agentJar.flatMap { it.archiveFile })
            into(pluginRoot.resolve("agent"))
        }
        // 3) 清理历史上被错误铺到 plugins/ 根目录的文件
        //    （旧配置里 into() 覆盖导致 index.html / agent jar 被扔到这里）
        val pluginsRoot = pluginRoot.parentFile
        listOf("index.html").forEach { stray ->
            val f = pluginsRoot.resolve(stray)
            if (f.isFile) {
                f.delete()
                logger.lifecycle("[webSandbox] 已清理残留: ${f.path}")
            }
        }
        pluginsRoot.listFiles { f ->
            f.isFile && f.name.startsWith("${project.name}-agent") && f.extension == "jar"
        }?.forEach {
            it.delete()
            logger.lifecycle("[webSandbox] 已清理残留: ${it.path}")
        }
        logger.lifecycle("[webSandbox] 已注入前端页面与 agent jar 到: $pluginRoot")
    }
}

/**
 * 把开发期需要的 Registry 值写进沙箱 IDE 的配置文件。
 *
 * <h2>为什么写文件而不是加 -D 参数</h2>
 * 实测（2025.3.1）Registry 的用户值持久化在
 * <pre>
 *   &lt;sandbox&gt;/&lt;IDEVERSION&gt;/config/options/ide.general.xml
 *     &lt;component name="Registry"&gt;
 *       &lt;entry key="ide.browser.jcef.debug.port" value="9222" source="USER"/&gt;
 * </pre>
 * 注意 {@code source="USER"} —— 只有带这个标记的才会覆盖 IDE 内置默认值。
 * 直接传 {@code -Dide.browser.jcef.debug.port=9222} 不在所有版本上都生效：
 * Registry 读的是自己的组件存储，不是系统属性。
 *
 * <h2>解决什么问题</h2>
 * 手工在 Registry 面板里设置的值，会随沙箱重建（clean / 换 IDE 版本 /
 * 删沙箱目录）一起丢失，每次都要重新点一遍，且改完必须重启 IDE。
 * 放到 prepareSandbox 里，则每次准备沙箱都自动补齐，不必人工干预。
 *
 * <h2>为什么用文本处理而不是 XML 解析后回写</h2>
 * 该文件由 IDE 写出，含大量无换行的 CDATA 段落。用 DOM 解析再序列化
 * 会把整个文件的格式重排（几万字符挤成一行），diff 噪音巨大且可能
 * 改变 IDE 自己的解析预期。因此这里只做"找到 entry 就替换、没有就插入"。
 *
 * <h2>2025.3.1 实际存在的 ide.browser.jcef.* 键（从 IDE 类常量池提取）</h2>
 * <pre>
 *   debug.port                     调试端口（本任务默认写入）
 *   debug.port.random.enabled      端口随机化，会覆盖 debug.port
 *   debug.js                       调试 JS
 *   gpu.disable                    禁用 GPU 加速（排障用，常开会明显变慢）
 *   osr.enabled                    离屏渲染开关
 *   contextMenu.devTools.enabled   右键菜单里的 Inspect
 *   log.level / log.path / log     日志级别与路径
 *   extra.args                     追加 CEF 启动参数
 *   sandbox.enable / sandbox.disable
 *   jsQueryPoolSize                JS 查询池大小
 *   preinit / skip / resize / headless.enabled
 * </pre>
 * 注意：只有 {@code gpu.disable}。
 * 写不存在的键不会有任何效果（IDE 静默忽略），这类笔误很难自查，
 * 所以上面这份清单是从 IDE 自身提取的真实键名。
 */
val seedSandboxRegistry by tasks.registering {
    group = "intellij platform"
    description = "把 jcefDebugPort 写进沙箱 IDE 的 Registry 配置"

    doLast {
        val port = (project.findProperty("jcefDebugPort") as String?)?.trim()?.toIntOrNull() ?: 0
        if (port <= 0) {
            logger.lifecycle("[seedRegistry] jcefDebugPort 未配置或为 0，跳过")
            return@doLast
        }

        // 定位沙箱根目录（兼容 .intellijPlatform/sandbox 与 build/idea-sandbox 两种布局）
        // 去重：projectDirectory 与 buildDirectory 在不同布局下可能指向同一处，
        // 不去重会把同一份配置写两遍（日志里也会重复打印）。
        val roots = listOf(
            layout.projectDirectory.dir(".intellijPlatform/sandbox/${project.name}").asFile,
            layout.buildDirectory.dir("idea-sandbox").get().asFile
        ).filter { it.isDirectory }
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }

        // 沙箱下通常有一层 IDE 版本目录（如 IU-2025.3.1）
        val ideDirs = roots.flatMap { root ->
            val versioned = root.listFiles { f -> f.isDirectory && f.name.contains("-20") }
                ?.toList().orEmpty()
            if (versioned.isNotEmpty()) versioned else listOf(root)
        }
        if (ideDirs.isEmpty()) {
            logger.warn("[seedRegistry] 未找到沙箱目录，跳过。已尝试: "
                + roots.joinToString { it.path })
            return@doLast
        }

        fun ensureEntry(text: String, key: String, value: String): String {
            val entryLine = Regex("""<entry\s+key="$key"[^/>]*/>""")
            if (entryLine.containsMatchIn(text)) {
                // 已存在：覆盖 value 与 source（source 必须是 USER 才盖得住内置默认）
                return entryLine.replace(text,
                    """<entry key="$key" value="$value" source="USER" />""")
            }
            // 不存在：插到 Registry 组件内第一个子元素之前；没有子元素就补一对标签
            val compOpen = Regex("""<component\s+name="Registry"\s*>""")
            val m = compOpen.find(text)
            if (m != null) {
                val at = m.range.last + 1
                return text.substring(0, at) + "\n    " +
                        """<entry key="$key" value="$value" source="USER" />""" +
                        text.substring(at)
            }
            // 连 component 都没有：在 </application> 前新增一个完整组件
            val closeApp = text.lastIndexOf("</application>")
            val block = """
                  <component name="Registry">
                    <entry key="$key" value="$value" source="USER" />
                  </component>
                """.trimIndent()
            return if (closeApp >= 0) {
                text.substring(0, closeApp) + block + "\n" + text.substring(closeApp)
            } else {
                text
            }
        }

        var written = 0
        // 需要写入的键值对。
        //   debug.port  ← 单独由 jcefDebugPort 控制（有默认值校验）
        //   其余键      ← 由 jcefRegistry 的逗号分隔列表提供，
        //                 默认值写在 gradle.properties，命令行 -P 可覆盖。
        //
        // 之前这里只读命令行，导致 gradle.properties 里配的键不会生效，
        // 用户写了 4 个值却只有 debug.port 落盘。findProperty 本身
        // 既读 gradle.properties 也读 -P，两者都能拿到。
        val desired = linkedMapOf<String, String>()
        desired["ide.browser.jcef.debug.port"] = port.toString()
        (project.findProperty("jcefRegistry") as String?)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it.contains('=') }
            ?.forEach { pair ->
                val k = pair.substringBefore('=').trim()
                val v = pair.substringAfter('=').trim()
                if (k.isNotEmpty() && v.isNotEmpty()) desired[k] = v
            }

        ideDirs.forEach { ideDir ->
            val optDir = ideDir.resolve("config/options")
            val file = optDir.resolve("ide.general.xml")
            try {
                optDir.mkdirs()
                val original = if (file.isFile) file.readText(Charsets.UTF_8) else
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<application>\n</application>\n"
                var text = original
                desired.forEach { (k, v) -> text = ensureEntry(text, k, v) }
                if (text != original) {
                    file.writeText(text, Charsets.UTF_8)
                    written++
                    logger.lifecycle("[seedRegistry] 已写入 ${desired.size} 项 → ${file.path}")
                    desired.forEach { (k, v) -> logger.lifecycle("[seedRegistry]   $k = $v") }
                } else {
                    logger.lifecycle("[seedRegistry] 配置已是目标值，无需改动: ${file.path}")
                }
            } catch (e: Exception) {
                logger.warn("[seedRegistry] 写入失败 ${file.path}: ${e.message}")
            }
        }
        if (written == 0) {
            logger.lifecycle("[seedRegistry] 无需写入（端口 $port）")
        }
    }
}

// runIde → prepareSandbox（本来就有依赖）→ 执行完紧跟 seedSandboxRegistry。
// 不要在 runIde 上再加 dependsOn：那会让 seed 在一次构建里跑两遍
// （一遍作为 runIde 的直接依赖，一遍作为 prepareSandbox 的收尾）。
tasks.named("prepareSandbox") { finalizedBy(seedSandboxRegistry) }

tasks.assemble { dependsOn(embedAgent) }