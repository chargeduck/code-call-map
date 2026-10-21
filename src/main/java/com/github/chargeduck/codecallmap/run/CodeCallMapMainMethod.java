package com.github.chargeduck.codecallmap.run;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * main 方法判定的 PSI 适配层。
 *
 * <p>本类原先是「一个 Contributor 同时提供 Run 与 Debug 两个 action」的实现，
 * 但 {@code Info} 只有一个 icon 字段，两个 action 会被 IDEA 合并成
 * 「一个图标 + 展开菜单」，无法同时显示绿三角与虫子。
 * 现在装订区功能已拆到 {@link RunWithCodeCallMapContributor} 与
 * {@link DebugWithCodeCallMapContributor}（各自拥有正确的图标），
 * 本类退化为纯粹的工具方法，供两个 Contributor 共用同一套判定逻辑。
 *
 * <p>判定规则由 {@link MainMethodSignature} 承载（可被单元测试覆盖），
 * 这里只负责把 PSI 信息适配成描述符。
 */
final class CodeCallMapMainMethod {

    private CodeCallMapMainMethod() {
    }

    /**
     * 判断是否为可运行的 main 方法。
     *
     * <p>签名必须严格匹配 JVM 的 entry point 约定：
     * {@code public static void main(String[])}，或 Java 21 起的无参
     * {@code public static void main()}（预览特性）。
     *
     * <p><b>不能只看方法名 + public/static</b>：{@code public static int main(...)} 不是合法入口，
     * 若给了按钮，用户点了必然启动失败，比「没有按钮」更糟。
     */
    static boolean isEntryPoint(@NotNull PsiMethod method) {
        if (!"main".equals(method.getName())) {
            return false;
        }
        // 所属类型必须可作为入口：非接口、非抽象类
        PsiClass owner = method.getContainingClass();
        boolean ownerRunnable = owner != null
                && !owner.isInterface()
                && !owner.hasModifierProperty(PsiModifier.ABSTRACT);

        return MainMethodSignature.isEntryPoint(
                method.getName(),
                buildDescriptor(method),
                method.hasModifierProperty(PsiModifier.PUBLIC),
                method.hasModifierProperty(PsiModifier.STATIC),
                ownerRunnable);
    }

    /** 由 PSI 参数与返回类型拼出 JVM 描述符，例如 {@code ([Ljava/lang/String;)V}。 */
    private static String buildDescriptor(@NotNull PsiMethod method) {
        StringBuilder sb = new StringBuilder("(");
        for (PsiParameter p : method.getParameterList().getParameters()) {
            sb.append(descriptorOf(p.getType()));
        }
        sb.append(')');
        PsiType ret = method.getReturnType();
        sb.append(ret == null ? "V" : descriptorOf(ret));
        return sb.toString();
    }

    /** 把 PsiType 转成 JVM 描述符（覆盖 main 签名可能出现的类型）。 */
    private static String descriptorOf(@Nullable PsiType type) {
        if (type instanceof com.intellij.psi.PsiArrayType arrayType) {
            return "[" + descriptorOf(arrayType.getComponentType());
        }
        String canonical = type == null ? "" : type.getCanonicalText();
        return switch (canonical) {
            case "void" -> "V";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            case "java.lang.String" -> "Ljava/lang/String;";
            default -> "L" + canonical.replace('.', '/') + ";";
        };
    }
}
