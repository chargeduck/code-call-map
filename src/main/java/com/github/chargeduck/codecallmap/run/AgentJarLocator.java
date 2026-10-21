package com.github.chargeduck.codecallmap.run;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * agent jar 路径定位器。按优先级依次尝试：
 * <ol>
 *   <li><b>设置面板显式配置的 {@code agentJarPath}</b>（校验文件存在）；</li>
 *   <li><b>插件自身安装目录下的 {@code <plugin>/agent/}</b>——开发期 runIde 沙箱布局；</li>
 *   <li><b>项目 {@code build/libs} 下最新的 {@code *-agent-*.jar}</b>——插件源码开发期，
 *       用户在同一个项目里改 agent 代码后立即可用；</li>
 *   <li><b>从插件 jar 内提取内嵌的 {@code /agent/codeCallMap-agent.jar}</b>——
 *       用户从 Marketplace 安装插件后的开箱即用路径（此时磁盘上没有任何 agent 文件）。</li>
 * </ol>
 * 都找不到时返回空串，由调用方决定是否提示用户。
 */
public final class AgentJarLocator {

    /** agent jar 文件名标识。 */
    private static final String AGENT_MARKER = "-agent-";

    /** 内嵌资源的固定名称（由 build.gradle.kts 的 embedAgent 任务写入）。 */
    private static final String EMBEDDED_AGENT = "codeCallMap-agent.jar";

    /** 提取目录（进程内复用，避免每次启动都解压）。 */
    private static volatile Path extractedAgent;

    private AgentJarLocator() {
    }

    /** 返回可用的 agent jar 绝对路径；找不到时返回空串。 */
    public static String resolveAgentJarPath(@Nullable Project project) {
        // 1. 显式配置优先
        CodeCallMapSettingsState settings = CodeCallMapSettingsState.getInstance();
        String configured = settings.agentJarPath == null ? "" : settings.agentJarPath.trim();
        if (!configured.isEmpty()) {
            Path p = Paths.get(configured);
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath().toString();
            }
        }

        // 2. 插件安装目录 / 沙箱下的 agent/
        Path bundled = findBundledAgentJar();
        if (bundled != null) {
            return bundled.toAbsolutePath().toString();
        }

        // 3. 项目 build/libs（开发期）
        Path libsDir = findBuildLibsDir(project);
        if (libsDir != null) {
            File jar = findNewestAgentJar(libsDir);
            if (jar != null) {
                return jar.getAbsolutePath();
            }
        }

        // 4. 从插件 jar 内提取内嵌 agent（Marketplace 安装场景）
        Path embedded = extractEmbeddedAgentJar();
        if (embedded != null) {
            return embedded.toAbsolutePath().toString();
        }
        return "";
    }

    /**
     * 从插件 jar 内提取内嵌的 agent jar 到临时目录。
     *
     * <p>为什么不在插件里直接引用 jar 内路径：{@code -javaagent} 要求一个真实的文件路径，
     * JVM 无法从 jar 内部条目加载 agent。因此必须落地成文件。
     */
    @Nullable
    private static Path extractEmbeddedAgentJar() {
        Path cached = extractedAgent;
        if (cached != null && Files.isRegularFile(cached)) {
            return cached;
        }
        synchronized (AgentJarLocator.class) {
            if (extractedAgent != null && Files.isRegularFile(extractedAgent)) {
                return extractedAgent;
            }
            try (java.io.InputStream in = AgentJarLocator.class.getResourceAsStream("/" + EMBEDDED_AGENT)) {
                if (in == null) {
                    return null;
                }
                Path tmp = Files.createTempFile("codecallmap-agent-", ".jar");
                tmp.toFile().deleteOnExit();
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                extractedAgent = tmp;
                return tmp;
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /**
     * 在插件安装目录下寻找随包分发的 agent jar。
     *
     * <p>实现方式：用本类自身的 class 位置反推插件根目录——
     * 插件 jar 位于 {@code <plugin>/lib/}，而 agent jar 位于 {@code <plugin>/agent/}。
     */
    @Nullable
    private static Path findBundledAgentJar() {
        try {
            URL self = AgentJarLocator.class.getProtectionDomain().getCodeSource().getLocation();
            if (self == null) {
                return null;
            }
            Path selfPath = Paths.get(self.toURI());
            // selfPath 形如 <plugin>/lib/<plugin>.jar
            Path libDir = Files.isDirectory(selfPath) ? selfPath : selfPath.getParent();
            if (libDir == null || libDir.getParent() == null) {
                return null;
            }
            Path agentDir = libDir.getParent().resolve("agent");
            if (!Files.isDirectory(agentDir)) {
                return null;
            }
            File jar = findNewestAgentJar(agentDir);
            return jar == null ? null : jar.toPath();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private static Path findBuildLibsDir(@Nullable Project project) {
        if (project == null || project.getBasePath() == null) {
            return null;
        }
        Path p = Paths.get(project.getBasePath(), "build", "libs");
        return Files.isDirectory(p) ? p : null;
    }

    /** 在目录下寻找最新的 {@code *-agent-*.jar}。 */
    @Nullable
    private static File findNewestAgentJar(Path dir) {
        File[] jars = dir.toFile().listFiles((d, name) ->
                name.endsWith(".jar") && name.contains(AGENT_MARKER));
        if (jars == null || jars.length == 0) {
            // 放宽匹配：有些构建会输出 codeCallMap-agent.jar（无版本号）
            jars = dir.toFile().listFiles((d, name) -> name.endsWith(".jar") && name.contains("agent"));
        }
        if (jars == null || jars.length == 0) {
            return null;
        }
        return Arrays.stream(jars)
                .max(Comparator.comparingLong(File::lastModified))
                .orElse(null);
    }

    /** 供 UI 展示：当前解析到的候选路径列表（便于用户排查"为什么没生效"）。 */
    @NotNull
    public static List<String> describeCandidates(@Nullable Project project) {
        List<String> out = new ArrayList<>();
        CodeCallMapSettingsState s = CodeCallMapSettingsState.getInstance();
        out.add("设置项 agentJarPath: " + (s.agentJarPath == null || s.agentJarPath.isBlank() ? "(未配置)" : s.agentJarPath));
        Path bundled = findBundledAgentJar();
        out.add("插件内置 agent: " + (bundled == null ? "(未找到)" : bundled.toString()));
        Path libs = findBuildLibsDir(project);
        out.add("项目 build/libs: " + (libs == null ? "(不存在)" : libs.toString()));
        out.add("最终解析结果: " + (resolveAgentJarPath(project).isEmpty() ? "(空，将不会注入)" : resolveAgentJarPath(project)));
        return out;
    }
}
