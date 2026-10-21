package com.github.chargeduck.codecallmap.webview;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从录制会话中取出「某方法的所有调用记录」。
 *
 * <h2>为什么在这里读文件，而不是让前端把数据传过来</h2>
 * 前端确实已经持有全部 span（{@code loadSpans} 时内联过 args），
 * 但一次 trace 的入参可能有几 MB。通过 JCEF 的 query 通道搬运这么大的字符串
 * 既慢又容易触达消息长度限制，而<b>后端本来就能直接读同一个目录</b>，
 * 没有任何理由绕一圈。
 *
 * <p>前端的职责因此收敛成：告诉后端「我要看 {@code className#method}」，
 * 后端负责把该方法的现场读出来并塞进编辑器。
 *
 * <h2>为什么不用 JSON 库</h2>
 * agent 侧刻意零依赖（要能被任意目标 JVM 加载），插件侧虽然能用 Gson，
 * 但这里只需要「从一行 JSONL 里取几个标量字段」，
 * 写一个容错的窄解析器比引入依赖更轻。注意：只解析<b>标量</b>，
 * 嵌套结构一律原样切片（{@link #parseTopLevelObject}），不做完整反序列化。
 *
 * @author chargeduck
 */
public final class CcmCallRecordReader {

    /** 单次最多回放多少次调用，防止某方法被循环调用上万次时把内存打爆。 */
    private static final int MAX_RECORDS = 200;

    private CcmCallRecordReader() {
    }

    /**
     * 读取指定方法的全部调用记录（按调用时间升序）。
     *
     * @param sessionDir 会话目录（JSONL 所在目录，args/ret 子目录也在其下）
     * @param jsonlFile  会话 JSONL 文件
     * @param className  全限定类名（如 {@code com.foo.Bar}）
     * @param methodName 方法名
     * @return 调用记录列表；找不到返回空列表（不返回 null，调用方无需判空）
     */
    public static List<CcmCallRecord> read(Path sessionDir, Path jsonlFile,
                                           String className, String methodName) {
        if (sessionDir == null || jsonlFile == null || className == null || methodName == null
                || !Files.isRegularFile(jsonlFile)) {
            return Collections.emptyList();
        }
        List<CcmCallRecord> result = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(jsonlFile, StandardCharsets.UTF_8)) {
                if (line.isBlank() || !line.contains("\"span_start\"")) {
                    continue;
                }
                // 先做便宜的字段比对再进解析：绝大多数行会在这一步被排除
                if (!className.equals(str(line, "className")) || !methodName.equals(str(line, "method"))) {
                    continue;
                }
                CcmCallRecord record = toRecord(line, sessionDir);
                if (record != null) {
                    result.add(record);
                }
                if (result.size() >= MAX_RECORDS) {
                    break;
                }
            }
        } catch (IOException e) {
            return Collections.emptyList();
        }
        // 按时间升序：JSONL 的写入顺序是「方法返回」顺序，
        // 嵌套调用下与外层看去的调用顺序不一致，必须显式排序
        result.sort((a, b) -> Long.compare(a.ts(), b.ts()));
        return result;
    }

    /**
     * 把一行 JSONL 转成展示模型。
     *
     * @param line       JSONL 的一行
     * @param sessionDir 会话目录（用于定位 args/ret 文件）
     * @return 展示模型；解析失败返回 null（单条坏数据不应中断整次回放）
     */
    private static CcmCallRecord toRecord(String line, Path sessionDir) {
        try {
            CcmCallRecord.Builder b = CcmCallRecord.builder()
                    .spanId(str(line, "spanId"))
                    .ts(num(line, "ts"))
                    .thread(str(line, "thread"))
                    .durationMs(num(line, "durationMs"))
                    .exception(str(line, "exception"));

            // 入参：读外置文件并拆出每个参数名对应的值文本
            String argsFile = str(line, "argsFile");
            if (argsFile != null && !argsFile.isEmpty()) {
                Map<String, String> args = parseTopLevelObject(readMaybe(sessionDir, argsFile));
                for (Map.Entry<String, String> e : args.entrySet()) {
                    b.putArg(e.getKey(), e.getValue());
                }
            }

            // 返回值：文件存在即表示"有返回值"（void 方法不会产出 ret 文件）
            String retFile = str(line, "retFile");
            if (retFile != null && !retFile.isEmpty()) {
                String raw = readMaybe(sessionDir, retFile);
                // 采集侧把返回值包成 {"return": <值>}，这里拆掉这层包装，
                // 让编辑器直接显示值本身，而不是每次都看到 return = ...
                Map<String, String> wrapper = parseTopLevelObject(raw);
                String value = wrapper.get("return");
                if (value == null && raw != null && !raw.isEmpty()) {
                    // 兼容：直接是裸值（早期格式或未来改成裸序列化）
                    value = raw;
                }
                b.ret(value, true);
            }
            return b.build();
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 安全读取会话目录下的相对路径文件。
     *
     * <p>做了目录穿越校验：只允许读会话目录之内的文件，
     * 避免被构造出的 {@code ../../etc/passwd} 形式的路径读到别处。
     *
     * @param sessionDir 会话目录
     * @param rel        相对路径（如 {@code args/xxx.json}）
     * @return 文件内容；不存在或越界返回 null
     */
    private static String readMaybe(Path sessionDir, String rel) {
        try {
            Path file = sessionDir.resolve(rel).normalize();
            if (!file.startsWith(sessionDir.normalize()) || !Files.isRegularFile(file)) {
                return null;
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 窄解析：JSONL 标量提取
    // ------------------------------------------------------------------

    /**
     * 从一行 JSON 里取字符串字段。
     *
     * @param json JSON 文本
     * @param key  字段名
     * @return 字段值（已反转义）；不存在返回 null
     */
    static String str(String json, String key) {
        String prefix = "\"" + key + "\":\"";
        int i = json.indexOf(prefix);
        if (i < 0) {
            return null;
        }
        int start = i + prefix.length();
        StringBuilder sb = new StringBuilder(32);
        for (int p = start; p < json.length(); p++) {
            char c = json.charAt(p);
            if (c == '\\') {
                // 采集侧只转义少量字符，这里逐字符还原
                if (p + 1 >= json.length()) {
                    break;
                }
                char n = json.charAt(++p);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (p + 4 < json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(json.substring(p + 1, p + 5), 16));
                                p += 4;
                            } catch (NumberFormatException ignored) {
                                // 非法 unicode 转义：原样保留反斜杠，不吞字符
                                // （注意这里不能写反斜杠+u 的字面注释：Java 会在"任何位置"
                                //   把它当 unicode 转义解析，连注释里都不放过，直接编译报错）
                                sb.append('\\').append('u');
                            }
                        }
                    }
                    default -> sb.append(n);
                }
                continue;
            }
            if (c == '"') {
                return sb.toString();
            }
            sb.append(c);
        }
        return null;
    }

    /**
     * 从一行 JSON 里取数字字段。
     *
     * @param json JSON 文本
     * @param key  字段名
     * @return 字段值；不存在或非数字返回 0
     */
    static long num(String json, String key) {
        String prefix = "\"" + key + "\":";
        int i = json.indexOf(prefix);
        if (i < 0) {
            return 0L;
        }
        int start = i + prefix.length();
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '-' || c == '+' || (c >= '0' && c <= '9')) {
                end++;
            } else {
                break;
            }
        }
        if (end == start) {
            return 0L;
        }
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 解析 JSON 对象的<b>顶层</b>键值，值保留原始 JSON 片段文本。
     *
     * <p>为什么不完整反序列化：参数值可能是任意嵌套的对象/数组，
     * 这里只需要"哪个参数、值长什么样"，把它原样切出来交给渲染层即可。
     * 完整解析会引入对 JSON 库的依赖，而收益为零。
     *
     * <p>容错：格式非法时返回已解析出的部分，不抛异常
     * （一条坏数据不该让整个编辑器提示消失）。
     *
     * @param json JSON 对象文本（可带首尾空白）
     * @return 有序 Map（保持原文顺序）
     */
    static Map<String, String> parseTopLevelObject(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null) {
            return out;
        }
        String s = json.trim();
        if (!s.startsWith("{")) {
            return out;
        }
        int i = 1;
        int n = s.length();
        while (i < n) {
            // 找下一个 key 的起始引号
            while (i < n && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) {
                i++;
            }
            if (i >= n || s.charAt(i) == '}') {
                break;
            }
            if (s.charAt(i) != '"') {
                break;
            }
            int keyEnd = findStringEnd(s, i);
            if (keyEnd < 0) {
                break;
            }
            String key = unescape(s.substring(i + 1, keyEnd));
            i = keyEnd + 1;
            while (i < n && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            if (i >= n || s.charAt(i) != ':') {
                break;
            }
            i++;
            while (i < n && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            int valueEnd = findValueEnd(s, i);
            if (valueEnd < 0) {
                break;
            }
            out.put(key, renderValue(s.substring(i, valueEnd).trim()));
            i = valueEnd;
        }
        return out;
    }

    /**
     * 找到字符串字面量的结束引号位置（跳过转义）。
     *
     * @param s     文本
     * @param start 起始引号位置
     * @return 结束引号位置；未闭合返回 -1
     */
    private static int findStringEnd(String s, int start) {
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 找到一个值的结束位置（标量到分隔符，容器按括号配对）。
     *
     * @param s     文本
     * @param start 值起始位置
     * @return 结束位置（不含）；无法识别返回 -1
     */
    private static int findValueEnd(String s, int start) {
        if (start >= s.length()) {
            return -1;
        }
        char c = s.charAt(start);
        if (c == '"') {
            int end = findStringEnd(s, start);
            return end < 0 ? -1 : end + 1;
        }
        if (c == '{' || c == '[') {
            int depth = 0;
            for (int i = start; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '"') {
                    int end = findStringEnd(s, i);
                    if (end < 0) {
                        return -1;
                    }
                    i = end;
                    continue;
                }
                if (ch == '{' || ch == '[') {
                    depth++;
                } else if (ch == '}' || ch == ']') {
                    depth--;
                    if (depth == 0) {
                        return i + 1;
                    }
                }
            }
            return -1;
        }
        // 标量：直到逗号或对象结束
        for (int i = start; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == ',' || ch == '}') {
                return i;
            }
        }
        return s.length();
    }

    /**
     * 把原始 JSON 片段渲染成适合展示的短文本。
     *
     * <p>字符串去掉外层引号（展示时引号是噪音），容器类保留结构，
     * 由调用方按长度再截断。
     *
     * @param raw 原始片段
     * @return 渲染文本
     */
    private static String renderValue(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
            return unescape(raw.substring(1, raw.length() - 1));
        }
        return raw;
    }

    /**
     * 还原 JSON 转义。
     *
     * @param s 已去掉外层引号的字符串内容
     * @return 还原结果
     */
    static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            sb.append('\\').append('u');
                        }
                    } else {
                        sb.append('\\').append('u');
                    }
                }
                default -> sb.append(n);
            }
        }
        return sb.toString();
    }
}
