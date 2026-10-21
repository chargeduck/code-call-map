<template>
  <div class="stats-wrap">
    <div class="bar">
      <span class="label">慢调用阈值</span>
      <el-input-number
        v-model="thresholdMs"
        :min="0"
        :step="100"
        size="small"
        controls-position="right"
        class="thr"
        @change="onThresholdChange"
      />
      <span class="unit">ms</span>
      <el-select v-model="scope" size="small" class="scope" @change="reload">
        <el-option label="当前会话" value="current" />
        <el-option label="全部会话（跨 trace 统计）" value="all" />
      </el-select>
      <el-button size="small" :loading="loading" @click="reload">刷新</el-button>
      <span class="summary">{{ summary }}</span>
    </div>

    <el-tabs v-model="innerTab" class="inner">
      <!-- 按入口接口聚合：反复调用同一接口的场景 -->
      <el-tab-pane label="按接口统计" name="entry">
        <el-table v-if="entryRows.length" :data="entryRows" size="small" stripe height="100%" class="tbl">
          <el-table-column label="接口 / 入口方法" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="mono">{{ row.label }}</span>
              <div v-if="row.path" class="dim">{{ row.path }}</div>
            </template>
          </el-table-column>
          <el-table-column label="调用次数" width="90" align="right" prop="count" />
          <el-table-column label="平均耗时" width="100" align="right">
            <template #default="{ row }">{{ fmt(row.avgMs) }} ms</template>
          </el-table-column>
          <el-table-column label="P95" width="90" align="right">
            <template #default="{ row }">{{ fmt(row.p95Ms) }} ms</template>
          </el-table-column>
          <el-table-column label="最大耗时" width="100" align="right">
            <template #default="{ row }">
              <span :class="isSlow(row.maxMs) ? 'bad' : ''">{{ fmt(row.maxMs) }} ms</span>
            </template>
          </el-table-column>
          <el-table-column label="慢调用" width="90" align="right">
            <template #default="{ row }">
              <span :class="row.slowCount ? 'bad' : 'dim'">{{ row.slowCount }}</span>
            </template>
          </el-table-column>
          <el-table-column label="慢占比" width="160">
            <template #default="{ row }">
              <div class="bar-wrap">
                <div class="bar" :class="rateClass(row.slowRate)" :style="{ width: (row.slowRate * 100) + '%' }"></div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="异常" width="70" align="right">
            <template #default="{ row }">
              <span :class="row.exceptions ? 'bad' : 'dim'">{{ row.exceptions || '-' }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div v-else class="empty">{{ emptyText }}</div>
      </el-tab-pane>

      <!-- 按方法聚合：不同接口走到同一段代码的场景 -->
      <el-tab-pane label="按方法统计" name="method">
        <div class="hint-row">
          同一段代码被多个请求复用时的累计开销；「涉及调用」是走到该方法的不同请求数
        </div>
        <el-table v-if="methodRows.length" :data="methodRows" size="small" stripe height="100%" class="tbl">
          <el-table-column label="方法" min-width="260" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="mono">{{ row.label }}</span>
            </template>
          </el-table-column>
          <el-table-column label="执行次数" width="95" align="right" prop="count" />
          <el-table-column label="涉及调用" width="95" align="right" prop="traceCount" />
          <el-table-column label="累计耗时" width="105" align="right">
            <template #default="{ row }">{{ fmt(row.totalMs) }} ms</template>
          </el-table-column>
          <el-table-column label="平均耗时" width="100" align="right">
            <template #default="{ row }">{{ fmt(row.avgMs) }} ms</template>
          </el-table-column>
          <el-table-column label="最大耗时" width="100" align="right">
            <template #default="{ row }">
              <span :class="isSlow(row.maxMs) ? 'bad' : ''">{{ fmt(row.maxMs) }} ms</span>
            </template>
          </el-table-column>
          <el-table-column label="慢调用" width="85" align="right">
            <template #default="{ row }">
              <span :class="row.slowCount ? 'bad' : 'dim'">{{ row.slowCount }}</span>
            </template>
          </el-table-column>
          <el-table-column label="异常" width="70" align="right">
            <template #default="{ row }">
              <span :class="row.exceptionCount ? 'bad' : 'dim'">{{ row.exceptionCount || '-' }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div v-else class="empty">{{ emptyText }}</div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { cefCall } from '../utils/bridge'
import { buildTraces, buildNodes } from '../utils/span'
import {
  DEFAULT_SLOW_THRESHOLD_MS,
  flattenTraces,
  aggregateByEntry,
  aggregateByMethod,
} from '../utils/stats'

const props = defineProps({
  sessions: { type: Array, default: () => [] },
  currentSessionId: { type: String, default: '' },
  active: { type: Boolean, default: false },
})

const thresholdMs = ref(DEFAULT_SLOW_THRESHOLD_MS)
const scope = ref('current')
const innerTab = ref('entry')
const loading = ref(false)
const summary = ref('')
const emptyText = ref('点击「刷新」加载统计数据')
const entryRows = ref([])
const methodRows = ref([])

/** 从插件读取用户配置的阈值，保证界面与设置页一致。 */
async function loadSettings() {
  const resp = await cefCall('getSettings')
  if (resp && resp.ok && typeof resp.slowThresholdMs === 'number') {
    thresholdMs.value = resp.slowThresholdMs
    console.log('[CCM] 慢调用阈值:', thresholdMs.value, 'ms')
  }
}

/** 拉取一个会话的 spans 并构建 traces。 */
async function fetchTraces(sessionId) {
  const resp = await cefCall('loadSpans:' + sessionId)
  if (!resp || !resp.ok) {
    console.warn('[CCM] 加载会话失败:', sessionId, resp && resp.error)
    return {}
  }
  return buildTraces(resp.spans || []).traces
}

async function reload() {
  loading.value = true
  emptyText.value = '加载中...'
  try {
    await loadSettings()

    const ids = scope.value === 'all'
      ? props.sessions.map((s) => s.id)
      : (props.currentSessionId ? [props.currentSessionId] : [])

    console.log('[CCM] 统计范围:', scope.value, '会话数:', ids.length)

    const merged = {}
    for (const id of ids) {
      const t = await fetchTraces(id)
      // traceId 在不同会话里可能重复，加会话前缀保证唯一
      Object.keys(t).forEach((tid) => { merged[id + '/' + tid] = t[tid] })
    }

    const flat = flattenTraces(merged, buildNodes)
    entryRows.value = aggregateByEntry(flat, thresholdMs.value)
    methodRows.value = aggregateByMethod(flat, thresholdMs.value)

    console.log('[CCM] 统计完成:', {
      traces: Object.keys(merged).length,
      nodes: flat.length,
      entries: entryRows.value.length,
      methods: methodRows.value.length,
    })

    if (!flat.length) {
      emptyText.value = '没有数据。请用 Run/Debug with CCM 采集后再看'
      summary.value = ''
      return
    }
    const totalSlow = entryRows.value.reduce((s, r) => s + r.slowCount, 0)
    summary.value = `${Object.keys(merged).length} 次调用 / ${entryRows.value.length} 个入口 / `
      + `${methodRows.value.length} 个方法，慢调用(≥${thresholdMs.value}ms) ${totalSlow} 次`
  } finally {
    loading.value = false
  }
}

function onThresholdChange() {
  // 阈值是纯前端判定，改完直接重算，不必重新请求后端
  if (entryRows.value.length || methodRows.value.length) {
    reload()
  }
}

function fmt(v) {
  if (v == null) return '-'
  return v >= 100 ? String(Math.round(v)) : String(v)
}

function isSlow(ms) {
  return (ms || 0) >= thresholdMs.value
}

function rateClass(rate) {
  if (rate >= 0.5) return 'bar-bad'
  if (rate > 0) return 'bar-warn'
  return 'bar-ok'
}

/**
 * 切到本页时自动加载。
 *
 * <h2>为什么不能只监听 active 的 false→true</h2>
 * 用户反馈"调用统计不好用"，常见原因是：
 * <ol>
 *   <li><b>首次进入时 sessions 还没加载完</b> —— 此时 reload 拿到的会话 id 列表是空的，
 *       直接得到"没有数据"，之后再也不会自动重试；</li>
 *   <li>{@code watch} 若在挂载时 active 就已是 true，则初始不会触发。</li>
 * </ol>
 * 所以用 immediate + 同时监听 sessions 变化：只要本页处于激活状态、
 * 且有数据可统计，就自动加载一次；已经加载过的不重复请求。
 */
watch(
  () => [props.active, props.sessions, props.currentSessionId],
  () => {
    if (!props.active) return
    // 已有的结果不重复拉取（用户可点「刷新」强制重新统计）
    if (entryRows.value.length || methodRows.value.length) return
    // 还没有任何会话可选时不要请求，等 sessions 到位后再由上面的 watch 触发
    if (!props.currentSessionId && !props.sessions.length) return
    reload()
  },
  { immediate: true, deep: false }
)

/** 会话切换后清空旧结果，避免显示上一个会话的统计。 */
watch(() => props.currentSessionId, () => {
  entryRows.value = []
  methodRows.value = []
  summary.value = ''
  emptyText.value = '切换到新会话，正在等待加载…'
  if (props.active) reload()
})
</script>

<style scoped>
.stats-wrap { height: 100%; display: flex; flex-direction: column; min-height: 0; }
.bar {
  display: flex; align-items: center; gap: 8px; padding: 6px 12px;
  background: #26282b; border-bottom: 1px solid #3c3f41; flex-shrink: 0; flex-wrap: wrap;
}
.label { color: #8a8a8a; font-size: 12px; }
.unit { color: #6a6a6a; font-size: 12px; }
.thr { width: 120px; }
.scope { width: 200px; }
.summary { margin-left: auto; color: #8a8a8a; font-size: 11px; }
.inner { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.inner :deep(.el-tabs__header) { margin: 0; padding: 0 12px; background: #26282b; flex-shrink: 0; }
.inner :deep(.el-tabs__content) { flex: 1; min-height: 0; overflow: hidden; padding: 0; }
.inner :deep(.el-tab-pane) { height: 100%; }
.tbl { height: 100%; }
.mono { font-family: Consolas, monospace; font-size: 12px; }
.dim { color: #6a6a6a; font-size: 11px; }
.bad { color: #f56c6c; }
.hint-row { padding: 4px 12px; color: #8a8a8a; font-size: 11px; background: #232528; }
.bar-wrap { height: 8px; background: #303236; border-radius: 4px; overflow: hidden; }
.bar { height: 100%; border-radius: 4px; }
.bar-bad { background: #f56c6c; }
.bar-warn { background: #e6a23c; }
.bar-ok { background: #67c23a; }
.empty { padding: 40px; text-align: center; color: #6a6a6a; }
</style>
