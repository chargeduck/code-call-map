<template>
  <div class="hotspot-wrap">
    <div class="section">
      <div class="section-title">
        耗时热点（按自身耗时排序）
        <span class="hint">自身耗时 = 总耗时 − 子调用耗时，直接指向「自己慢」的方法</span>
      </div>
      <el-table
        v-if="hotspots.length"
        :data="hotspots"
        size="small"
        stripe
        height="260"
        @row-click="onJump"
      >
        <el-table-column label="#" width="46" align="center">
          <template #default="{ $index }">
            <span class="rank" :class="rankClass($index)">{{ $index + 1 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="调用" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <span :style="{ paddingLeft: (row.depth * 10) + 'px' }" class="call">{{ row.label }}</span>
          </template>
        </el-table-column>
        <el-table-column label="自身耗时" width="110" align="right">
          <template #default="{ row }">
            <span class="self">{{ fmt(row.selfMs) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="总耗时" width="100" align="right">
          <template #default="{ row }">{{ fmt(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column label="占比" width="150">
          <template #default="{ row }">
            <div class="bar-wrap">
              <div class="bar" :style="{ width: pct(row.selfMs) + '%', background: barColor(row.selfMs) }"></div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="异常" width="60" align="center">
          <template #default="{ row }">
            <span v-if="row.exception" class="err">!</span>
            <span v-else class="dim">-</span>
          </template>
        </el-table-column>
      </el-table>
      <div v-else class="empty">暂无耗时数据（点击上方「加载真实数据」）</div>
    </div>

    <div class="section">
      <div class="section-title">
        按类聚合
        <span class="hint">哪个类的代码占用了最多时间</span>
      </div>
      <el-table v-if="byClass.length" :data="byClass" size="small" stripe height="200">
        <el-table-column prop="className" label="类" min-width="240" show-overflow-tooltip />
        <el-table-column label="累计自身耗时" width="130" align="right">
          <template #default="{ row }">
            <span class="self">{{ fmt(row.selfMs) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="调用次数" width="90" align="right">
          <template #default="{ row }">{{ row.count }}</template>
        </el-table-column>
        <el-table-column label="单次最大" width="100" align="right">
          <template #default="{ row }">{{ fmt(row.maxMs) }}</template>
        </el-table-column>
        <el-table-column label="占比" min-width="140">
          <template #default="{ row }">
            <div class="bar-wrap">
              <div class="bar" :style="{ width: pctClass(row.selfMs) + '%', background: barColor(row.selfMs) }"></div>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div v-else class="empty">暂无数据</div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { findHotspots, aggregateByClass } from '../utils/span'
import { formatDuration } from '../utils/duration'

const props = defineProps({
  nodes: { type: Array, default: () => [] }
})
const emit = defineEmits(['jump'])

/** 单次调用超过该毫秒数视为「慢」，用于颜色分级。 */
const SLOW_MS = 300
/** 明显偏慢的阈值。 */
const WARN_MS = 100

const hotspots = computed(() => findHotspots(props.nodes, 15))
const byClass = computed(() => aggregateByClass(props.nodes).slice(0, 15))

const maxSelf = computed(() =>
  hotspots.value.reduce((m, n) => Math.max(m, n.selfMs || 0), 0) || 1
)
const maxClassSelf = computed(() =>
  byClass.value.reduce((m, n) => Math.max(m, n.selfMs || 0), 0) || 1
)

/**
 * 格式化耗时。
 *
 * <p>旧的实现是 {@code n >= 1000 ? (n/1000).toFixed(2) + 'k' : ...}，
 * 而模板里又拼了 " ms" —— 于是 1234ms 显示成 <b>"1.23k ms"</b>，
 * 把千位缩写当成时间单位用了，读起来完全是错的。
 * 现在统一走 {@link formatDuration}，单位由它自己带。
 */
function fmt(v) {
  return formatDuration(v)
}
function pct(v) {
  return Math.min(100, Math.round((Number(v || 0) / maxSelf.value) * 100))
}
function pctClass(v) {
  return Math.min(100, Math.round((Number(v || 0) / maxClassSelf.value) * 100))
}
function barColor(v) {
  const n = Number(v || 0)
  if (n >= SLOW_MS) return '#e06c6c'
  if (n >= WARN_MS) return '#e0a050'
  return '#4caf50'
}
function rankClass(i) {
  if (i === 0) return 'r1'
  if (i < 3) return 'r2'
  return ''
}
function onJump(row) {
  emit('jump', row)
}
</script>

<style scoped>
.hotspot-wrap {
  height: 100%;
  overflow: auto;
  padding: 8px;
  box-sizing: border-box;
}
.section { margin-bottom: 12px; }
.section-title {
  font-size: 12px;
  color: #c8c8c8;
  padding: 4px 4px 8px;
  font-weight: 500;
}
.hint {
  color: #7a7a7a;
  font-size: 11px;
  font-weight: 400;
  margin-left: 8px;
}
.call { color: #c8c8c8; }
.self { color: #e0a050; }
.dim { color: #5a5a5a; }
.err { color: #e06c6c; font-weight: 500; }
.rank {
  display: inline-block;
  width: 18px;
  text-align: center;
  color: #8a8a8a;
  font-size: 11px;
}
.rank.r1 { color: #e06c6c; font-weight: 500; }
.rank.r2 { color: #e0a050; }
.bar-wrap {
  background: #2b2d30;
  height: 8px;
  border-radius: 4px;
  overflow: hidden;
  margin-top: 5px;
}
.bar { height: 100%; border-radius: 4px; }
.empty {
  text-align: center;
  color: #666;
  font-size: 13px;
  padding: 30px 0;
}
.hotspot-wrap :deep(.el-table) {
  background: transparent;
  color: #c8c8c8;
  font-size: 12px;
}
.hotspot-wrap :deep(.el-table tr),
.hotspot-wrap :deep(.el-table th.el-table__cell),
.hotspot-wrap :deep(.el-table td.el-table__cell) {
  background: transparent;
}
.hotspot-wrap :deep(.el-table th.el-table__cell) {
  background: #2b2d30;
}
</style>
