package com.github.chargeduck.codecallmap.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CcmVmOptionCleaner} 的清理规则测试。
 *
 * <p>这段逻辑会删除用户运行配置里的内容，规则必须精确：
 * 只能删 CCM 自己的 agent 参数，用户手配的其它 agent 一律保留。
 */
class CcmVmOptionCleanerTest {

    @Test
    void removesCcmAgentOption() {
        String input = "-Xmx2G -javaagent:C:\\Temp\\codecallmap-agent-123.jar=callmap.output.dir=E:\\p\\.callmap -Dfoo=bar";
        assertEquals("-Xmx2G -Dfoo=bar", CcmVmOptionCleaner.removeCcmAgentOptions(input));
    }

    @Test
    void keepsUserOwnAgent() {
        // 用户自己配的 agent 必须原样保留
        String input = "-javaagent:C:\\other\\jmx-agent.jar -Xmx1G";
        assertEquals(input, CcmVmOptionCleaner.removeCcmAgentOptions(input));
    }

    @Test
    void removesOnlyCcmAmongMultipleAgents() {
        String input = "-javaagent:C:\\temp\\CodeCallMap-Agent-99.jar=a=b -javaagent:C:\\other\\jmx.jar";
        assertEquals("-javaagent:C:\\other\\jmx.jar",
                CcmVmOptionCleaner.removeCcmAgentOptions(input));
    }

    @Test
    void leavesUnrelatedOptionsUntouched() {
        String input = "-Dspring.profiles.active=local -XX:TieredStopAtLevel=1";
        assertEquals(input, CcmVmOptionCleaner.removeCcmAgentOptions(input));
    }

    @Test
    void plainVMOptionsWithNoAgentIsUnchanged() {
        assertEquals("-Xmx512m", CcmVmOptionCleaner.removeCcmAgentOptions("-Xmx512m"));
    }

    @Test
    void removesAgentWithProjectPathArgument() {
        // 真实日志里的形态：agent 参数后紧跟 callmap.output.dir
        String input = "-javaagent:C:\\Users\\x\\AppData\\Local\\Temp\\codecallmap-agent-6518560516805948708.jar=callmap.output.dir=${projectPath}\\.callmap -XX:TieredStopAtLevel=1";
        assertEquals("-XX:TieredStopAtLevel=1",
                CcmVmOptionCleaner.removeCcmAgentOptions(input));
    }
}
