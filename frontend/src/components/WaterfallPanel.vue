<template>
  <div class="panel-wrap">
    <!-- 与「时序图」同一套思路：滚动高度由 .wf-inner 撑开，
         画布只有视口大小并 sticky 在左上角。
         一次请求动辄上千个 span，如果画布按内容高度建（30px × 2000 = 6 万像素），
         既会撞上浏览器画布尺寸上限，也会让切换 tab 时重建图层卡住。 -->
    <div ref="scrollEl" class="wf-scroll" @scroll="onScroll">
      <div class="wf-inner" :style="{ width: contentW + 'px', height: contentH + 'px' }">
        <canvas
          ref="canvasEl"
          class="wf-canvas"
          @click="onCanvasClick"
          @mousemove="onMouseMove"
          @mouseleave="onMouseLeave"
        ></canvas>
      </div>
    </div>

    <div
      v-if="tip.visible"
      class="tip"
      :style="{ left: tip.x + 'px', top: tip.y + 'px' }"
    >
      <div class="tip-title">{{ tip.label }}</div>
      <div class="tip-row">耗时 {{ tip.duration }}</div>
      <div v-if="tip.self" class="tip-row">自身 {{ tip.self }}</div>
      <div v-if="tip.exc" class="tip-row exc">{{ tip.exc }}</div>
    </div>

    <div class="legend">瀑布图：横轴为时间，每行一个 Span，条宽 = 耗时</div>
    <div v-if="isEmpty" class="empty">暂无数据，点击「加载真实数据」或点击「加载示例数据」</div>
  </div>
</template>

<script setup>
import { ref, reactive, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { niceStep } from '../utils/span'
import { formatDuration, formatDurationAxis } from '../utils/duration'

const props = defineProps({
  nodes: { type: Array, default: () => [] },
  active: { type: Boolean, default: true }
})
const emit = defineEmits(['jump'])

const canvasEl = ref(null)
const scrollEl = ref(null)
const isEmpty = ref(true)
const contentW = ref(0)
const contentH = ref(0)
const tip = reactive({ visible: false, x: 0, y: 0, label: '', duration: '', self: '', exc: '' })

/**
 * 布局常量。
 *
 * <h2>左侧标签区为什么是"三段式"</h2>
 * 旧版把「自身耗时」画在 padL-100、「总耗时」画在 padL-46，
 * 而方法名从 x=6 开始按缩进画 —— 长方法名（UserServiceImpl#page）
 * 会直接压到耗时数字上，这也是用户反馈"重叠"的原因。
 *
 * <p>现在改成明确的三列，列宽按内容实测决定：
 * <pre>
 *  │ 方法名（含缩进，超长省略） │ 自身耗时 │ 总耗时 │ 时间轴区
 *  0                          nameW     selfW    durW    padL
 * </pre>
 */
const ROW_H = 30
const PAD_TOP = 46
const HEAD_H = 40           // 固定表头高度（滚动时不动）
const LABEL_FONT = '11px ui-monospace, Consolas, Monaco, monospace'
const DUR_FONT = '11px Consolas, Monaco, monospace'
const MIN_NAME_W = 150
const MAX_NAME_W = 420
const TIME_COL_W = 92       // 自身 / 总耗时两列各占宽度
const GAP = 10
const C_BG = '#1e1f22'

/**
 * 当前布局。只与数据有关，与视口无关，所以按 nodes 引用缓存 ——
 * 拖动滚动条、切换 tab 都不会重算。
 */
let layout = null
let layoutNodes = null

/** 计算布局：先量方法名宽度，再定各列位置。 */
function computeLayout(nodes) {
  if (!nodes.length) return null
  const sorted = nodes.slice().sort((a, b) => a.start - b.start)

  let minT = Infinity
  let maxT = -Infinity
  sorted.forEach((n) => { minT = Math.min(minT, n.start); maxT = Math.max(maxT, n.end) })
  if (minT === Infinity) return null
  if (maxT === minT) maxT = minT + 1

  // 方法名列宽：取实测最宽者，限制在 [MIN, MAX] 之间
  const probe = document.createElement('canvas').getContext('2d')
  probe.font = LABEL_FONT
  let nameW = MIN_NAME_W
  sorted.forEach((n) => {
    const indent = 6 + (n.depth || 0) * 10
    const w = probe.measureText(n.label || '').width + indent + 16
    nameW = Math.max(nameW, Math.min(MAX_NAME_W, w))
  })

  const padL = nameW + TIME_COL_W * 2 + GAP * 3
  const h = PAD_TOP + sorted.length * ROW_H + 24
  return { nameW, padL, minT, maxT, rows: sorted, h, w: padL + 260 }
}

function ensureLayout(nodes) {
  if (layoutNodes === nodes && layout) return layout
  layoutNodes = nodes
  layout = computeLayout(nodes || [])
  return layout
}

let rafId = 0
let timerId = 0

function cancelPendingDraw() {
  if (rafId) { cancelAnimationFrame(rafId); rafId = 0 }
  if (timerId) { clearTimeout(timerId); timerId = 0 }
}

/**
 * 合并重绘请求。
 *
 * <p>rAF 负责滚动时的顺滑刷新；setTimeout 兜底是因为浏览器对
 * 不可见窗口会停止派发动画帧，只靠 rAF 会让画布一直不画。
 */
function scheduleDraw() {
  if (!props.active) return
  if (rafId || timerId) return
  rafId = requestAnimationFrame(() => {
    rafId = 0
    if (timerId) { clearTimeout(timerId); timerId = 0 }
    draw()
  })
  timerId = setTimeout(() => {
    timerId = 0
    if (rafId) { cancelAnimationFrame(rafId); rafId = 0 }
    draw()
  }, 150)
}

function draw() {
  const canvas = canvasEl.value
  const scroll = scrollEl.value
  if (!canvas || !scroll) return
  const nodes = props.nodes || []
  isEmpty.value = nodes.length === 0
  const viewW = scroll.clientWidth
  const viewH = scroll.clientHeight
  // 面板隐藏时量不到尺寸，等可见后由 ResizeObserver 再画
  if (!viewW || !viewH) return

  const dpr = window.devicePixelRatio || 1
  const pxW = Math.floor(viewW * dpr)
  const pxH = Math.floor(viewH * dpr)
  if (canvas.width !== pxW || canvas.height !== pxH) {
    canvas.width = pxW
    canvas.height = pxH
    canvas.style.width = viewW + 'px'
    canvas.style.height = viewH + 'px'
  }
  const ctx = canvas.getContext('2d')
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.clearRect(0, 0, viewW, viewH)
  if (isEmpty.value) {
    // 空数据时把滚动内容归零，否则会残留上一条 trace 的滚动条
    if (contentW.value) contentW.value = 0
    if (contentH.value) contentH.value = 0
    return
  }

  const L = ensureLayout(nodes)
  if (!L) return

  // 画布按视口，但时间轴要铺满内容宽度
  const W = Math.max(L.padL + 260, viewW)
  if (contentW.value !== W) contentW.value = W
  if (contentH.value !== L.h) contentH.value = L.h

  const sx = scroll.scrollLeft
  const sy = scroll.scrollTop
  const plotW = Math.max(60, W - L.padL - 40)
  const scale = plotW / (L.maxT - L.minT)
  const x = (t) => L.padL + (t - L.minT) * scale

  // ---- 内容层：按滚动量平移，只画可视行 ----
  ctx.save()
  ctx.translate(-sx, -sy)

  // 时间轴刻度线通高，滚动时也能对齐
  const step = niceStep((L.maxT - L.minT) / 6)
  for (let t = Math.ceil(L.minT / step) * step; t <= L.maxT; t += step) {
    const tx = x(t)
    ctx.strokeStyle = '#3c3f41'
    ctx.lineWidth = 1
    ctx.beginPath()
    ctx.moveTo(tx, PAD_TOP - 6)
    ctx.lineTo(tx, L.h - 10)
    ctx.stroke()
  }
  // 标签区与时间轴区的分隔线
  ctx.strokeStyle = '#3c3f41'
  ctx.beginPath()
  ctx.moveTo(L.padL - GAP / 2, PAD_TOP - 26)
  ctx.lineTo(L.padL - GAP / 2, L.h - 10)
  ctx.stroke()

  // 行（仅可视窗口）
  const i0 = Math.max(0, Math.floor((sy - PAD_TOP) / ROW_H))
  const i1 = Math.min(L.rows.length - 1, Math.ceil((sy + viewH - PAD_TOP) / ROW_H))
  for (let i = i0; i <= i1; i++) {
    const n = L.rows[i]
    const y = PAD_TOP + i * ROW_H

    // 行底色（斑马纹，长列表更好跟行）
    if (i % 2 === 1) {
      ctx.fillStyle = 'rgba(255,255,255,0.02)'
      ctx.fillRect(0, y, W, ROW_H)
    }

    const x0 = x(n.start)
    const x1 = x(n.end)
    const selfMs = n.selfMs != null ? n.selfMs : (n.durationMs || 0)
    const color = n.exception ? '#e06c6c'
      : (selfMs > 300 ? '#e06c6c' : (selfMs > 100 ? '#e0a050' : '#4caf50'))

    // 条形
    ctx.fillStyle = 'rgba(255,255,255,0.06)'
    ctx.fillRect(x0, y + 6, Math.max(2, x1 - x0), ROW_H - 12)
    ctx.fillStyle = color
    ctx.fillRect(x0, y + 6, Math.max(2, x1 - x0), 3)

    // 方法名：按层级缩进，超宽省略
    const indent = 6 + (n.depth || 0) * 10
    ctx.font = LABEL_FONT
    ctx.fillStyle = '#c8c8c8'
    const maxNameW = L.nameW - indent - 4
    ctx.textAlign = 'left'
    ctx.fillText(clip(ctx, n.label || '', maxNameW), indent, y + ROW_H / 2)

    // 自身耗时列（右对齐，暗色）
    ctx.font = DUR_FONT
    ctx.textAlign = 'right'
    if (n.selfMs != null && n.childCount > 0 && n.selfMs > 0) {
      ctx.fillStyle = '#8a8a8a'
      ctx.fillText(formatDuration(n.selfMs), L.nameW + TIME_COL_W, y + ROW_H / 2)
    } else {
      ctx.fillStyle = '#4a4a4a'
      ctx.fillText('-', L.nameW + TIME_COL_W, y + ROW_H / 2)
    }
    // 总耗时列
    ctx.fillStyle = color
    ctx.fillText(formatDuration(n.durationMs), L.nameW + TIME_COL_W * 2 + GAP, y + ROW_H / 2)
    ctx.textAlign = 'left'

    // 异常：贴在条形右侧
    if (n.exception) {
      ctx.fillStyle = '#e06c6c'
      ctx.font = '10px sans-serif'
      ctx.fillText('⚠ ' + clip(ctx, n.exception, 40), Math.max(x1 + 6, L.padL), y + ROW_H / 2)
    }
  }

  ctx.restore()

  // ---- 固定表头：列名 + 时间刻度，滚动时始终可见 ----
  drawHeader(ctx, L, viewW, sx, x)
}

/** 顶部固定表头（列名与时间刻度）。 */
function drawHeader(ctx, L, viewW, sx, x) {
  ctx.fillStyle = C_BG
  ctx.fillRect(0, 0, viewW, HEAD_H)
  ctx.strokeStyle = '#3c3f41'
  ctx.lineWidth = 1
  ctx.beginPath()
  ctx.moveTo(0, HEAD_H - 0.5)
  ctx.lineTo(viewW, HEAD_H - 0.5)
  ctx.stroke()

  ctx.font = DUR_FONT
  ctx.textBaseline = 'middle'
  ctx.fillStyle = '#8a8a8a'
  // 三个列名跟着横向滚动一起平移，保证和下面各行的方法名对齐
  ctx.textAlign = 'left'
  ctx.fillText('方法', 6 - sx, HEAD_H - 14)
  ctx.textAlign = 'right'
  ctx.fillText('自身', L.nameW + TIME_COL_W - sx, HEAD_H - 14)
  ctx.fillText('总耗时', L.nameW + TIME_COL_W * 2 + GAP - sx, HEAD_H - 14)
  ctx.textAlign = 'left'

  // 时间刻度：位置随横向滚动平移，数值按内容坐标算好
  const step = niceStep((L.maxT - L.minT) / 6)
  ctx.font = '10px sans-serif'
  ctx.fillStyle = '#6a6a6a'
  for (let t = Math.ceil(L.minT / step) * step; t <= L.maxT; t += step) {
    const tx = x(t) - sx
    if (tx < 0 || tx > viewW) continue
    ctx.fillText(formatDurationAxis(t - L.minT), tx + 2, HEAD_H - 14)
  }
}

/** 按宽度截断文字，超出加省略号。 */
function clip(ctx, text, maxW) {
  if (!text) return ''
  if (ctx.measureText(text).width <= maxW) return text
  let s = text
  while (s.length > 1 && ctx.measureText(s + '…').width > maxW) {
    s = s.slice(0, -1)
  }
  return s + '…'
}

/** 命中测试：把鼠标位置换算成内容坐标里的行。 */
function hitTest(ev) {
  if (!layout || !layout.rows || !layout.rows.length) return -1
  const rect = canvasEl.value.getBoundingClientRect()
  const yView = ev.clientY - rect.top
  // 固定表头不参与命中
  if (yView < HEAD_H) return -1
  const yContent = yView + (scrollEl.value ? scrollEl.value.scrollTop : 0)
  const i = Math.floor((yContent - PAD_TOP) / ROW_H)
  if (i < 0 || i >= layout.rows.length) return -1
  return i
}

function onCanvasClick(ev) {
  if (!props.active) return
  const i = hitTest(ev)
  if (i < 0) return
  emit('jump', layout.rows[i])
}

function onMouseMove(ev) {
  if (!props.active) return
  const i = hitTest(ev)
  if (i < 0) { tip.visible = false; return }
  const n = layout.rows[i]
  const wrap = canvasEl.value?.closest('.panel-wrap')
  const r = wrap ? wrap.getBoundingClientRect() : { left: 0, top: 0 }
  tip.visible = true
  tip.x = ev.clientX - r.left + 14
  tip.y = ev.clientY - r.top + 12
  tip.label = n.label || ''
  tip.duration = formatDuration(n.durationMs)
  tip.self = n.selfMs != null && n.childCount > 0 ? formatDuration(n.selfMs) : ''
  tip.exc = n.exception || ''
}

function onMouseLeave() { tip.visible = false }

/** 滚动时隐藏 tooltip 并重画可视窗口。 */
function onScroll() {
  tip.visible = false
  scheduleDraw()
}

let ro = null

// 只监听数组引用变化，不做 deep 遍历 —— 几千个节点上 deep watch 本身就很贵
watch(() => props.nodes, () => { scheduleDraw() })
watch(() => props.active, (v) => { if (v) nextTick(scheduleDraw) })

onMounted(() => {
  if (typeof ResizeObserver !== 'undefined' && scrollEl.value) {
    ro = new ResizeObserver(() => scheduleDraw())
    ro.observe(scrollEl.value)
  }
  nextTick(scheduleDraw)
})

onBeforeUnmount(() => {
  if (ro) { ro.disconnect(); ro = null }
  cancelPendingDraw()
})
</script>

<style scoped>
.panel-wrap { position: relative; height: 100%; overflow: hidden; }

/* overflow:auto —— 内容超宽出横向滚动条，超高出纵向滚动条 */
.wf-scroll {
  width: 100%;
  height: 100%;
  overflow: auto;
  position: relative;
}
.wf-inner { position: relative; min-width: 100%; min-height: 100%; }
/* 画布钉在滚动视口左上角，永远只有一屏大小 */
.wf-canvas { position: sticky; top: 0; left: 0; display: block; }

.empty {
  position: absolute; top: 40%; width: 100%; text-align: center;
  color: #666; font-size: 14px;
}
.legend {
  position: absolute; top: 8px; right: 12px; font-size: 11px;
  color: #8a8a8a; background: rgba(0, 0, 0, .45); padding: 4px 8px;
  border-radius: 4px; pointer-events: none;
}
.tip {
  position: absolute; z-index: 10;
  background: #2b2d30; border: 1px solid #4d6a8f; border-radius: 4px;
  padding: 6px 9px; font-size: 11px; color: #c8c8c8;
  pointer-events: none; box-shadow: 0 3px 10px rgba(0, 0, 0, .45);
  max-width: 440px;
}
.tip-title {
  font-family: Consolas, Monaco, monospace; color: #6cb6ff;
  margin-bottom: 3px; word-break: break-all;
}
.tip-row { color: #9a9a9a; }
.tip-row.exc { color: #e06c6c; }
</style>
