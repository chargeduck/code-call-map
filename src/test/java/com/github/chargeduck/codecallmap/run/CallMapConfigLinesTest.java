package com.github.chargeduck.codecallmap.run;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CallMapConfigLines} 的配置解析测试。
 *
 * <p>这个文件会被用户手工编辑（换目录、调包前缀），
 * 解析必须能容忍注释、空行、引号等常见写法。
 */
class CallMapConfigLinesTest {

    @Test
    void parsesKeyValuePairs() {
        Map<String, String> m = CallMapConfigLines.parse(Arrays.asList(
                "outputDir: ${projectPath}\\.callmap",
                "monitorPackages: com.github.chargeduck",
                "sampleRate: 100",
                "minDurationMs: 1"
        ));
        // Windows 路径含冒号，必须只按【第一个】冒号切分
        assertEquals("${projectPath}\\.callmap", m.get("outputDir"));
        assertEquals("com.github.chargeduck", m.get("monitorPackages"));
        assertEquals("100", m.get("sampleRate"));
    }

    @Test
    void ignoresCommentsAndBlankLines() {
        Map<String, String> m = CallMapConfigLines.parse(Arrays.asList(
                "# 这是注释",
                "",
                "   ",
                "# another comment: with colon",
                "sampleRate: 50"
        ));
        assertEquals(1, m.size());
        assertEquals("50", m.get("sampleRate"));
    }

    @Test
    void stripsQuotes() {
        Map<String, String> m = CallMapConfigLines.parse(Arrays.asList(
                "outputDir: \"/path/with/quotes\"",
                "monitorPackages: 'com.github'"
        ));
        assertEquals("/path/with/quotes", m.get("outputDir"));
        assertEquals("com.github", m.get("monitorPackages"));
    }

    @Test
    void skipsLinesWithoutColon() {
        Map<String, String> m = CallMapConfigLines.parse(Arrays.asList(
                "garbage line",
                "key: value"
        ));
        assertEquals(1, m.size());
        assertTrue(m.containsKey("key"));
        assertFalse(m.containsKey("garbage line"));
    }

    @Test
    void emptyValueIsPreservedAsEmptyString() {
        // outputDir: （留空）表示"用默认位置"
        Map<String, String> m = CallMapConfigLines.parse(Arrays.asList("outputDir:"));
        assertEquals("", m.get("outputDir"));
    }

    @Test
    void emptyListYieldsEmptyMap() {
        assertTrue(CallMapConfigLines.parse(java.util.Collections.emptyList()).isEmpty());
        assertTrue(CallMapConfigLines.parse(null).isEmpty());
    }
}
