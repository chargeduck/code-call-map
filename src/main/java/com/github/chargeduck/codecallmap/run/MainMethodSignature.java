package com.github.chargeduck.codecallmap.run;

import org.jetbrains.annotations.Nullable;

/**
 * {@code main} 入口方法签名的纯判定逻辑（不依赖 PSI，可直接单测）。
 *
 * <p>从 {@link CodeCallMapMainMethod} 中抽出，原因：
 * 「哪些方法该出现 Run with CodeCallMap 图标」这条规则一旦写错，
 * 要么图标该出现时不出现（用户以为插件坏了），
 * 要么给了按钮但点下去启动失败（体验更差）。
 * 抽成纯函数后可以用字符串描述符直接覆盖各种边界写法。
 */
final class MainMethodSignature {

    private MainMethodSignature() {
    }

    /** {@code String[]} 的 JVM 描述符形式。 */
    private static final String STRING_ARRAY = "[Ljava/lang/String;";

    /**
     * 判断描述符是否对应合法 JVM 入口。
     *
     * @param name       方法名
     * @param descriptor JVM 方法描述符，如 {@code ([Ljava/lang/String;)V}
     * @param isPublic   是否 public
     * @param isStatic   是否 static
     * @param ownerIsRunnableType 所属类型是否可作为入口（非接口、非抽象类）
     */
    static boolean isEntryPoint(@Nullable String name, @Nullable String descriptor,
                                boolean isPublic, boolean isStatic,
                                boolean ownerIsRunnableType) {
        if (name == null || descriptor == null) {
            return false;
        }
        if (!"main".equals(name)) {
            return false;
        }
        if (!isPublic || !isStatic) {
            return false;
        }
        if (!ownerIsRunnableType) {
            return false;
        }
        // 返回类型必须是 V（void）
        if (!descriptor.endsWith(")V")) {
            return false;
        }
        // 取出括号内的参数部分
        int open = descriptor.indexOf('(');
        int close = descriptor.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return false;
        }
        String args = descriptor.substring(open + 1, close);

        // 合法入口只有两种形参：无参，或单个 String[]
        if (args.isEmpty()) {
            return true;
        }
        return STRING_ARRAY.equals(args);
    }
}
