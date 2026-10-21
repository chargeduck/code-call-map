package com.github.chargeduck.codecallmap.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * main 入口签名判定测试。
 *
 * <p>这条规则决定装订区「Run/Debug with CodeCallMap」图标出现在哪。
 * 判错的两个方向都有代价：
 * <ul>
 *   <li>该出现时没出现 → 用户以为插件坏了；</li>
 *   <li>不该出现时出现了 → 用户点下去启动失败，体验更差。</li>
 * </ul>
 */
class MainMethodSignatureTest {

    private static final String STRING_ARRAY_VOID = "([Ljava/lang/String;)V";

    @Test
    void standardMainIsEntryPoint() {
        assertTrue(MainMethodSignature.isEntryPoint(
                "main", STRING_ARRAY_VOID, true, true, true));
    }

    @Test
    void noArgMainIsEntryPoint() {
        // Java 21+ 的无参 main（预览特性）
        assertTrue(MainMethodSignature.isEntryPoint("main", "()V", true, true, true));
    }

    @Test
    void wrongMethodNameRejected() {
        assertFalse(MainMethodSignature.isEntryPoint(
                "mainX", STRING_ARRAY_VOID, true, true, true));
        assertFalse(MainMethodSignature.isEntryPoint("run", "()V", true, true, true));
    }

    @Test
    void nonPublicOrNonStaticRejected() {
        assertFalse(MainMethodSignature.isEntryPoint(
                "main", STRING_ARRAY_VOID, false, true, true), "非 public 不可作为入口");
        assertFalse(MainMethodSignature.isEntryPoint(
                "main", STRING_ARRAY_VOID, true, false, true), "非 static 不可作为入口");
    }

    @Test
    void nonVoidReturnRejected() {
        assertFalse(MainMethodSignature.isEntryPoint(
                "main", "([Ljava/lang/String;)I", true, true, true), "返回 int 不是合法入口");
        assertFalse(MainMethodSignature.isEntryPoint(
                "main", "([Ljava/lang/String;)Ljava/lang/String;", true, true, true));
    }

    @Test
    void wrongParametersRejected() {
        assertFalse(MainMethodSignature.isEntryPoint(
                "main", "(I)V", true, true, true), "单个 int 参数不是入口");
        assertFalse(MainMethodSignature.isEntryPoint(
                "main", "(Ljava/lang/String;)V", true, true, true), "String 而非 String[] 不是入口");
        assertFalse(MainMethodSignature.isEntryPoint(
                "main", "([Ljava/lang/String;I)V", true, true, true), "两个参数不是入口");
        assertFalse(MainMethodSignature.isEntryPoint(
                "main", "([I)V", true, true, true), "int[] 不是入口");
    }

    @Test
    void interfaceOrAbstractOwnerRejected() {
        assertFalse(MainMethodSignature.isEntryPoint(
                "main", STRING_ARRAY_VOID, true, true, false),
                "接口 / 抽象类不能作为运行入口");
    }

    @Test
    void nullSafety() {
        assertFalse(MainMethodSignature.isEntryPoint(null, STRING_ARRAY_VOID, true, true, true));
        assertFalse(MainMethodSignature.isEntryPoint("main", null, true, true, true));
    }

    @Test
    void malformedDescriptorRejected() {
        assertFalse(MainMethodSignature.isEntryPoint("main", "", true, true, true));
        assertFalse(MainMethodSignature.isEntryPoint("main", "abc", true, true, true));
        assertFalse(MainMethodSignature.isEntryPoint("main", "()", true, true, true));
    }
}
