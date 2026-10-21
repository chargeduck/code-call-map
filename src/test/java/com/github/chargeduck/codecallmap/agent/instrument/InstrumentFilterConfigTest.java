package com.github.chargeduck.codecallmap.agent.instrument;

import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InstrumentFilterConfig} 的 YAML 解析测试。
 */
class InstrumentFilterConfigTest {

    private static InstrumentFilterConfig parse(String text) throws Exception {
        return InstrumentFilterConfig.parse(new StringReader(text), "test");
    }

    @Test
    void parsesStandardYamlLists() throws Exception {
        InstrumentFilterConfig c = parse("""
                include:
                  - com.acme.lhp
                  - com.acme.frm
                exclude:
                  - org.apache.catalina
                """);
        assertEquals(2, c.include.size());
        assertEquals("com.acme.lhp", c.include.get(0));
        assertEquals("com.acme.frm", c.include.get(1));
        assertEquals(1, c.exclude.size());
        assertEquals("org.apache.catalina", c.exclude.get(0));
    }

    @Test
    void ignoresCommentsAndBlankLines() throws Exception {
        InstrumentFilterConfig c = parse("""
                # 这是注释
                include:

                  # 中间也有注释
                  - com.demo
                """);
        assertEquals(1, c.include.size());
        assertEquals("com.demo", c.include.get(0));
    }

    @Test
    void supportsInlineBracketSyntax() throws Exception {
        InstrumentFilterConfig c = parse("exclude: [org.apache.catalina, java.]");
        assertEquals(2, c.exclude.size());
        assertTrue(c.exclude.contains("java."));
    }

    @Test
    void supportsPlainTextListWithoutDash() throws Exception {
        // 不熟悉 YAML 的用户可能直接写包名，不留 "- "
        InstrumentFilterConfig c = parse("""
                include:
                com.demo
                com.other
                """);
        assertEquals(2, c.include.size());
        assertEquals("com.other", c.include.get(1));
    }

    @Test
    void stripsQuotes() throws Exception {
        InstrumentFilterConfig c = parse("""
                include:
                  - "com.quoted"
                  - 'com.single'
                """);
        assertEquals("com.quoted", c.include.get(0));
        assertEquals("com.single", c.include.get(1));
    }

    @Test
    void emptyIncludeMeansInstrumentEverythingExceptExclude() throws Exception {
        InstrumentFilterConfig c = parse("""
                include:
                exclude:
                  - java.
                """);
        assertTrue(c.include.isEmpty());
        assertEquals(1, c.exclude.size());
    }

    @Test
    void bundledDefaultConfigIsReadable() {
        // 内置默认配置必须能被读到，否则用户没配任何文件时规则会退化成最小值
        InstrumentFilterConfig c = InstrumentFilterConfig.load(null);
        assertTrue(c.exclude.size() > 10,
                "内置默认配置至少应包含十余条排除项，实际 " + c.exclude.size());
        // Tomcat 类加载器必须在排除列表里，这是 LinkageError 的根因
        assertTrue(c.exclude.stream().anyMatch(p -> p.startsWith("org.apache.catalina")),
                "Tomcat 必须在默认排除列表中");
    }
}
