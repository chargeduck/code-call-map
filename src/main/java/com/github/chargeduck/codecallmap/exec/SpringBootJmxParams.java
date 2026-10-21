package com.github.chargeduck.codecallmap.exec;

/**
 * Spring Boot 应用的识别逻辑（<b>纯 Java，不依赖 IntelliJ SDK</b>）。
 *
 * <h2>为什么需要单独一个类</h2>
 * {@code unitTest} 是纯 JVM 任务（不带 IntelliJ 平台），而
 * {@link CcmAgentInjector}、{@code CodeCallMapProgramPatcher} 一加载就会触发
 * 平台 {@code Logger} 的类初始化，在单测里直接 {@code NoClassDefFoundError}。
 * 把纯逻辑抽出来才能在不启动平台的前提下断言。
 *
 * <h2>为什么用 classpath 而不是配置类型名判断（踩过的坑）</h2>
 * 第一版用「配置类名含 SpringBoot」判断，结果对用户<b>完全无效</b>：
 * 用户在 IDEA 里跑的是我们自己的 {@code CodeCallMapRunConfiguration}
 * （见 {@code .idea/workspace.xml} 的 {@code type="CodeCallMapRunConfiguration"}），
 * 类名里根本没有 SpringBoot 字样 —— 判断恒为 false，一个参数都没补上。
 *
 * <p>结论：<b>配置类型名反映的是"谁提供的配置 UI"，与"目标应用是不是
 * Spring Boot"毫无关系</b>。classpath 才是目标应用的真实依赖。
 *
 * <h2>参数清单为什么也搬走了</h2>
 * 手工拼 {@code -D} 参数的方案已废弃（见
 * {@link SpringBootJmxBridge#prepare}）：手工写参数绕过了
 * {@code setupJmxParameters} 对 {@code myJmxPort} 的赋值，
 * 导致 {@code setupProcessHandler} 直接 return，端口号仍然不显示。
 * 现在参数完全交给 Spring Boot 插件自己生成，这里只保留"要不要处理"的判断。
 */
public final class SpringBootJmxParams {

    private SpringBootJmxParams() {
    }

    /**
     * 从 classpath 条目判断目标应用是不是 Spring Boot。
     *
     * <p>只要有 {@code spring-boot-*.jar} 就认定是 Spring Boot 应用，
     * 与用哪个 Run Configuration 无关。
     *
     * @param classPathEntries 本次运行的 classpath 条目（可为 null）
     * @return true 表示检出 Spring Boot
     */
    public static boolean classPathHasSpringBoot(java.util.List<String> classPathEntries) {
        if (classPathEntries == null) {
            return false;
        }
        for (String entry : classPathEntries) {
            if (entry == null) {
                continue;
            }
            // 统一小写：Windows 上路径大小写不敏感，
            // 用原样匹配可能漏掉 Spring-Boot-Xxx.jar 这类写法
            String lower = entry.toLowerCase();
            if (lower.contains("spring-boot") && lower.endsWith(".jar")) {
                return true;
            }
        }
        return false;
    }
}
