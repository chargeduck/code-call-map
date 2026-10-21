/**
 * 跨 trace 的调用统计。
 *
 * <h2>为什么需要跨 trace 聚合</h2>
 * 每次 HTTP 请求在 agent 侧会开启一条独立 trace（{@code TraceContext} 栈空时新建）。
 * 现有 HotspotPanel 的聚合只在<b>单条 trace 内</b>进行，
 * 所以反复调用同一个接口时，看到的是"一条一条独立的链路"，
 * 无法回答这几个关键问题：
 * <ul>
 *   <li>这个接口被调了多少次？平均耗时多少？最慢一次多慢？</li>
 *   <li>这段代码被多少个不同请求走到过？总共执行了多少次？</li>
 *   <li>有没有一批调用稳定地慢（而不是偶发抖动）？</li>
 * </ul>
 *
 * <p>本模块把多条 trace 摊平后重新聚合，直接回答上面这些问题。
 */

/** 默认慢调用阈值（毫秒）。与采集阈值 `minDurationMs` 是两回事：
 *  后者决定"录不录"，本阈值只决定"算不算慢"，不影响数据量。 */
export const DEFAULT_SLOW_THRESHOLD_MS = 1000

/**
 * 从一组 trace 中提取全部节点（深度优先摊平）。
 *
 * @param {Object} traces { traceId: spans[] }
 * @param {Function} buildNodes span.js 导出的构建函数
 * @returns {Array} 每个元素额外带 traceId，便于回溯来源
 */
export function flattenTraces(traces, buildNodes) {
  const out = []
  const traceIds = Object.keys(traces || {})
  traceIds.forEach(function (tid) {
    const nodes = buildNodes(traces[tid]) || []
    const walk = function (n, depth) {
      out.push(Object.assign({}, n, { traceId: tid, depth: depth }))
      ;(n.children || []).forEach(function (c) { walk(c, depth + 1) })
    }
    nodes.forEach(function (n) { walk(n, 0) })
  })
  return out
}

/**
 * 按方法聚合（className#method）。
 *
 * <p>键用类全名而非简单类名，避免不同包下同名类被合并。
 *
 * <p>注意 {@code traceCount} 与 {@code count} 的区别：
 * 同一个方法在一次请求里可能被调用多次（如循环内调用），
 * 所以「执行次数」和「涉及多少条 trace」必须分开统计 ——
 * 这正是"反复调用很多接口但都走到同样代码"这个场景要看的数据。
 */
export function aggregateByMethod(flatNodes, slowThresholdMs) {
  const threshold = normalizeThreshold(slowThresholdMs)
  const map = new Map()

  flatNodes.forEach(function (n) {
    const key = (n.className || '') + '#' + (n.method || '')
    let a = map.get(key)
    if (!a) {
      a = {
        key: key,
        label: n.label || key,
        className: n.className || '',
        method: n.method || '',
        count: 0,          // 总执行次数
        traceIds: new Set(), // 涉及的不同 trace
        totalMs: 0,
        selfMs: 0,
        maxMs: 0,
        slowCount: 0,      // 超过阈值的次数
        exceptionCount: 0,
      }
      map.set(key, a)
    }
    const d = n.durationMs || 0
    a.count += 1
    a.traceIds.add(n.traceId)
    a.totalMs += d
    a.selfMs += n.selfMs || 0
    a.maxMs = Math.max(a.maxMs, d)
    if (d >= threshold) a.slowCount += 1
    if (n.exception) a.exceptionCount += 1
  })

  return Array.from(map.values()).map(function (a) {
    return {
      key: a.key,
      label: a.label,
      className: a.className,
      method: a.method,
      count: a.count,
      traceCount: a.traceIds.size,
      totalMs: round(a.totalMs),
      avgMs: round(a.totalMs / a.count),
      selfMs: round(a.selfMs),
      maxMs: round(a.maxMs),
      slowCount: a.slowCount,
      slowRate: a.count ? a.slowCount / a.count : 0,
      exceptionCount: a.exceptionCount,
    }
  }).sort(function (x, y) { return y.totalMs - x.totalMs })
}

/**
 * 按入口接口聚合（每条 trace 的根节点视为一次"接口调用"）。
 *
 * <p>这是"反复调用同一个接口"场景的主视图：
 * 一条 trace = 一次请求，根节点的耗时就是这次请求的总耗时。
 */
export function aggregateByEntry(flatNodes, slowThresholdMs) {
  const threshold = normalizeThreshold(slowThresholdMs)
  const map = new Map()

  flatNodes.forEach(function (n) {
    // 只取根节点：depth 为 0 即该 trace 的入口
    if (n.depth !== 0) return
    const key = (n.className || '') + '#' + (n.method || '')
    let a = map.get(key)
    if (!a) {
      a = {
        key: key,
        label: n.label || key,
        path: n.path || '',
        traceIds: [],
        durations: [],
        exceptions: 0,
      }
      map.set(key, a)
    }
    a.traceIds.push(n.traceId)
    a.durations.push(n.durationMs || 0)
    if (n.exception) a.exceptions += 1
    if (!a.path && n.path) a.path = n.path
  })

  return Array.from(map.values()).map(function (a) {
    const sorted = a.durations.slice().sort(function (x, y) { return x - y })
    const sum = sorted.reduce(function (s, v) { return s + v }, 0)
    const slowCount = sorted.filter(function (v) { return v >= threshold }).length
    return {
      key: a.key,
      label: a.label,
      path: a.path,
      count: sorted.length,
      totalMs: round(sum),
      avgMs: round(sum / sorted.length),
      maxMs: sorted[sorted.length - 1] || 0,
      // P95 比平均值更能反映"多数用户的实际体验"
      p95Ms: sorted[Math.max(0, Math.ceil(sorted.length * 0.95) - 1)] || 0,
      slowCount: slowCount,
      slowRate: sorted.length ? slowCount / sorted.length : 0,
      exceptions: a.exceptions,
    }
  }).sort(function (x, y) { return y.avgMs - x.avgMs })
}

/** 阈值归一化：非法值（负数 / NaN / 空）退回默认值，避免整表被判成慢调用。 */
function normalizeThreshold(v) {
  const n = Number(v)
  if (!isFinite(n) || n < 0) return DEFAULT_SLOW_THRESHOLD_MS
  return n
}

function round(v) {
  return Math.round(v * 10) / 10
}
