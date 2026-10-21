package com.github.chargeduck.codecallmap.webview;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 两次录制会话的<b>性能对比</b>。
 *
 * <h2>为什么做这个</h2>
 * 「时间旅行调试」路线（可回退单步）的目标是<b>复现问题</b>。
 * CCM 采集的是脱敏 JSONL 调用链，天然适合回答另一个问题：
 * <b>这次改动到底让哪段代码变快/变慢了？</b>
 *
 * <p>所以这里做「A/B 对比」：把两次会话按 <b>类.方法</b> 聚合，
 * 逐一算出耗时与调用次数的变化，并按"变慢幅度"排序。
 * 这是优化前后验证的标准动作，也比回放更贴近日常需求。
 *
 * <h2>聚合口径</h2>
 * <ul>
 *   <li>键：{@code 类全名#方法名}。不用 displayName（简单类名），
 *       避免不同包下的同名类被错误合并。</li>
 *   <li>总耗时：该方法所有 span 的 durationMs 之和</li>
 *   <li>自身耗时：所有 span 的 selfMs 之和（扣除子调用，反映"这段代码本身"的开销）</li>
 *   <li>次数：出现的 span 个数</li>
 * </ul>
 *
 * <h2>为什么以自身耗时为主排序</h2>
 * 总耗时会受下游调用影响，优化上游时总耗时可能纹丝不动。
 * 自身耗时才是"这段代码自己花了多少"，是判断优化是否生效的可靠指标。
 */
public final class CallMapDiff {

    private CallMapDiff() {
    }

    /** 单个方法的对比结果。 */
    public static final class Row {
        /** 键：类全名#方法名。 */
        public final String key;
        /** 展示名：简单类名.方法名。 */
        public final String displayName;

        /** A 会话（基线）的数据；方法在 A 中不存在时 count 为 0。 */
        public long aTotalMs;
        public long aSelfMs;
        public int aCount;

        /** B 会话（对比）的数据。 */
        public long bTotalMs;
        public long bSelfMs;
        public int bCount;

        /** B 相对 A 的自身耗时变化（毫秒，正数=变慢）。 */
        public long selfDeltaMs;
        /** B 相对 A 的自身耗时变化百分比（A 为 0 时见 {@link #deltaPercentText()}）。 */
        public double selfDeltaPercent;

        /** 该方法是否只在 B 中出现（新增调用）。 */
        public boolean onlyInB;
        /** 该方法是否只在 A 中出现（本次不再被调用）。 */
        public boolean onlyInA;

        Row(String key, String displayName) {
            this.key = key;
            this.displayName = displayName;
        }

        /** 变化方向：1 变慢，-1 变快，0 基本不变。 */
        public int trend() {
            // 1ms 以内视为噪声，避免满屏细微波动干扰判断
            if (selfDeltaMs > 1) {
                return 1;
            }
            if (selfDeltaMs < -1) {
                return -1;
            }
            return 0;
        }

        /** 百分比文本；基数为 0 时给出明确说明而不是 Infinity。 */
        public String deltaPercentText() {
            if (onlyInB) {
                return "新增";
            }
            if (onlyInA) {
                return "已移除";
            }
            if (aSelfMs == 0) {
                return selfDeltaMs == 0 ? "持平" : "—";
            }
            return String.format("%+.1f%%", selfDeltaPercent);
        }

        /** 变化量文本，形如 {@code +12ms} / {@code -8ms}。 */
        public String deltaText() {
            if (onlyInB || onlyInA) {
                return "";
            }
            return String.format("%+dms", selfDeltaMs);
        }
    }

    /** 一次对比的完整结果。 */
    public static final class Result {
        public final CallMapData.Session sessionA;
        public final CallMapData.Session sessionB;
        /** 按"变慢幅度"排序的行；只想看回退时取前面几条即可。 */
        public final List<Row> rows = new ArrayList<>();
        /** A 会话全部方法的自身耗时之和（用于看整体趋势）。 */
        public long aTotalSelfMs;
        /** B 会话全部方法的自身耗时之和。 */
        public long bTotalSelfMs;

        Result(CallMapData.Session a, CallMapData.Session b) {
            this.sessionA = a;
            this.sessionB = b;
        }

        public boolean isEmpty() {
            return rows.isEmpty();
        }

        /** 整体自身耗时变化百分比（A 为 0 时返回 0）。 */
        public double overallPercent() {
            if (aTotalSelfMs == 0) {
                return 0;
            }
            return (bTotalSelfMs - aTotalSelfMs) * 100.0 / aTotalSelfMs;
        }
    }

    /**
     * 对比两个会话。
     *
     * @param baseline 基线会话（改动前，A）
     * @param current  对比会话（改动后，B）
     */
    @NotNull
    public static Result compare(@Nullable CallMapData.Session baseline,
                                 @Nullable CallMapData.Session current) {
        Result result = new Result(baseline, current);
        if (baseline == null || current == null) {
            return result;
        }

        Map<String, Row> rows = new LinkedHashMap<>();
        collect(CallMapData.load(baseline), rows, true);
        collect(CallMapData.load(current), rows, false);

        for (Row row : rows.values()) {
            row.selfDeltaMs = row.bSelfMs - row.aSelfMs;
            row.selfDeltaPercent = row.aSelfMs == 0
                    ? 0.0
                    : (row.bSelfMs - row.aSelfMs) * 100.0 / row.aSelfMs;
            row.onlyInB = row.aCount == 0;
            row.onlyInA = row.bCount == 0;
            result.aTotalSelfMs += row.aSelfMs;
            result.bTotalSelfMs += row.bSelfMs;
        }

        // 按变慢幅度降序：最该关注的性能回退排最前
        List<Row> sorted = new ArrayList<>(rows.values());
        sorted.sort((x, y) -> Long.compare(y.selfDeltaMs, x.selfDeltaMs));
        result.rows.addAll(sorted);
        return result;
    }

    /** 把一次会话的 span 按 类#方法 聚合进 rows。 */
    private static void collect(@NotNull CallMapData.Snapshot snapshot,
                                @NotNull Map<String, Row> rows,
                                boolean asBaseline) {
        for (CallMapData.SpanNode node : flatten(snapshot)) {
            String key = node.qualifiedName();
            Row row = rows.computeIfAbsent(key,
                    k -> new Row(k, node.displayName()));
            if (asBaseline) {
                row.aSelfMs += node.selfMs();
                row.aTotalMs += node.durationMs == null ? 0 : node.durationMs;
                row.aCount++;
            } else {
                row.bSelfMs += node.selfMs();
                row.bTotalMs += node.durationMs == null ? 0 : node.durationMs;
                row.bCount++;
            }
        }
    }

    /** 深度优先展开所有节点。 */
    private static List<CallMapData.SpanNode> flatten(@NotNull CallMapData.Snapshot snapshot) {
        List<CallMapData.SpanNode> out = new ArrayList<>();
        for (CallMapData.SpanNode root : snapshot.roots) {
            walk(root, out);
        }
        return out;
    }

    private static void walk(CallMapData.SpanNode node, List<CallMapData.SpanNode> out) {
        out.add(node);
        for (CallMapData.SpanNode child : node.children) {
            walk(child, out);
        }
    }
}
