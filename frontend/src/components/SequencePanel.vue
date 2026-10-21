<template>
  <div class="seq-wrap">
    <!-- 画布滚动容器：时序图的宽度由参与者数量决定，
         参与者多时必须能横向滚动，否则生命线会被挤成一条缝 -->
    <div ref="scrollEl" class="seq-scroll" @scroll="onScroll">
      <canvas
        ref="canvasEl"
        @click="onCanvasClick"
        @mousemove="onMouseMove"
        @mouseleave="onMouseLeave"
      ></canvas>
    </div>

    <!-- 悬浮提示：生命线太密时文字会重叠，靠 tooltip 弥补 -->
    <div
      v-if="tip.visible"
      class="tip"
      :style="{ left: tip.x + 'px', top: tip.y + 'px' }"
    >
      <div class="tip-title">{{ tip.label }}</div>
      <div class="tip-row">耗时 {{ tip.duration }}</div>
      <div v-if="tip.self" class="tip-row">自身 {{ tip.self }}</div>
      <div v-if="tip.caller" class="tip-row caller">调用方 {{ tip.caller }}</div>
    </div>

    <div class="legend">
      时序图：顶部为参与者，竖线为生命线，箭头表示调用、自上而下为时间顺序
      <span class="hint">（可横向 / 纵向滚动）</span>
    </div>
    <div v-if="isEmpty" class="empty">暂无数据，点击「加载真实数据」或「加载示例数据」</div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onBeforeUnmount, nextTick, reactive } from 'vue'
import { formatDuration } from '../utils/duration'

const props = defineProps({
  nodes: { type: Array, default: () => [] },
  traceId: { type: String, default: '' },
  active: { type: Boolean, default: true }
})
const emit = defineEmits(['jump'])

const canvasEl = ref(null)
const scrollEl = ref(null)
const isEmpty = ref(true)
const tip = reactive({ visible: false, x: 0, y: 0, label: '', duration: '', self: '', caller: '' })

/**
 * 布局常量。
 *
 * <h2>为什么参与者宽度要按名字长短算</h2>
 * 固定宽度会让 "UserController" 这种长类名被截断，
 * 而时序图的可读性完全依赖"一眼看出这是谁"。
 */
const PAD_TOP = 64          // 顶部留给参与者方框
const BOX_H = 30            // 参与者方框高
const ARROW_GAP = 30        // 相邻两条消息的垂直间距
const PAD_BOTTOM = 40
const MIN_ACTOR_W = 96      // 参与者最小宽度
const ACTOR_GAP = 26        // 参与者之间的水平间距
const PAD_X = 24
const ARROW_H = 8           // 箭头头部大小
const LABEL_FONT = '11px ui-monospace, Consolas, Monaco, monospace'
const LABEL_FONT_TIME = '10px ui-monospace, Consolas, Monaco, monospace'

/**
 * 参与者模型：把每个「类」聚合成一个参与者。
 *
 * <h2>为什么用类名而不是每个 span 一个参与者</h2>
 * 一次请求可能涉及 40 个 span，但类型只有 10 个左右。
 * 按类聚合是 UML 时序图的标准做法，也是唯一能让图保持可读的方式。
 * 同类内的多次调用表现为同一生命线上的多条自调用 / 来回箭头。
 *
 * <h2>为什么必须传「已排序的节点」（用户反馈"从右到左"的根因）</h2>
 * 早期版本直接遍历 {@code props.nodes} 的原始数组建参与者。
 * 而原始数组的顺序是<b>插桩输出顺序</b> —— 也就是方法<b>返回</b>的顺序：
 * 最内层的 {@code PgStatement} 先写、最外层的 {@code DispatcherServlet} 最后写。
 * 于是参与者从左到右排成 {@code PgStatement … DispatcherServlet}，
 * 调用箭头全部从右往左指，看起来整张图是"倒着长"的。
 *
 * <p>正确做法：按<b>调用发生顺序</b>（先序遍历结果）建立参与者，
 * 这样入口 Servlet 在最左边，箭头统一向右，
 * 符合"从左到右、自上而下"的阅读直觉。
 *
 * @param ordered 已按先序遍历排好的节点数组
 */
function buildActors(ordered) {
  const seen = new Map()
  const actors = []
  ordered.forEach((n) => {
    // 用类名短名作为参与者名；同包下重名的概率极低
    const full = n.className || n.label || '?'
    const short = full.split('.').pop()
    if (!seen.has(short)) {
      seen.set(short, actors.length)
      actors.push({ name: short, fullName: full, index: actors.length })
    }
  })
  return { actors, indexOf: seen }
}

/** 计算参与者方框宽度（按文字宽度取最大值，并挤压到合理区间）。 */
function actorWidths(ctx, actors) {
  ctx.font = 'bold ' + LABEL_FONT
  return actors.map((a) => {
    const w = ctx.measureText(a.name).width + 24
    return Math.max(MIN_ACTOR_W, Math.min(240, w))
  })
}

/**
 * 计算「调用发生顺序」：对调用树做先序遍历。
 *
 * <h2>为什么不能只按 start 排序（这是用户反馈"顺序反了"的根因）</h2>
 * 插桩记录的时间戳 {@code ts} 精度只到毫秒。一次请求里大量 span 落在
 * <b>同一毫秒</b>（实测：{@code PgStatement.execute} 与
 * {@code PgPreparedStatement.execute} 都是 +11503ms），
 * 此时按 start 排序的结果取决于输入数组顺序，完全不稳定，
 * 于是子调用可能排在父调用之前，看起来"顺序反了"。
 *
 * <p>时序图的语义本来就是<b>调用发生的时间先后</b>，而调用关系天然是一棵树：
 * 父调用一定在子调用之前发生。所以正确做法是<b>先序遍历</b>
 * （父 → 第一个子 → 子的子 → … → 第二个子），
 * 同一层次内再按 start 排。这样既能保证"servlet 在最上面"，
 * 也符合"controller → service → impl"的直觉。
 *
 * <p>无父节点的多个根（比如启动过程的 main + 多个 HTTP 请求）
 * 按 start 排序，保证最早发生的在上。
 *
 * @return 按先序遍历排好的节点数组
 */
function orderByCallSequence(nodes) {
  const byId = new Map(nodes.map((n) => [n.spanId, n]))

  // 建父子索引
  const childrenOf = new Map()
  const roots = []
  nodes.forEach((n) => {
    // 父节点不在本集合里 → 视为根（数据被裁剪过时会出现）
    if (n.parentId && byId.has(n.parentId)) {
      if (!childrenOf.has(n.parentId)) childrenOf.set(n.parentId, [])
      childrenOf.get(n.parentId).push(n)
    } else {
      roots.push(n)
    }
  })
  const byStart = (a, b) => (a.start || 0) - (b.start || 0)
  roots.sort(byStart)
  childrenOf.forEach((arr) => arr.sort(byStart))

  // 先序遍历，带环保护
  const ordered = []
  const visited = new Set()
  const walk = (n, depthGuard) => {
    if (!n || visited.has(n.spanId) || depthGuard > 200) return
    visited.add(n.spanId)
    ordered.push(n)
    const kids = childrenOf.get(n.spanId) || []
    kids.forEach((k) => walk(k, depthGuard + 1))
  }
  roots.forEach((r) => walk(r, 0))
  // 兜底：有环或孤立节点没被走到时，按 start 补到末尾，避免丢数据
  nodes.filter((n) => !visited.has(n.spanId)).sort(byStart).forEach((n) => {
    visited.add(n.spanId)
    ordered.push(n)
  })
  return ordered
}

/**
 * 由有序节点 + 参与者索引生成消息列表。
 *
 * <p>消息的 {@code from} 取父节点所属参与者，{@code to} 取自己所属参与者。
 * 因为参与者是按先序遍历顺序编号的，箭头天然指向右侧（或自调用），
 * 不会再出现"整体从右往左"的观感。
 */
function buildMessages(ordered, indexOf) {
  const byId = new Map(ordered.map((n) => [n.spanId, n]))
  const actorOf = (n) => {
    if (!n) return -1
    const short = (n.className || n.label || '?').split('.').pop()
    return indexOf.has(short) ? indexOf.get(short) : -1
  }
  return ordered.map((n) => {
    const parent = n.parentId ? byId.get(n.parentId) : null
    const from = actorOf(parent)
    const to = actorOf(n)
    return {
      span: n,
      to,
      from,
      selfCall: parent && from === to
    }
  })
}

let layout = { actors: [], widths: [], xs: [], messages: [], w: 0, h: 0, rowYs: [] }

function computeLayout() {
  const nodes = props.nodes || []
  if (!nodes.length) return null
  // 【顺序很重要】先算出"调用发生顺序"，参与者也按这个顺序编号 ——
  // 这样入口 Servlet 落在最左边，箭头统一向右。
  // 若用 props.nodes 原始顺序建参与者，会得到从右到左的图（历史 bug）。
  const ordered = orderByCallSequence(nodes)
  const { actors, indexOf } = buildActors(ordered)
  const messages = buildMessages(ordered, indexOf)

  // 先用临时 canvas 量文字宽度（此时还没画，只为了算布局）
  const probe = document.createElement('canvas').getContext('2d')
  const widths = actorWidths(probe, actors)

  // 参与者水平坐标：依次排开
  const xs = []
  let cursor = PAD_X
  widths.forEach((w) => {
    xs.push(cursor + w / 2)
    cursor += w + ACTOR_GAP
  })
  const contentW = Math.max(cursor + PAD_X, 480)

  // 纵向：每条消息一行
  const rowYs = messages.map((_, i) => PAD_TOP + BOX_H + 16 + i * ARROW_GAP)
  const contentH = PAD_TOP + BOX_H + 16 + messages.length * ARROW_GAP + PAD_BOTTOM

  layout = { actors, widths, xs, messages, w: contentW, h: contentH, rowYs }
  return layout
}

/** 计算画布尺寸（含滚动），并返回绘图上下文。 */
function prepareCanvas() {
  const canvas = canvasEl.value
  const scroll = scrollEl.value
  if (!canvas || !scroll) return null
  const L = layout
  const dpr = window.devicePixelRatio || 1
  // 【关键】先重置画布尺寸再量容器。
  //
  // scroll.clientWidth 会被"当前画布宽度"撑大：如果上一次内容很宽
  // （参与者多），画布就是 2000px，容器的 clientWidth 也会是 2000 ——
  // 这时候切到只有 3 个参与者的 trace，内容只要 500px，
  // 但读到的 clientWidth 仍是 2000，于是画布永远缩不回去，
  // 右侧留一大片空白且滚动条不会消失。
  //
  // 先把 canvas 宽高置 0，容器立刻回落到布局分配的宽度（flex 撑满），
  // 这时量到的才是"真正可用的视口宽度"。
  canvas.style.width = '0px'
  canvas.style.height = '0px'
  // 兜底：某些布局上下文里置 0 后 clientWidth 也可能是 0（例如面板尚未完成布局），
  // 这时退回到父容器的测量值，避免画布被算成 0 宽而完全画不出来
  const viewportW = scroll.clientWidth || scroll.parentElement?.clientWidth || 480
  const viewportH = scroll.clientHeight || scroll.parentElement?.clientHeight || 320

  // 画布取「内容尺寸」，容器负责滚动 —— 这就是横向滚动条的来源
  const cssW = Math.max(L.w, viewportW)
  const cssH = Math.max(L.h, viewportH)
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
  computeLayout()
  const p = prepareCanvas()
  if (!p) return
  const { ctx, w: W, h: H } = p
  const L = layout
  ctx.clearRect(0, 0, W, H)

  // ---------- 1. 生命线（先画，让箭头压在上面） ----------
  const lineTop = PAD_TOP + BOX_H
  const lineBottom = H - PAD_BOTTOM + 12
  L.xs.forEach((x, i) => {
    ctx.strokeStyle = '#4a4d51'
    ctx.lineWidth = 1
    ctx.setLineDash([4, 4])
    ctx.beginPath()
    ctx.moveTo(x, lineTop)
    ctx.lineTo(x, lineBottom)
    ctx.stroke()
    ctx.setLineDash([])
  })

  // ---------- 2. 参与者方框 ----------
  L.actors.forEach((a, i) => {
    const w = L.widths[i]
    const x = L.xs[i] - w / 2
    // 背景
    ctx.fillStyle = '#2f3b4d'
    ctx.strokeStyle = '#4d6a8f'
    ctx.lineWidth = 1
    roundRect(ctx, x, PAD_TOP, w, BOX_H, 4)
    ctx.fill()
    ctx.stroke()
    // 名字
    ctx.fillStyle = '#dbe4f0'
    ctx.font = 'bold ' + LABEL_FONT
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    let label = a.name
    // 超宽则截断加省略号
    const maxW = w - 14
    if (ctx.measureText(label).width > maxW) {
      while (label.length > 2 && ctx.measureText(label + '…').width > maxW) {
        label = label.slice(0, -1)
      }
      label += '…'
    }
    ctx.fillText(label, L.xs[i], PAD_TOP + BOX_H / 2)
    ctx.textAlign = 'left'
  })

  // ---------- 3. 消息箭头 ----------
  L.messages.forEach((m, i) => {
    const y = L.rowYs[i]
    if (y > H - 8) return
    const toX = L.xs[m.to]
    const span = m.span
    // 自身调用：在生命线上画一个小回环
    if (m.selfCall) {
      drawSelfMessage(ctx, toX, y, span)
      return
    }
    // 无调用方（请求入口）：从左侧边缘画进来
    const fromX = m.from >= 0 ? L.xs[m.from] : Math.max(8, toX - 90)
    drawArrow(ctx, fromX, toX, y, span)
  })

  // ---------- 4. 底部说明 ----------
  ctx.fillStyle = '#6a6a6a'
  ctx.font = LABEL_FONT_TIME
  ctx.textAlign = 'left'
  ctx.fillText('调用链: ' + props.traceId, 8, 16)
}

/** 画一条带标签的调用箭头。 */
function drawArrow(ctx, x0, x1, y, span) {
  const dur = span.durationMs
  // 颜色按耗时分级：红 > 1s，橙 > 300ms，绿 正常
  const color = span.exception ? '#e06c6c'
    : (dur != null && dur >= 1000 ? '#e06c6c'
      : (dur != null && dur >= 300 ? '#e0a050' : '#6cb6ff'))
  const rightward = x1 >= x0
  const xa = x0 + (rightward ? 6 : -6)
  const xb = x1 - (rightward ? 6 : -6)

  ctx.strokeStyle = color
  ctx.fillStyle = color
  ctx.lineWidth = 1.2
  ctx.beginPath()
  ctx.moveTo(xa, y)
  ctx.lineTo(xb, y)
  ctx.stroke()

  // 箭头头部（实心三角）
  const dir = rightward ? 1 : -1
  ctx.beginPath()
  ctx.moveTo(xb, y)
  ctx.lineTo(xb - dir * ARROW_H, y - ARROW_H * 0.5)
  ctx.lineTo(xb - dir * ARROW_H, y + ARROW_H * 0.5)
  ctx.closePath()
  ctx.fill()

  // 方法名 + 耗时：放在箭头上方
  ctx.fillStyle = '#d0d0d0'
  ctx.font = LABEL_FONT
  ctx.textBaseline = 'bottom'
  const name = span.method || span.label || '?'
  const midX = (xa + xb) / 2
  const text = name
  const tw = ctx.measureText(text).width
  // 箭头太短则把标签放到右侧，避免压在箭头上
  const labelX = tw + 10 > Math.abs(xb - xa)
    ? Math.max(xa, xb) + 8
    : midX - tw / 2
  ctx.textAlign = 'left'
  ctx.fillText(text, labelX, y - 2)

  // 耗时紧跟其后，用暗色区分
  if (dur != null) {
    const durText = formatDuration(dur)
    ctx.fillStyle = '#8a8a8a'
    ctx.font = LABEL_FONT_TIME
    ctx.fillText('(' + durText + ')', labelX + tw + 4, y - 2)
  }
  ctx.textBaseline = 'middle'
}

/** 自调用：生命线上的一个小回环（UML 里表示对象调用自身）。 */
function drawSelfMessage(ctx, x, y, span) {
  const dur = span.durationMs
  const color = span.exception ? '#e06c6c'
    : (dur != null && dur >= 1000 ? '#e06c6c'
      : (dur != null && dur >= 300 ? '#e0a050' : '#6cb6ff'))
  const loopW = 30, loopH = 12
  ctx.strokeStyle = color
  ctx.fillStyle = color
  ctx.lineWidth = 1.2
  ctx.beginPath()
  ctx.moveTo(x, y - loopH / 2)
  ctx.lineTo(x + loopW, y - loopH / 2)
  ctx.lineTo(x + loopW, y + loopH / 2)
  ctx.lineTo(x + 6, y + loopH / 2)
  ctx.stroke()
  // 回头箭头
  ctx.beginPath()
  ctx.moveTo(x + 6, y + loopH / 2)
  ctx.lineTo(x + 6 + ARROW_H, y + loopH / 2 - ARROW_H * 0.5)
  ctx.lineTo(x + 6 + ARROW_H, y + loopH / 2 + ARROW_H * 0.5)
  ctx.closePath()
  ctx.fill()

  ctx.fillStyle = '#d0d0d0'
  ctx.font = LABEL_FONT
  ctx.textBaseline = 'bottom'
  ctx.textAlign = 'left'
  const name = span.method || '?'
  ctx.fillText(name, x + loopW + 8, y - 2)
  if (dur != null) {
    const tw = ctx.measureText(name).width
    ctx.fillStyle = '#8a8a8a'
    ctx.font = LABEL_FONT_TIME
    ctx.fillText('(' + formatDuration(dur) + ')', x + loopW + 8 + tw + 4, y - 2)
  }
  ctx.textBaseline = 'middle'
}

/** 圆角矩形路径。 */
function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.lineTo(x + w - r, y)
  ctx.quadraticCurveTo(x + w, y, x + w, y + r)
  ctx.lineTo(x + w, y + h - r)
  ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h)
  ctx.lineTo(x + r, y + h)
  ctx.quadraticCurveTo(x, y + h, x, y + h - r)
  ctx.lineTo(x, y + r)
  ctx.quadraticCurveTo(x, y, x + r, y)
  ctx.closePath()
}

/** 命中测试：根据鼠标 y 找对应的消息行。 */
function hitTest(ev) {
  if (!layout.messages || !layout.rowYs) return -1
  const rect = canvasEl.value.getBoundingClientRect()
  const y = ev.clientY - rect.top
  const step = ARROW_GAP
  const firstY = layout.rowYs[0]
  if (firstY == null) return -1
  const i = Math.round((y - firstY) / step)
  if (i < 0 || i >= layout.messages.length) return -1
  // 与真实行位置偏差过大时不算命中（避免半行也触发）
  if (Math.abs(layout.rowYs[i] - y) > step * 0.45) return -1
  return i
}

function onCanvasClick(ev) {
  if (!props.active) return
  const i = hitTest(ev)
  if (i < 0) return
  emit('jump', layout.messages[i].span)
}

/** 悬浮提示：生命线密集时，文字标签会互相压住，靠它补全信息。 */
function onMouseMove(ev) {
  if (!props.active) return
  const i = hitTest(ev)
  if (i < 0) {
    tip.visible = false
    return
  }
  const m = layout.messages[i]
  const n = m.span
  // tooltip 定位到 .seq-wrap（外层相对定位容器）的坐标系：
  // 直接取 canvas 的父节点（.seq-scroll）再往上找带 position:relative 的那层，
  // 比硬编码 parentElement.parentElement 更能扛住 DOM 结构调整。
  const wrap = canvasEl.value?.closest('.seq-wrap')
  const wrapRect = wrap ? wrap.getBoundingClientRect() : { left: 0, top: 0 }
  tip.visible = true
  tip.x = ev.clientX - wrapRect.left + 14
  tip.y = ev.clientY - wrapRect.top + 12
  tip.label = (n.className ? n.className.split('.').pop() + '#' : '') + (n.method || '?')
  tip.duration = formatDuration(n.durationMs)
  tip.self = n.selfMs != null && n.childCount > 0 ? formatDuration(n.selfMs) : ''
  tip.caller = n.parentId
    ? (layout.messages.find(x => x.span.spanId === n.parentId)?.span.method || '')
    : '请求入口'
}

function onMouseLeave() {
  tip.visible = false
}

/** 滚动时隐藏 tooltip，否则它会浮在错误的位置。 */
function onScroll() {
  tip.visible = false
}

function onResize() {
  if (props.active) draw()
}

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
.seq-wrap { position: relative; height: 100%; }

/* 双向滚动：时序图的宽度随参与者数量增长，不横滚就必然被压扁。
   overflow:auto 会自动在需要时出现横向 / 纵向滚动条。 */
.seq-scroll {
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
  border-radius: 4px; pointer-events: none; max-width: 60%;
}
.legend .hint { color: #666; }

/* 悬浮提示：跟随鼠标，不拦截事件 */
.tip {
  position: absolute;
  z-index: 10;
  background: #2b2d30;
  border: 1px solid #4d6a8f;
  border-radius: 4px;
  padding: 6px 9px;
  font-size: 11px;
  color: #c8c8c8;
  pointer-events: none;
  box-shadow: 0 3px 10px rgba(0, 0, 0, .45);
  max-width: 420px;
}
.tip-title {
  font-family: Consolas, Monaco, monospace;
  color: #6cb6ff;
  margin-bottom: 3px;
  word-break: break-all;
}
.tip-row { color: #9a9a9a; }
.tip-row.caller { color: #7a7a7a; font-size: 10px; }
</style>
