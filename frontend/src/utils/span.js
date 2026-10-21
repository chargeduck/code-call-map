/**
 * Span 数据逻辑：按 traceId 分组、配对 span_start/span_end 生成展示节点。
 * 与插件 agent 落盘的 JSONL 事件结构对应：
 *   { traceId, spanId, parentId, type: 'span_start'|'span_end'|'trace_summary',
 *     ts, thread, className, method, args, sql, exception, durationMs, path,
 *     sourceFile, lineNumber }
 */

export function buildTraces(spans) {
  const traces = {}
  const firstTs = {}
  ;(spans || []).forEach(function (s) {
    if (!s.traceId) return
    if (!traces[s.traceId]) {
      traces[s.traceId] = []
      firstTs[s.traceId] = s.ts || 0
    }
    traces[s.traceId].push(s)
    // 同一条 trace 内可能有多条记录，取最早时间戳代表这条链的开始
    if (s.ts && (!firstTs[s.traceId] || s.ts < firstTs[s.traceId])) {
      firstTs[s.traceId] = s.ts
    }
  })
  const firstTraceId = Object.keys(traces)[0] || ''
  // 按 trace 开始时间升序：下拉里"最新的在最下面"，
  // 与 JSONL 的追加顺序一致，用户按视觉就能找到刚发起的请求。
  // 注意：不能直接用 Object.keys 顺序，JS 对象对整数型 key 会重排，
  // 而 traceId 里含 "t-1-" 这类前缀虽非纯数字，但依赖实现细节不可靠。
  const traceIds = Object.keys(traces).sort(function (a, b) {
    return (firstTs[a] || 0) - (firstTs[b] || 0)
  })
  return { traces, traceIds, firstTraceId }
}

export function buildNodes(trace) {
  const map = {}
  ;(trace || []).forEach(function (s) {
    const id = s.spanId
    if (!map[id]) {
      map[id] = {
        spanId: id,
        parentId: s.parentId || null,
        traceId: s.traceId,
        className: s.className || '',
        method: s.method || '',
        path: s.path || '',
        sourceFile: s.sourceFile || '',
        lineNumber: s.lineNumber != null ? s.lineNumber : null,
        thread: s.thread || '',
        args: s.args,
        sql: s.sql,
        exception: s.exception,
        start: null,
        end: null,
        durationMs: null,
        // 插桩采集：单条记录即含起止信息（ts=开始时间，durationMs=耗时）
        instrumented: false
      }
    }
    const n = map[id]
    if (s.type === 'span_start') {
      // 插桩采集的记录自带 durationMs，此时 ts 表示「开始时间」；
      // 手工埋点的 span_start 不含 durationMs，ts 同样是开始时间。
      if (s.durationMs != null) {
        n.instrumented = true
        n.durationMs = s.durationMs
      }
      n.start = s.ts
      n.thread = s.thread || n.thread
      n.args = s.args || n.args
      n.sourceFile = s.sourceFile || n.sourceFile
      n.lineNumber = s.lineNumber != null ? s.lineNumber : n.lineNumber
    }
    if (s.type === 'span_end') {
      n.end = s.ts
      n.durationMs = s.durationMs != null ? s.durationMs : (n.end - n.start)
      n.sql = s.sql || n.sql
      n.exception = s.exception || n.exception
    }
    if (s.type === 'trace_summary' && n.durationMs == null) n.durationMs = s.durationMs
  })
  const nodes = Object.keys(map).map(function (k) { return map[k] })
  nodes.forEach(function (n) {
    if (n.start == null) n.start = 0
    if (n.durationMs == null && n.end != null) n.durationMs = Math.max(0, n.end - n.start)
    if (n.durationMs == null) n.durationMs = 0
    if (n.end == null) n.end = n.start + n.durationMs
    n.label = (n.className ? shortClass(n.className) + '.' : '') + n.method
  })
  markSelfTime(nodes)
  return nodes
}

/** 类名取最后一段，避免长包名把图挤爆。 */
function shortClass(className) {
  const parts = String(className).split('.')
  return parts[parts.length - 1] || className
}

/**
 * 计算每个节点的「自身耗时」（self time）：总耗时减去直接子节点耗时之和。
 *
 * <p>这是定位卡点的关键指标——父方法总耗时大往往只是因为调用了慢子方法，
 * 真正「自己慢」的是 self time 高的那个。负数归零（并发子调用会让和超过父耗时）。
 */
function markSelfTime(nodes) {
  const childrenOf = {}
  nodes.forEach(function (n) {
    if (n.parentId) {
      if (!childrenOf[n.parentId]) childrenOf[n.parentId] = []
      childrenOf[n.parentId].push(n)
    }
  })
  nodes.forEach(function (n) {
    const kids = childrenOf[n.spanId] || []
    let kidsTotal = 0
    kids.forEach(function (k) { kidsTotal += k.durationMs || 0 })
    n.childCount = kids.length
    n.selfMs = Math.max(0, (n.durationMs || 0) - kidsTotal)
  })
  // 深度（用于缩进与树形展示）
  const byId = {}
  nodes.forEach(function (n) { byId[n.spanId] = n })
  nodes.forEach(function (n) {
    let d = 0
    let cur = n
    const guard = new Set()
    while (cur && cur.parentId && byId[cur.parentId] && !guard.has(cur.spanId)) {
      guard.add(cur.spanId)
      cur = byId[cur.parentId]
      d++
      if (d > 100) break
    }
    n.depth = d
  })
}

/**
 * 找出最值得关注的耗时节点。
 *
 * @param nodes 全部节点
 * @param topN  返回条数
 * @returns 按自身耗时降序的节点数组（同耗时则按总耗时降序）
 */
export function findHotspots(nodes, topN) {
  const limit = topN || 10
  return (nodes || [])
    .slice()
    .sort(function (a, b) {
      if (b.selfMs !== a.selfMs) return b.selfMs - a.selfMs
      return (b.durationMs || 0) - (a.durationMs || 0)
    })
    .slice(0, limit)
    .filter(function (n) { return (n.selfMs || 0) > 0 })
}

/**
 * 按「类」聚合自身耗时，回答「哪个类最耗时」。
 * 聚合自身耗时而非总耗时，避免父类被无谓放大。
 */
export function aggregateByClass(nodes) {
  const agg = {}
  ;(nodes || []).forEach(function (n) {
    const key = n.className || '(unknown)'
    if (!agg[key]) agg[key] = { className: key, selfMs: 0, totalMs: 0, count: 0, maxMs: 0 }
    const a = agg[key]
    a.selfMs += n.selfMs || 0
    a.totalMs += n.durationMs || 0
    a.count += 1
    a.maxMs = Math.max(a.maxMs, n.durationMs || 0)
  })
  return Object.keys(agg).map(function (k) { return agg[k] })
    .sort(function (a, b) { return b.selfMs - a.selfMs })
}

export function niceStep(raw) {
  const pow = Math.pow(10, Math.floor(Math.log(raw) / Math.LN10))
  const n = raw / pow
  const f = n < 1 ? 1 : n < 2 ? 2 : n < 5 ? 5 : 10
  return f * pow
}

export function escHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}
