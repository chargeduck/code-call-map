package com.github.chargeduck.codecallmap.webview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编辑器内联展示用的视图模型测试。
 *
 * <h2>这个类为什么值得测</h2>
 * {@code CcmCallRecord} 是纯 Java（不依赖 IntelliJ 平台），
 * 但它的输出会<b>直接占据代码行内空间</b> —— inlay 与普通灰字不同，
 * 它会把代码往右推。所以"截断到多少"不是审美问题：
 * 一个 500 字符的 JSON 会让整行代码被推到屏幕外，
 * 用户反而看不到本来要看的方法签名。
 *
 * <p>另外 {@code void} 与"返回 null"在展示上必须区分开，
 * 这正是 {@code hasReturn} 存在的理由 —— 只看 {@code ret == null} 分不出来。
 */
class CcmCallRecordTest {

    /** 正常的带参、带返回值调用。 */
    @Test
    void shortSummaryDescribesCall() {
        CcmCallRecord r = CcmCallRecord.builder()
                .spanId("s-1")
                .durationMs(42)
                .putArg("id", "7")
                .putArg("name", "\"abc\"")
                .ret("{...}", true)
                .build();

        String s = r.shortSummary();
        assertTrue(s.contains("42ms"), s);
        assertTrue(s.contains("2 参数"), s);
        assertTrue(s.contains("有返回"), s);
    }

    /** void 方法不显示"有返回"。 */
    @Test
    void voidMethodHasNoReturnMarker() {
        CcmCallRecord r = CcmCallRecord.builder().spanId("s-2").durationMs(3).build();
        assertFalse(r.hasReturn());
        assertFalse(r.shortSummary().contains("有返回"));
        assertEquals("（void，无返回值）", r.fullRetText());
        assertNull(r.shortRet(), "void 方法没有返回值短文本");
    }

    /**
     * 返回 null 与 void 必须能区分。
     *
     * <p>两者 {@code ret} 都是 null，只有 {@code hasReturn} 不同 ——
     * 这是最容易写错的一处。
     */
    @Test
    void nullReturnIsDistinctFromVoid() {
        CcmCallRecord voidMethod = CcmCallRecord.builder().spanId("a").build();
        CcmCallRecord nullReturn = CcmCallRecord.builder().spanId("b").ret(null, true).build();

        assertFalse(voidMethod.hasReturn(), "void 方法 hasReturn 应为 false");
        assertTrue(nullReturn.hasReturn(), "返回 null 的方法 hasReturn 应为 true");

        assertEquals("（void，无返回值）", voidMethod.fullRetText());
        assertEquals("null", nullReturn.fullRetText());
        assertEquals("null", nullReturn.shortRet());
    }

    /** 异常要体现在摘要里，否则用户看不出这次调用失败了。 */
    @Test
    void exceptionIsSurfacedInSummary() {
        CcmCallRecord r = CcmCallRecord.builder()
                .spanId("s-3")
                .durationMs(9)
                .exception("java.lang.IllegalStateException: boom")
                .build();
        assertTrue(r.shortSummary().contains("异常"), r.shortSummary());
        assertTrue(r.fullArgsText().contains("无参数"), "无参时应给出明确提示");
    }

    /** 参数值必须截断，否则会把整行代码推出屏幕。 */
    @Test
    void longArgValueIsTruncated() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            big.append('x');
        }
        CcmCallRecord r = CcmCallRecord.builder()
                .spanId("s-4")
                .putArg("body", big.toString())
                .build();

        String shortVal = r.shortArg("body");
        assertNotNull(shortVal);
        // 32 个字符 + 省略说明，明显短于原文
        assertTrue(shortVal.length() < 60, "短值应远小于原文，实际 " + shortVal.length());
        assertTrue(shortVal.contains("…"), "应带截断标记");
        // 完整值仍完整保留（详情面板 / tooltip 用），不能被摘要逻辑污染
        assertTrue(r.fullArgsText().contains(big.toString()), "完整文本应保留原文");
    }

    /** 换行必须压成空格，否则 inlay 会把代码行折成多行。 */
    @Test
    void newlinesAreFlattenedInShortValue() {
        CcmCallRecord r = CcmCallRecord.builder()
                .spanId("s-5")
                .putArg("text", "line1\nline2\r\nline3")
                .build();
        String v = r.shortArg("text");
        assertNotNull(v);
        assertFalse(v.contains("\n"), "短值不能含换行");
        assertFalse(v.contains("\r"), "短值不能含回车");
    }

    @Test
    void missingArgReturnsNull() {
        CcmCallRecord r = CcmCallRecord.builder().spanId("s-6").build();
        assertNull(r.shortArg("notExists"));
    }

    /** 参数顺序要保持采集时的顺序，方便和源码签名对照。 */
    @Test
    void argOrderIsPreserved() {
        CcmCallRecord r = CcmCallRecord.builder()
                .spanId("s-7")
                .putArg("a", "1")
                .putArg("b", "2")
                .putArg("c", "3")
                .build();
        String full = r.fullArgsText();
        assertTrue(full.indexOf("a = 1") < full.indexOf("b = 2"), full);
        assertTrue(full.indexOf("b = 2") < full.indexOf("c = 3"), full);
    }
}
