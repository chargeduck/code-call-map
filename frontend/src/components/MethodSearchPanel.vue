<template>
  <div class="search-root" :class="{ compact }">
    <!-- 搜索输入：两种形态共用同一套搜索逻辑 -->
    <div class="search-bar">
      <el-input
        v-model="keyword"
        size="small"
        clearable
        :placeholder="compact ? '搜索方法 → 自动跳到最慢那次调用' : '输入方法名 / 类名（支持模糊，如 page、OrderService、Controller#page）'"
        class="search-input"
      >
        <template #prefix>
          <span class="prefix-icon">⌕</span>
        </template>
      </el-input>
    </div>

    <!-- ===== 顶部工具条形态：只显示统计摘要 ===== -->
    <template v-if="compact">
      <!-- 去重统计：用户关心"这个方法有几个不同实体被调过、出现几次"，
           而不是"命中多少条原始记录"——同一方法在一个链里可能被调 20 次。 -->
      <span v-if="kw" class="summary">
        <template v-if="groups.length">
          命中 <b>{{ groups.length }}</b> 个方法 / 共 <b>{{ totalHits }}</b> 次调用
          <span class="dim">· {{ hitTraceCount }} 条链</span>
        </template>
        <span v-else class="dim">无匹配</span>
      </span>
      <el-button
        v-if="kw && groups.length"
        size="small"
        text
        type="primary"
        class="detail-btn"
        @click="emit('open-detail', keyword)"
      >查看明细</el-button>
      <!-- 命中后直接把"最慢的那次"作为当前 trace，用户不必再点开面板 -->
      <span v-if="kw && groups.length" class="jump-hint">
        → 已定位 <b>{{ slowestHit.label }}</b>
      </span>
    </template>

    <!-- ===== 完整面板形态 ===== -->
    <template v-else>
      <div class="opts">
        <el-checkbox v-model="matchCase" size="small">区分大小写</el-checkbox>
        <el-checkbox v-model="groupByMethod" size="small">按方法去重</el-checkbox>
        <span v-if="kw" class="summary">
          命中 <b>{{ groups.length }}</b> 个方法 / 共 <b>{{ totalHits }}</b> 次调用
          <span class="dim">· {{ hitTraceCount }} 条链</span>
        </span>
      </div>
    </template>

    <!-- ===== 完整面板：结果区 ===== -->
    <div v-if="!compact" class="result-area">
      <el-empty
        v-if="!kw"
        description="输入方法名开始搜索：会按方法去重汇总，并展开每条调用链的完整路径"
      />
      <el-empty v-else-if="!groups.length" description="没有匹配的方法" />

      <div v-else class="group-list">
        <div
          v-for="(g, gi) in groups"
          :key="g.key"
          class="group-item"
          :class="{ open: gi === activeGroup }"
        >
          <!-- 汇总行：一个方法一行 -->
          <div class="group-head" @click="toggleGroup(gi)">
            <span class="caret">{{ gi === activeGroup ? '▾' : '▸' }}</span>
            <span class="group-label">{{ g.label }}</span>
            <span class="count-badge" :title="'在该会话中出现的次数'">×{{ g.hits.length }}</span>
            <span class="group-stat" title="最大耗时">max {{ fmt(g.maxMs) }}</span>
            <span class="group-stat" title="总耗时">Σ {{ fmt(g.totalMs) }}</span>
            <span class="dim small">跨 {{ g.traceCount }} 条链</span>
          </div>

          <!-- 展开：该方法的每一次调用（按耗时降序） -->
          <div v-if="gi === activeGroup" class="hit-list">
            <div
              v-for="h in g.hits"
              :key="h.key"
              class="hit-item"
              @click="selectHit(h)"
            >
              <span class="hit-dur" :class="durClass(h.durationMs)">{{ fmt(h.durationMs) }}</span>
              <div class="hit-body">
                <div class="hit-path">
                  <template v-for="(seg, i) in h.pathSegments" :key="i">
                    <span v-if="i > 0" class="arrow">›</span>
                    <span class="seg" :class="{ self: i === h.pathSegments.length - 1 }">{{ seg }}</span>
                  </template>
                </div>
                <div class="hit-meta">{{ h.thread }} · {{ h.traceId }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ===== 完整面板：选中调用所属链的完整树 ===== -->
    <div v-if="!compact && activeNodes.length" class="tree-area">
      <div class="tree-head">
        <span>调用链：{{ activeTraceId }}</span>
        <el-button size="small" text type="primary" @click="openInMain">在时序图中查看</el-button>
      </div>
      <div class="tree-body">
        <div
          v-for="n in activeNodes"
          :key="n.spanId"
          class="tree-row"
          :class="{ hit: n.isHit }"
        >
          <span class="indent" :style="{ width: (n.depth || 0) * 16 + 'px' }"></span>
          <span class="tree-dot"></span>
          <span class="tree-label">{{ n.label }}</span>
          <span class="tree-dur" :class="durClass(n.durationMs)">{{ fmt(n.durationMs) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { buildNodes } from '../utils/span'
import { formatDuration } from '../utils/duration'

const props = defineProps({
  /** 当前会话的全部 span 事件 */
  spans: { type: Array, default: () => [] },
  sessionId: { type: String, default: '' },
  /** true = 顶部工具条形态（一行摘要）；false = 完整面板 */
  compact: { type: Boolean, default: false },
  /** 外部传入的初始关键字，用于"顶部搜完 → 打开明细"时带过去 */
  initialKeyword: { type: String, default: '' }
})
const emit = defineEmits(['open-trace', 'open-detail', 'hit-change'])

const keyword = ref(props.initialKeyword || '')
const matchCase = ref(false)
/** 是否按方法去重汇总。默认开：用户搜方法名时想看的是"有哪几种方法匹配"。 */
const groupByMethod = ref(true)
const activeGroup = ref(0)

const kw = computed(() => keyword.value.trim())

watch(() => props.initialKeyword, (v) => {
  if (v) keyword.value = v
})

/**
 * 全量命中明细：每个 span 一条，带从根到该 span 的路径。
 *
 * <p>「搜方法名」的本质需求是"这个方法在哪条链里被调用了"，
 * 所以既要匹配方法名，也要能匹配类名（排查时常只记得类）。
 */
const allHits = computed(() => {
  const needle0 = kw.value
  if (!needle0) return []
  const needle = matchCase.value ? needle0 : needle0.toLowerCase()
  const test = (s) => {
    const hay = matchCase.value ? s : s.toLowerCase()
    return hay.includes(needle)
  }

  const byTrace = new Map()
  ;(props.spans || []).forEach((s) => {
    if (!s.traceId) return
    if (!byTrace.has(s.traceId)) byTrace.set(s.traceId, [])
    byTrace.get(s.traceId).push(s)
  })

  const out = []
  byTrace.forEach((list, traceId) => {
    const nodes = buildNodes(list)
    const byId = new Map(nodes.map((n) => [n.spanId, n]))
    nodes.forEach((n) => {
      const className = n.className || ''
      const method = n.method || ''
      const full = className ? className + '#' + method : method
      if (!(test(full) || test(method) || test(className))) return

      const segs = []
      let cur = n
      const guard = new Set()
      while (cur && !guard.has(cur.spanId)) {
        guard.add(cur.spanId)
        segs.unshift(cur.method || cur.spanId)
        cur = cur.parentId ? byId.get(cur.parentId) : null
        if (segs.length > 60) break
      }
      out.push({
        key: traceId + '/' + n.spanId,
        traceId,
        spanId: n.spanId,
        label: full || n.spanId,
        method,
        className,
        durationMs: n.durationMs,
        thread: n.thread,
        pathSegments: segs
      })
    })
  })
  // 耗时降序：搜方法通常是想看"最慢的那次"
  out.sort((a, b) => (b.durationMs || 0) - (a.durationMs || 0))
  return out
})

const totalHits = computed(() => allHits.value.length)
const hitTraceCount = computed(() => new Set(allHits.value.map(h => h.traceId)).size)

/**
 * 按方法去重汇总。
 *
 * <h2>为什么要去重</h2>
 * 一次分页查询里 {@code ServiceImpl#page} 可能被调 50 次（循环 + 嵌套），
 * 直接平铺会把下拉/列表刷满同一个方法名，用户根本看不出"到底有哪些方法命中"。
 * 汇总后每个方法一行，附带次数、最大耗时、总耗时、涉及几条链 ——
 * 这才是"统计"该给出的信息。
 *
 * <p>关掉去重时退化为"每次调用一行"，但仍按耗时降序。
 */
const groups = computed(() => {
  const hits = allHits.value
  if (!groupByMethod.value) {
    return hits.map((h, i) => ({
      key: h.key + '#' + i,
      label: h.label,
      hits: [h],
      maxMs: h.durationMs || 0,
      totalMs: h.durationMs || 0,
      traceCount: 1
    }))
  }
  const map = new Map()
  hits.forEach((h) => {
    // 去重键用 类名#方法：同名方法在不同类里是不同实体，不能合并
    const k = h.label
    let g = map.get(k)
    if (!g) {
      g = { key: k, label: k, hits: [], maxMs: 0, totalMs: 0, traceIds: new Set() }
      map.set(k, g)
    }
    g.hits.push(h)
    g.maxMs = Math.max(g.maxMs, h.durationMs || 0)
    g.totalMs += h.durationMs || 0
    g.traceIds.add(h.traceId)
  })
  const arr = Array.from(map.values()).map(g => ({
    key: g.key,
    label: g.label,
    hits: g.hits,
    maxMs: g.maxMs,
    totalMs: g.totalMs,
    traceCount: g.traceIds.size
  }))
  // 按最大耗时降序：最值得看的排前面
  arr.sort((a, b) => b.maxMs - a.maxMs)
  return arr
})

/** 最慢的那次命中，顶部条形态用它做"自动定位"。 */
const slowestHit = computed(() => allHits.value[0] || null)

// 顶部条形态：搜索有结果就直接跳到最慢那次，省掉一次点击。
// 清空关键字时也要 emit（带 null），让父组件解除 trace 锁定，
// 否则用户搜过一次之后自动刷新就再也不会跟随新请求了。
//
// 加 lastEmittedTrace 防抖：自动刷新会让 slowestHit 对象引用变化但 traceId 没变，
// 不拦住的话每次刷新都往父组件发一次相同的跳转指令。
let lastEmittedTrace = null
watch([kw, slowestHit], () => {
  if (!props.compact) return
  const hit = slowestHit.value || null
  const tid = hit ? hit.traceId : null
  if (tid === lastEmittedTrace) return
  lastEmittedTrace = tid
  emit('hit-change', hit)
}, { immediate: true })

watch(kw, () => { activeGroup.value = 0 })

// ---- 完整面板：选中某次调用后展示其 trace 树 ----
const activeHit = ref(null)
const activeTraceId = computed(() => activeHit.value ? activeHit.value.traceId : '')

const activeNodes = computed(() => {
  const hit = activeHit.value
  if (!hit) return []
  const list = (props.spans || []).filter(s => s.traceId === hit.traceId)
  const nodes = buildNodes(list)
  nodes.forEach(n => { n.isHit = n.spanId === hit.spanId })
  return nodes
})

function toggleGroup(gi) {
  activeGroup.value = gi === activeGroup.value ? -1 : gi
  // 展开时默认选中第一个（即最慢那次），树立刻有内容，不用再点一层
  const g = groups.value[gi]
  if (g && g.hits.length) activeHit.value = g.hits[0]
}

function selectHit(h) {
  activeHit.value = h
}

function openInMain() {
  if (activeTraceId.value) emit('open-trace', activeTraceId.value)
}

/**
 * 时长展示：统一走 formatDuration，按 ms → s → m → h → d 逐级递增。
 *
 * <p>旧实现是 {@code ms >= 1000 ? (ms/1000).toFixed(2)+' s' : ms+' ms'}，
 * 两档硬切；61 秒会显示成 "61.23 s"，不符合正常读数习惯。
 */
function fmt(ms) {
  return formatDuration(ms)
}

function durClass(ms) {
  if (ms == null) return ''
  if (ms > 1000) return 'dur-bad'
  if (ms > 300) return 'dur-warn'
  return 'dur-ok'
}
</script>

<style scoped>
.search-root { display: flex; flex-direction: column; }
/* 顶部条形态：与其他工具栏控件同高、同一行。
   宽度收窄到 220，配合 App 里下拉的收窄，保证 1440 宽也不折行 */
.search-root.compact {
  flex-direction: row;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.search-bar { display: flex; align-items: center; gap: 8px; }
.search-root.compact .search-input { width: 220px; }
.search-root.compact .summary { max-width: 300px; overflow: hidden; text-overflow: ellipsis; }
.search-root:not(.compact) .search-bar {
  padding: 8px 12px;
  background: #26282b;
  border-bottom: 1px solid #3c3f41;
  flex-shrink: 0;
}
.search-root:not(.compact) .search-input { width: 460px; }
.prefix-icon { color: #7a7a7a; font-size: 14px; }

.opts {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 12px;
  background: #232528;
  border-bottom: 1px solid #3c3f41;
}
.summary { font-size: 12px; color: #9a9a9a; white-space: nowrap; }
.summary b { color: #6cc06c; }
.dim { color: #7a7a7a; }
.small { font-size: 11px; }
.detail-btn { padding: 0 4px; }
.jump-hint { font-size: 11px; color: #7a7a7a; white-space: nowrap; }
.jump-hint b { color: #6cb6ff; font-family: Consolas, Monaco, monospace; }

.result-area { flex: 1; min-height: 100px; overflow: auto; padding: 6px 10px; }
.group-list { display: flex; flex-direction: column; gap: 3px; }
.group-item { background: #232528; border-radius: 4px; border: 1px solid transparent; }
.group-item.open { border-color: #3c4a63; }
.group-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 8px;
  cursor: pointer;
  font-size: 12px;
}
.group-head:hover { background: #2a2d31; border-radius: 4px; }
.caret { color: #6f6f6f; width: 10px; }
.group-label {
  color: #d6d6d6;
  font-family: Consolas, Monaco, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}
.count-badge {
  font-size: 11px;
  color: #6cc06c;
  border: 1px solid #35502f;
  background: #23301f;
  border-radius: 8px;
  padding: 0 6px;
  flex-shrink: 0;
}
.group-stat {
  font-size: 11px;
  color: #9a9a9a;
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}
.hit-list { padding: 2px 6px 6px 24px; display: flex; flex-direction: column; gap: 2px; }
.hit-item {
  display: flex;
  gap: 8px;
  padding: 3px 6px;
  border-radius: 3px;
  cursor: pointer;
}
.hit-item:hover { background: #2a2d31; }
.hit-dur {
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  width: 72px;
  text-align: right;
  flex-shrink: 0;
}
.hit-body { flex: 1; min-width: 0; }
.hit-path {
  font-size: 11px;
  color: #8a8a8a;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 3px;
}
.hit-path .arrow { color: #555; }
.hit-path .seg { color: #9a9a9a; }
.hit-path .seg.self { color: #6cb6ff; font-weight: 600; }
.hit-meta { font-size: 10px; color: #666; }

.tree-area {
  height: 40%;
  min-height: 130px;
  border-top: 1px solid #3c3f41;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}
.tree-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 12px;
  background: #26282b;
  color: #9a9a9a;
  font-size: 12px;
  flex-shrink: 0;
}
.tree-body { flex: 1; overflow: auto; padding: 4px 10px; }
.tree-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 2px 4px;
  border-radius: 3px;
}
.tree-row.hit { background: #2b3a55; }
.tree-dot { width: 5px; height: 5px; border-radius: 50%; background: #555; flex-shrink: 0; }
.tree-row.hit .tree-dot { background: #6cb6ff; }
.tree-label {
  color: #c8c8c8;
  font-family: Consolas, Monaco, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}
.tree-row.hit .tree-label { color: #fff; font-weight: 600; }
.tree-dur { font-variant-numeric: tabular-nums; font-size: 11px; }

.dur-ok { color: #6cc06c; }
.dur-warn { color: #e0a050; }
.dur-bad { color: #e06c6c; }
</style>
