package com.github.chargeduck.codecallmap.exec;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring Boot 应用识别逻辑测试。
 *
 * <h2>端口号显示的完整链路（反编译 Spring Boot 插件确认）</h2>
 * <pre>
 * SpringBootJmxSetup.LocalSetup
 *   .setupJmxParameters(params, LocalTargetEnvironmentRequest)  // 注入 -D，并把 myJmxPort 设为正数
 *        ↓
 *   .setupProcessHandler(processHandler)   // 第一行就是 if (myJmxPort <= 0) return;
 *        ↓  登记到 SpringBootApplicationLifecycleManager
 * Dashboard.getServerPort()  → 渲染到 Services / Run 面板
 * </pre>
 *
 * <h2>踩过的两个坑</h2>
 * <ol>
 *   <li><b>用配置类名判断</b>"是不是 Spring Boot" —— 用户跑的是
 *       {@code CodeCallMapRunConfiguration}，类名不含 SpringBoot，判断恒 false；</li>
 *   <li><b>手工拼 5 个 {@code -D} 参数</b> —— 命令行与原生逐字一致，
 *       但实例里的 {@code myJmxPort} 仍是 -1，{@code setupProcessHandler} 直接 return。</li>
 * </ol>
 *
 * <p>这两个坑都无法靠"看参数对不对"发现，所以判据必须是
 * <b>classpath 里有没有 spring-boot</b>。
 */
class CcmJmxParamsTest {

    @Test
    void detectsSpringBootFromClassPath() {
        // 典型 Spring Boot 项目 classpath（摘自 demo 的真实启动日志）
        assertTrue(SpringBootJmxParams.classPathHasSpringBoot(Arrays.asList(
                "${projectPath}\\demo-app\\target\\classes",
                "${mavenRepo}\\org\\springframework\\boot\\spring-boot-starter-web\\2.7.18\\spring-boot-starter-web-2.7.18.jar",
                "${mavenRepo}\\org\\springframework\\boot\\spring-boot-2.7.18.jar",
                "${mavenRepo}\\org\\springframework\\spring-core\\5.3.31\\spring-core-5.3.31.jar")),
                "classpath 里有 spring-boot-*.jar 就该判定为 Spring Boot 应用");
    }

    /** 只有 spring-core / spring-webmvc（普通 Spring 项目，非 Boot）。 */
    @Test
    void plainSpringProjectIsNotSpringBoot() {
        assertFalse(SpringBootJmxParams.classPathHasSpringBoot(Arrays.asList(
                "${mavenRepo}\\org\\springframework\\spring-core\\5.3.31\\spring-core-5.3.31.jar",
                "${mavenRepo}\\org\\springframework\\spring-webmvc\\5.3.39\\spring-webmvc-5.3.39.jar")),
                "只有 spring-core / spring-webmvc 不代表是 Spring Boot");
    }

    /** Windows 路径大小写不敏感，判定必须忽略大小写。 */
    @Test
    void detectionIsCaseInsensitive() {
        assertTrue(SpringBootJmxParams.classPathHasSpringBoot(Arrays.asList(
                "E:\\Maven\\Repository\\Org\\SpringFramework\\Boot\\Spring-Boot-2.7.18.JAR")));
    }

    @Test
    void handlesNullAndEmpty() {
        assertFalse(SpringBootJmxParams.classPathHasSpringBoot(null));
        assertFalse(SpringBootJmxParams.classPathHasSpringBoot(Collections.emptyList()));
        assertFalse(SpringBootJmxParams.classPathHasSpringBoot(Arrays.asList((String) null)));
    }

    /** 目录形式的 spring-boot 条目（未打包）不算 —— 只有 jar 才是真依赖。 */
    @Test
    void springBootDirectoryIsNotTreatedAsJar() {
        assertFalse(SpringBootJmxParams.classPathHasSpringBoot(Arrays.asList(
                "E:\\some\\spring-boot\\classes")));
    }
}
