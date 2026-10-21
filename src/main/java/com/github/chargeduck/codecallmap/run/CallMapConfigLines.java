package com.github.chargeduck.codecallmap.run;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code callmap-config.yml} 的行解析（纯字符串处理，无平台依赖）。
 *
 * <h2>为什么单独抽出来</h2>
 * {@link ProjectConfigStore} 持有 IDEA 的 {@code Logger} 静态字段，
 * 在纯单元测试环境里类加载就会 {@code NoClassDefFoundError}。
 * 把可测的解析规则拆到这里，就能用普通 JUnit 覆盖边界情况 ——
 * 这个文件用户会手工编辑，容错性值得单独测。
 */
public final class CallMapConfigLines {

    private CallMapConfigLines() {
    }

    /**
     * 解析配置行。
     *
     * @param lines 文件各行
     * @return 键值对；跳过注释、空行、无冒号的行
     */
    public static Map<String, String> parse(List<String> lines) {
        Map<String, String> result = new LinkedHashMap<>();
        if (lines == null) {
            return result;
        }
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            // 只按【第一个】冒号切分：Windows 路径 C:\project\... 里还有冒号
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String key = line.substring(0, idx).trim();
            String value = line.substring(idx + 1).trim();
            // 去掉成对的引号
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            result.put(key, value);
        }
        return result;
    }
}
