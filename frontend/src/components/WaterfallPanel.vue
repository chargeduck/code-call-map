<template>
  <div class="panel-wrap">
    <!-- 双向滚动容器：左侧标签区宽度按最长方法名动态计算，
         超出视口时出现横向滚动条，避免方法名和耗时挤在一起 -->
    <div ref="scrollEl" class="wf-scroll">
      <canvas ref="canvasEl" @click="onCanvasClick" @mousemove="onMouseMove" @mouseleave="onMouseLeave"></canvas>
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
const LABEL_FONT = '11px ui-monospace, Consolas, Monaco, monospace'
const DUR_FONT = '11px Consolas, Monaco, monospace'
const MIN_NAME_W = 150
const MAX_NAME_W = 420
const TIME_COL_W = 92       // 自身 / 总耗时两列各占宽度
const GAP = 10

let layout = { padL: 0, nameW: 0, plotW: 0, scale: 1, minT: 0, maxT: 1, rows: [], w: 0, h: 0 }

/** 计算布局：先量方法名宽度，再定各列位置。 */
function computeLayout(ctx) {
  const nodes = (props.nodes || []).slice().sort((a, b) => a.start - b.start)
  if (!nodes.length) return null

  let minT = Infinity, maxT = -Infinity
  nodes.forEach((n) => { minT = Math.min(minT, n.start); maxT = Math.max(maxT, n.end) })
  if (minT === Infinity) return null
  if (maxT === minT) maxT = minT + 1

  // 方法名列宽：取实测最宽者，限制在 [MIN, MAX] 之间
  ctx.font = LABEL_FONT
  let nameW = MIN_NAME_W
  nodes.forEach((n) => {
    const indent = 6 + (n.depth || 0) * 10
    const w = ctx.measureText(n.label || '').width + indent + 16
    nameW = Math.max(nameW, Math.min(MAX_NAME_W, w))
  })

  const padL = nameW + TIME_COL_W * 2 + GAP * 3
  layout = { nameW, padL, minT, maxT, rows: nodes }
  return layout
}

/** 计算画布尺寸（含滚动），返回绘图上下文。 */
function prepareCanvas() {
  const canvas = canvasEl.value
  const scroll = scrollEl.value
  if (!canvas || !scroll) return null
  const L = layout
  const dpr = window.devicePixelRatio || 1

  // 先归零再量视口，否则 clientWidth 会被上一次的宽画布撑住，永远缩不回来
  canvas.style.width = '0px'
  canvas.style.height = '0px'
  const viewportW = scroll.clientWidth || scroll.parentElement?.clientWidth || 600
  const viewportH = scroll.clientHeight || scroll.parentElement?.clientHeight || 320

  // 时间轴至少给 260px，否则条形会被压得没有意义
  const requiredW = L.padL + 260
  const contentH = PAD_TOP + L.rows.length * ROW_H + 24
  const cssW = Math.max(requiredW, viewportW)
  const cssH = Math.max(contentH, viewportH)

  canvas.width = Math.floor(cssW * dpr)
  canvas.height = Math.floor(cssH * dpr)
  canvas.style.width = cssW + 'px'
  canvas.style.height = cssH + 'px'
  const ctx = canvas.getContext('2d')
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  return { ctx, w: cssW, h: cssH }
}

function draw() {
  if (!props.active) return
  isEmpty.value = (props.nodes || []).length === 0
  const canvas = canvasEl.value
  if (isEmpty.value) {
    if (canvas) canvas.getContext('2d').clearRect(0, 0, canvas.width, canvas.height)
    return
  }
  const probe = document.createElement('canvas').getContext('2d')
  if (!computeLayout(probe)) return
  const p = prepareCanvas()
  if (!p) return
  const { ctx, w: W, h: H } = p
  const L = layout
  const { minT, maxT, padL, nameW } = L
  const plotW = Math.max(60, W - padL - 40)
  const scale = plotW / (maxT - minT)
  layout.plotW = plotW
  layout.scale = scale
  const x = (t) => padL + (t - minT) * scale

  ctx.clearRect(0, 0, W, H)

  // ---------- 表头 ----------
  ctx.font = DUR_FONT
  ctx.textBaseline = 'middle'
  ctx.fillStyle = '#8a8a8a'
  ctx.textAlign = 'left'
  ctx.fillText('方法', 6, PAD_TOP - 16)
  // 两列耗时用右对齐，数字位数不同也能对齐小数点
  ctx.textAlign = 'right'
  ctx.fillText('自身', nameW + TIME_COL_W, PAD_TOP - 16)
  ctx.fillText('总耗时', nameW + TIME_COL_W * 2 + GAP, PAD_TOP - 16)
  ctx.textAlign = 'left'

  // ---------- 时间轴刻度 ----------
  const step = niceStep((maxT - minT) / 6)
  for (let t = Math.ceil(minT / step) * step; t <= maxT; t += step) {
    const tx = x(t)
    ctx.strokeStyle = '#3c3f41'
    ctx.lineWidth = 1
    ctx.beginPath()
    ctx.moveTo(tx, PAD_TOP - 6)
    ctx.lineTo(tx, H - 10)
    ctx.stroke()
    ctx.fillStyle = '#6a6a6a'
    ctx.font = '10px sans-serif'
    ctx.fillText(formatDurationAxis(t - minT), tx + 2, PAD_TOP - 16)
  }

  // 分隔线：把标签区和时间轴区分开，视觉上更清楚
  ctx.strokeStyle = '#3c3f41'
  ctx.beginPath()
  ctx.moveTo(padL - GAP / 2, PAD_TOP - 26)
  ctx.lineTo(padL - GAP / 2, H - 10)
  ctx.stroke()

  // ---------- 每一行 ----------
  L.rows.forEach((n, i) => {
    const y = PAD_TOP + i * ROW_H
    if (y > H - 4) return
    const x0 = x(n.start), x1 = x(n.end)
    const selfMs = n.selfMs != null ? n.selfMs : (n.durationMs || 0)
    const color = n.exception ? '#e06c6c'
      : (selfMs > 300 ? '#e06c6c' : (selfMs > 100 ? '#e0a050' : '#4caf50'))

    // 行底色（斑马纹，长列表更好跟行）
    if (i % 2 === 1) {
      ctx.fillStyle = 'rgba(255,255,255,0.02)'
      ctx.fillRect(0, y, W, ROW_H)
    }

    // 条形
    ctx.fillStyle = 'rgba(255,255,255,0.06)'
    ctx.fillRect(x0, y + 6, Math.max(2, x1 - x0), ROW_H - 12)
    ctx.fillStyle = color
    ctx.fillRect(x0, y + 6, Math.max(2, x1 - x0), 3)

    // 方法名：按层级缩进，超宽省略（列宽已经量过，正常不会截断）
    const indent = 6 + (n.depth || 0) * 10
    ctx.font = LABEL_FONT
    ctx.fillStyle = '#c8c8c8'
    const maxNameW = nameW - indent - 4
    ctx.fillText(clip(ctx, n.label || '', maxNameW), indent, y + ROW_H / 2)

    // 自身耗时列（右对齐，暗色）
    ctx.font = DUR_FONT
    ctx.textAlign = 'right'
    if (n.selfMs != null && n.childCount > 0 && n.selfMs > 0) {
      ctx.fillStyle = '#8a8a8a'
      ctx.fillText(formatDuration(n.selfMs), nameW + TIME_COL_W, y + ROW_H / 2)
    } else {
      ctx.fillStyle = '#4a4a4a'
      ctx.fillText('-', nameW + TIME_COL_W, y + ROW_H / 2)
    }
    // 总耗时列
    ctx.fillStyle = color
    ctx.fillText(formatDuration(n.durationMs), nameW + TIME_COL_W * 2 + GAP, y + ROW_H / 2)
    ctx.textAlign = 'left'

    // 异常：贴在条形右侧
    if (n.exception) {
      ctx.fillStyle = '#e06c6c'
      ctx.font = '10px sans-serif'
      ctx.fillText('⚠ ' + clip(ctx, n.exception, 40), Math.max(x1 + 6, padL), y + ROW_H / 2)
    }
  })
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

function hitTest(ev) {
  if (!layout.rows || !layout.rows.length) return -1
  const rect = canvasEl.value.getBoundingClientRect()
  const y = ev.clientY - rect.top
  const i = Math.floor((y - PAD_TOP) / ROW_H)
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

function onResize() { if (props.active) draw() }

watch(() => [props.nodes, props.active], () => { nextTick(draw) }, { deep: true })

onMounted(() => {
  window.addEventListener('resize', onResize)
  nextTick(draw)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
})
</script>

<style scoped>
.panel-wrap { position: relative; height: 100%; }

/* overflow:auto —— 内容超宽时出横向滚动条，超高时出纵向滚动条 */
.wf-scroll {
  width: 100%;
  height: 100%;
  overflow: auto;
}
canvas { display: block; }

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
