<template>
  <div class="diff-wrap">
    <div class="bar">
      <span class="label">基线</span>
      <el-select v-model="baseId" size="small" class="sel" placeholder="改动前的录制">
        <el-option v-for="s in sessions" :key="s.id" :value="s.id" :label="sessionLabel(s)" />
      </el-select>
      <span class="arrow">→</span>
      <span class="label">对比</span>
      <el-select v-model="currId" size="small" class="sel" placeholder="改动后的录制">
        <el-option v-for="s in sessions" :key="s.id" :value="s.id" :label="sessionLabel(s)" />
      </el-select>
      <el-button type="primary" size="small" :loading="loading" @click="runDiff">对比</el-button>
      <span class="summary">{{ summary }}</span>
    </div>

    <el-table
      v-if="rows.length"
      :data="rows"
      size="small"
      stripe
      height="100%"
      class="tbl"
      :row-class-name="rowClass"
    >
      <el-table-column label="方法" min-width="260" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="method">{{ row.displayName }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80" align="center">
        <template #default="{ row }">
          <span class="badge" :class="statusClass(row)">{{ row.statusText }}</span>
        </template>
      </el-table-column>
      <el-table-column label="基线自身" width="100" align="right">
        <template #default="{ row }">{{ row.aSelfMs }} ms</template>
      </el-table-column>
      <el-table-column label="对比自身" width="100" align="right">
        <template #default="{ row }">{{ row.bSelfMs }} ms</template>
      </el-table-column>
      <el-table-column label="变化" width="90" align="right">
        <template #default="{ row }">
          <span :class="deltaClass(row)">{{ row.deltaText }}</span>
        </template>
      </el-table-column>
      <el-table-column label="变化率" width="90" align="right">
        <template #default="{ row }">
          <span :class="deltaClass(row)">{{ row.percentText }}</span>
        </template>
      </el-table-column>
      <el-table-column label="调用次数" width="100" align="right">
        <template #default="{ row }">{{ row.countText }}</template>
      </el-table-column>
      <el-table-column label="变化条" min-width="140">
        <template #default="{ row }">
          <div v-if="row.barPct > 0" class="bar-wrap">
            <div class="bar" :class="row.deltaMs >= 0 ? 'up' : 'down'" :style="{ width: row.barPct + '%' }"></div>
          </div>
        </template>
      </el-table-column>
    </el-table>
    <div v-else class="empty">{{ emptyText }}</div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { cefCall } from '../utils/bridge'
import { buildTraces, buildNodes } from '../utils/span'

const props = defineProps({
  sessions: { type: Array, default: () => [] },
  active: { type: Boolean, default: false },
})

const baseId = ref('')
const currId = ref('')
const rows = ref([])
const loading = ref(false)
const summary = ref('')
const emptyText = ref('选择两次不同的录制后点击「对比」')

function sessionLabel(s) {
  const m = /callmap-(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})-/.exec(s.id || '')
  const time = m ? `${m[2]}-${m[3]} ${m[4]}:${m[5]}:${m[6]}` : s.id
  const kb = Math.max(1, Math.round((s.sizeBytes || 0) / 1024))
  return `${time}  (${kb} KB)`
}

/** 默认选「最新=对比、次新=基线」，符合"刚跑完想和上次比"的高频场景。 */
function initDefaults() {
  if (props.sessions.length >= 2) {
    if (!currId.value) currId.value = props.sessions[0].id
    if (!baseId.value) baseId.value = props.sessions[1].id
  } else if (props.sessions.length === 1) {
    if (!currId.value) currId.value = props.sessions[0].id
  }
}

/**
 * 会话列表到位后设置默认选中项，并在本页已激活时自动跑一次对比。
 *
 * <h2>为什么不能只监听 active</h2>
 * 用户反馈"会话对比不好用"，典型场景是：
 * 切到本页时 {@code sessions} 还在异步加载 → {@code baseId/currId} 都是空 →
 * 之后 sessions 到位、默认值被初始化了，但 <b>没有任何人再触发 runDiff</b>，
 * 界面就一直停在"请选择两个会话"。
 * 所以在 sessions 变化时也要检查一次。
 */
watch(() => props.sessions, () => {
  initDefaults()
  maybeAutoDiff()
}, { immediate: true })

/** 本页激活时若还没有结果，自动比一次（避免用户面对空白页）。 */
watch(() => props.active, () => {
  initDefaults()
  maybeAutoDiff()
})

/**
 * 满足条件时自动执行对比。
 *
 * <p>三个前提：本页激活、还没有结果、两个会话都选好且不相同。
 * 用 {@code loading} 兜住并发，避免 sessions 连续两次变化触发重复请求。
 */
function maybeAutoDiff() {
  if (!props.active || loading.value) return
  if (rows.value.length) return
  if (!baseId.value || !currId.value) return
  if (baseId.value === currId.value) return
  runDiff()
}

/**
 * 按「类#方法」把一次会话的 span 聚合起来。
 * 用类全名做键，避免不同包下的同名类被错误合并。
 */
function aggregate(spans) {
  const built = buildTraces(spans)
  const map = new Map()
  for (const tid of Object.keys(built.traces)) {
    const nodes = buildNodes(built.traces[tid])
    const walk = (n) => {
      const key = (n.className || '') + '#' + (n.method || '')
      let r = map.get(key)
      if (!r) {
        r = { key, displayName: n.label || key, selfMs: 0, count: 0 }
        map.set(key, r)
      }
      r.selfMs += n.selfMs || 0
      r.count += 1
      ;(n.children || []).forEach(walk)
    }
    nodes.forEach(walk)
  }
  return map
}

async function runDiff() {
  if (!baseId.value || !currId.value) {
    emptyText.value = '请选择基线和对比两个会话'
    return
  }
  if (baseId.value === currId.value) {
    emptyText.value = '基线与对比不能是同一个会话'
    return
  }
  loading.value = true
  emptyText.value = '加载中...'
  const [a, b] = await Promise.all([
    cefCall('loadSpans:' + baseId.value),
    cefCall('loadSpans:' + currId.value),
  ])
  loading.value = false
  if (!a || !a.ok || !b || !b.ok) {
    emptyText.value = '加载会话数据失败'
    return
  }

  const mapA = aggregate(a.spans || [])
  const mapB = aggregate(b.spans || [])
  const keys = new Set([...mapA.keys(), ...mapB.keys()])

  const list = []
  let aTotal = 0
  let bTotal = 0
  for (const key of keys) {
    const ra = mapA.get(key)
    const rb = mapB.get(key)
    const aSelf = ra ? ra.selfMs : 0
    const bSelf = rb ? rb.selfMs : 0
    const delta = bSelf - aSelf
    const percent = aSelf === 0 ? 0 : (delta * 100) / aSelf
    aTotal += aSelf
    bTotal += bSelf

    const onlyInB = !ra
    const onlyInA = !rb
    let statusText = '持平'
    if (onlyInB) statusText = '新增'
    else if (onlyInA) statusText = '移除'
    else if (delta > 1) statusText = '变慢'
    else if (delta < -1) statusText = '变快'

    list.push({
      key,
      displayName: (rb || ra).displayName,
      aSelfMs: Math.round(aSelf),
      bSelfMs: Math.round(bSelf),
      deltaMs: Math.round(delta),
      deltaText: onlyInA || onlyInB ? '' : (delta > 0 ? '+' : '') + Math.round(delta) + 'ms',
      percentText: onlyInB ? '新增' : onlyInA ? '已移除'
        : (aSelf === 0 ? (delta === 0 ? '持平' : '—') : (percent > 0 ? '+' : '') + percent.toFixed(1) + '%'),
      countText: (ra ? ra.count : 0) === (rb ? rb.count : 0)
        ? String(rb ? rb.count : 0)
        : `${ra ? ra.count : 0} → ${rb ? rb.count : 0}`,
      statusText,
    })
  }

  // 按变慢幅度降序：最该关注的性能回退排最前
  list.sort((x, y) => y.deltaMs - x.deltaMs)
  const maxAbs = Math.max(1, ...list.map((r) => Math.abs(r.deltaMs)))
  list.forEach((r) => { r.barPct = (Math.abs(r.deltaMs) / maxAbs) * 100 })
  rows.value = list

  const slower = list.filter((r) => r.statusText === '变慢').length
  const faster = list.filter((r) => r.statusText === '变快').length
  const overall = aTotal === 0 ? 0 : ((bTotal - aTotal) * 100) / aTotal
  summary.value = `整体自身耗时 ${Math.round(aTotal)}ms → ${Math.round(bTotal)}ms `
    + `(${overall > 0 ? '+' : ''}${overall.toFixed(1)}%) ｜ 变慢 ${slower}，变快 ${faster}`
}

function statusClass(row) {
  if (row.statusText === '变慢' || row.statusText === '新增') return 'bad'
  if (row.statusText === '变快' || row.statusText === '移除') return 'good'
  return 'flat'
}

function deltaClass(row) {
  if (row.deltaMs > 1) return 'bad'
  if (row.deltaMs < -1) return 'good'
  return 'flat'
}

function rowClass({ row }) {
  return row.deltaMs > 1 ? 'row-worse' : ''
}
</script>

<style scoped>
.diff-wrap {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  background: #26282b;
  border-bottom: 1px solid #3c3f41;
  flex-shrink: 0;
  flex-wrap: wrap;
}
.label { color: #8a8a8a; font-size: 12px; }
.arrow { color: #6a6a6a; }
.sel { width: 170px; }
.summary { margin-left: auto; color: #8a8a8a; font-size: 11px; }
.tbl { flex: 1; min-height: 0; }
.method { font-family: Consolas, monospace; font-size: 12px; }
.badge {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 11px;
  background: #3c3f41;
  color: #a0a0a0;
}
.badge.bad { background: #4a1f1f; color: #f56c6c; }
.badge.good { background: #1f3a2a; color: #67c23a; }
.bad { color: #f56c6c; }
.good { color: #67c23a; }
.flat { color: #8a8a8a; }
.bar-wrap { height: 8px; background: #303236; border-radius: 4px; overflow: hidden; }
.bar { height: 100%; border-radius: 4px; }
.bar.up { background: #f56c6c; }
.bar.down { background: #67c23a; }
.empty { padding: 40px; text-align: center; color: #6a6a6a; }
:deep(.row-worse) { background: rgba(245, 108, 108, 0.06) !important; }
</style>
