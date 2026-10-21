<template>
  <div class="table-wrap">
    <el-empty v-if="!nodes || nodes.length === 0" description="暂无数据" />
    <el-table v-else :data="nodes" size="small" height="100%" stripe @row-click="jumpToSource">
      <el-table-column prop="spanId" label="Span" width="150" show-overflow-tooltip />
      <el-table-column label="父 Span" width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.parentId || '-' }}</template>
      </el-table-column>
      <el-table-column label="调用" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.label }}</template>
      </el-table-column>
      <!-- 入参：排查"为什么慢"时经常要先确认是不是参数本身导致的
           （比如分页 size 传了 100000、查询条件为空全表扫）-->
      <el-table-column label="入参" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">
          <code v-if="row.args" class="args-cell">{{ row.args }}</code>
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="100" align="right">
        <template #default="{ row }">
          <span :class="durationClass(row)">{{ fmt(row.durationMs) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="自身" width="100" align="right">
        <template #default="{ row }">
          <span :class="selfClass(row)">{{ fmt(row.selfMs) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="层级" width="60" align="center">
        <template #default="{ row }">{{ (row.depth || 0) + 1 }}</template>
      </el-table-column>
      <el-table-column prop="thread" label="线程" width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ row.thread || '-' }}</template>
      </el-table-column>
      <el-table-column label="SQL" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="ok">{{ row.sql || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="异常" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="err">{{ row.exception || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="路径" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.path || '-' }}</template>
      </el-table-column>
      <el-table-column label="源码" width="96" align="center">
        <template #default="{ row }">
          <el-button size="small" type="primary" text :disabled="!canJump(row)" @click.stop="jumpToSource(row)">跳转源码</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { formatDuration } from '../utils/duration'

const props = defineProps({
  nodes: { type: Array, default: () => [] }
})
const emit = defineEmits(['jump'])

function fmt(v) {
  return formatDuration(v)
}

function canJump(row) {
  return !!(row && (row.sourceFile || (row.className && row.method)))
}
function jumpToSource(row) {
  if (canJump(row)) emit('jump', row)
}

function durationClass(row) {
  if (row.exception) return 'err'
  if (row.durationMs != null && row.durationMs > 300) return 'warn'
  return 'ok'
}

/** 自身耗时高亮：这是「自己慢」的直接指标。 */
function selfClass(row) {
  if ((row.selfMs || 0) > 300) return 'err'
  if ((row.selfMs || 0) > 100) return 'warn'
  return 'ok'
}
</script>

<style scoped>
.table-wrap {
  height: 100%;
  overflow: auto;
  padding: 8px;
  box-sizing: border-box;
}
.table-wrap :deep(.el-table) {
  background: transparent;
  color: #c8c8c8;
  font-size: 12px;
}
.table-wrap :deep(.el-table tr),
.table-wrap :deep(.el-table th.el-table__cell),
.table-wrap :deep(.el-table td.el-table__cell) {
  background: transparent;
}
.table-wrap :deep(.el-table th.el-table__cell) {
  background: #2b2d30;
  position: sticky;
  top: 0;
}
.warn { color: #e0a050; }
.err { color: #e06c6c; }
.ok { color: #6cc06c; }
/* 入参用等宽字体：JSON 里的括号引号对齐后更易扫读 */
.args-cell {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  color: #a9b7c6;
}
.muted { color: #666; }
</style>
