package com.github.chargeduck.codecallmap.exec;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.impl.DefaultJavaProgramRunner;
import org.jetbrains.annotations.NotNull;

/**
 * 「Run with CCM」的执行 Runner。
 *
 * <h2>实现方式</h2>
 * 直接继承 IDEA 原生的 {@link DefaultJavaProgramRunner}，
 * 只覆写 runner id、认领条件、以及 {@link #patch} 里的参数注入。
 *
 * <p>这样做的三个好处：
 * <ol>
 *   <li><b>启动逻辑零重复</b>：进程创建、控制台、停止全部沿用原生实现；</li>
 *   <li><b>注入时机天然正确</b>：{@code patch} 只会被"当前这个 runner 掌管的启动"调用，
 *       所以不需要任何标记就能判断"这是 CCM 启动"；</li>
 *   <li><b>绝不污染用户配置</b>：注入写在 {@link JavaParameters}（本次进程参数）上，
 *       不碰 {@code ApplicationConfiguration}。</li>
 * </ol>
 *
 * <h2>⚠️ 之前的错误设计</h2>
 * 早期版本没有继承原生 runner，而是"把执行器换成原生的再委托"。
 * 那样做等于把自己的执行身份让了出去，导致：
 * <ul>
 *   <li>注入只能提前写进配置对象 → 点过一次 CCM 后，原生 Run 也被插桩；</li>
 *   <li>CCM agent 与 IDEA 的 debugger-agent 同时生效，
 *       报 {@code LinkageError: attempted duplicate abstract class definition
 *       for org.apache.catalina.loader.WebappClassLoaderBase}，应用起不来。</li>
 * </ul>
 * 现改为继承原生 runner，问题从根上消失。
 */
public final class CcmRunProgramRunner extends DefaultJavaProgramRunner {

    @Override
    public @NotNull String getRunnerId() {
        return "CCM.Runner." + CcmRunExecutor.ID;
    }

    /**
     * 只认领 CCM 的 Run 执行器，且配置是 Java 应用类型。
     *
     * <p>先判 executorId 再判配置类型：绝大多数调用会在第一行返回，
     * 避免对每次原生运行都做后面的类型检查。
     */
    @Override
    public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
        if (!CcmRunExecutor.ID.equals(executorId)) {
            return false;
        }
        return profile instanceof com.intellij.execution.configurations.RunProfileWithCompileBeforeLaunchOption;
    }

    /**
     * 在本次启动的 VM 参数里追加 {@code -javaagent}。
     *
     * <p>必须调用 {@code super.patch(...)}：父类会做 classpath / 环境变量等
     * 平台必需的补丁，漏掉会导致类加载异常。
     */
    @Override
    public void patch(@NotNull JavaParameters javaParameters,
                      @NotNull RunnerSettings settings,
                      @NotNull RunProfile runProfile,
                      boolean beforeExecution) {
        // 先让父类完成平台自身的 patch，再追加 agent
        super.patch(javaParameters, settings, runProfile, beforeExecution);
        CcmAgentInjector.inject(javaParameters, runProfile);
    }

    /**
     * 进程启动后登记 Spring Boot JMX 会话，让 Services 面板能显示端口号。
     *
     * <h2>为什么是 onProcessStarted 而不是 execute</h2>
     * 2025.3 的 {@code ProgramRunner.execute()} 返回 void，拿不到进程句柄；
     * {@code onProcessStarted(settings, result)} 是平台在进程启动后回调的默认方法，
     * {@code result.getProcessHandler()} 正是 Spring Boot 插件登记会话所需要的。
     * 原生 Spring Boot 配置由它自己的 runner 做这件事，CCM 用自定义 runner 就得自己补。
     *
     * <p>失败仅影响端口号显示，不影响启动与采集，因此整段 try/catch 兜住。
     */
    @Override
    public void onProcessStarted(@NotNull RunnerSettings settings,
                                 @NotNull ExecutionResult result) {
        super.onProcessStarted(settings, result);
        try {
            // 与 patcher 阶段的 prepare() 成对：取回暂存的 setup，完成进程登记。
            // 没有这一步，Dashboard 的 getServerPort() 就没有宿主，端口号不显示。
            SpringBootJmxBridge.completeOnProcessStarted(result);
        } catch (Throwable ignored) {
            // 端口号是附加能力，任何异常都不应影响运行
        }
    }
}
