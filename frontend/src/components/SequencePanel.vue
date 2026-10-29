<template>
  <div class="seq-wrap">
    <!-- 滚动容器：真实滚动高度由 .seq-inner 撑开，
         画布本身只有「视口大小」并 sticky 在左上角 —— 这样不管调用链有多少层，
         都不会出现一张几万像素高的画布（那样既吃内存，切换 tab 时也必然卡）。 -->
    <div ref="scrollEl" class="seq-scroll" @scroll="onScroll">
      <div class="seq-inner" :style="{ width: contentW + 'px', height: contentH + 'px' }">
        <canvas
          ref="canvasEl"
          class="seq-canvas"
          @click="onCanvasClick"
          @mousemove="onMouseMove"
          @mouseleave="onMouseLeave"
        ></canvas>
      </div>
    </div>

    <!-- 悬浮提示：生命线太密时文字会重叠，靠 tooltip 弥补 -->
    <div
      v-if="tip.visible"
      class="tip"
      :style="{ left: tip.x + 'px', top: tip.y + 'px' }"
    >
      <div class="tip-title">{{ tip.label }}</div>
      <div class="tip-row">{{ tip.kind }}</div>
      <div class="tip-row">耗时 {{ tip.duration }}</div>
      <div v-if="tip.self" class="tip-row">自身 {{ tip.self }}</div>
      <div v-if="tip.caller" class="tip-row caller">调用方 {{ tip.caller }}</div>
    </div>

    <div class="legend">
      时序图：实线＝调用，虚线＝返回，自上而下为时间顺序
      <span class="hint">（可横向 / 纵向滚动）</span>
      <span v-if="traceId" class="trace-id">{{ traceId }}</span>
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
/** 由布局算出的「内容尺寸」，写成响应式是为了让 .seq-inner 撑出正确的滚动条。 */
const contentW = ref(0)
const contentH = ref(0)
const tip = reactive({
  visible: false, x: 0, y: 0, label: '', kind: '', duration: '', self: '', caller: ''
})

/**
 * 布局常量。
 *
 * <h2>为什么参与者宽度要按名字长短算</h2>
 * 固定宽度会让 "UserController" 这种长类名被截断，
 * 而时序图的可读性完全依赖"一眼看出这是谁"。
 *
 * <h2>为什么顶部参与者区要单独留高度</h2>
 * 它会被<b>固定</b>在视口顶部（滚动时不跟着走），
 * 否则滚到第 300 行时已经完全不知道这条竖线属于谁了。
 */
const HEAD_H = 46           // 顶部参与者区高度（滚动时固定）
const BOX_H = 28            // 参与者方框高
const BOX_TOP = 9           // 方框距顶部
const ROW_GAP = 28          // 相邻两条消息的垂直间距
const FIRST_ROW_OFFSET = HEAD_H + 30
const PAD_BOTTOM = 48
const MIN_ACTOR_W = 96      // 参与者最小宽度
const ACTOR_GAP = 26        // 参与者之间的水平间距
const PAD_X = 24
const ARROW_H = 8           // 箭头头部大小
const BAR_W = 8             // 激活条宽度
const LABEL_FONT = '11px ui-monospace, Consolas, Monaco, monospace'
const LABEL_FONT_TIME = '10px ui-monospace, Consolas, Monaco, monospace'

const C_CALL = '#6cb6ff'
const C_WARN = '#e0a050'
const C_BAD = '#e06c6c'
const C_RET = '#8894a3'
const C_LIFELINE = '#454a50'
const C_BG = '#1e1f22'

/**
 * 参与者模型：把每个「类」聚合成一个参与者。
 *
 * <h2>为什么用类名而不是每个 span 一个参与者</h2>
 * 一次请求可能涉及 40 个 span，但类型只有 10 个左右。
 * 按类聚合是 UML 时序图的标准做法，也是唯一能让图保持可读的方式。
 *
 * <h2>为什么必须传「已排序的节点」</h2>
 * 原始数组的顺序是<b>插桩输出顺序</b>，也就是方法<b>返回</b>的顺序：
 * 最内层的 {@code PgStatement} 先写、最外层的 {@code DispatcherServlet} 最后写。
 * 按它建参与者会得到 {@code PgStatement … DispatcherServlet} 的横向排列，
 * 箭头全部从右往左指，看起来整张图是"倒着长"的。
 * 按调用发生顺序（先序遍历）建立参与者，入口 Servlet 才会落在最左边。
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
 * <h2>为什么不能只按 start 排序</h2>
 * 插桩记录的时间戳 {@code ts} 精度只到毫秒。一次请求里大量 span 落在
 * <b>同一毫秒</b>，此时按 start 排序的结果取决于输入数组顺序，完全不稳定，
 * 于是子调用可能排在父调用之前，看起来"顺序反了"。
 *
 * <p>调用关系天然是一棵树：父调用一定在子调用之前发生。所以先序遍历，
 * 同一层次内再按 start 排，既能保证"servlet 在最上面"，
 * 也符合"controller → service → impl"的直觉。
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
 * 生成「消息行」：每次调用占两行 —— 一行进入（调用箭头），一行退出（返回箭头）。
 *
 * <h2>为什么必须有返回箭头</h2>
 * 只有「调用」实线时，一张图只反映"请求怎么一路下去"，
 * 到 SQL 就断了；用户看到的是一条单向下行的折线，
 * 完全看不出"数据是怎么一层层返回来的"。
 * 完整的 UML 时序图里，返回用<b>虚线箭头</b>表示，
 * 与控制流一起构成一个来回，也是判断"哪一层把耗时吃掉了"的关键。
 *
 * <h2>为什么按时间排序而不是直接拼「进入+退出」两块</h2>
 * 如果先把所有进入排完再排所有退出，图会退化成"两段式"，
 * 中间那层嵌套关系全丢了。按时间排序（同一毫秒内：
 * 外层先进入、内层先返回）才能还原真实的交错顺序。
 *
 * @param ordered 先序遍历后的节点
 * @param indexOf 参与者短名 → 下标
 */
function buildRows(ordered, indexOf) {
  const byId = new Map(ordered.map((n) => [n.spanId, n]))
  const actorOf = (n) => {
    if (!n) return -1
    const short = (n.className || n.label || '?').split('.').pop()
    return indexOf.has(short) ? indexOf.get(short) : -1
  }

  const events = []
  ordered.forEach((n) => {
    events.push({ kind: 'call', span: n, t: n.start || 0, depth: n.depth || 0 })
  })
  ordered.forEach((n) => {
    const end = n.end != null ? n.end : (n.start || 0) + (n.durationMs || 0)
    events.push({ kind: 'ret', span: n, t: end, depth: n.depth || 0 })
  })
  events.sort((a, b) => {
    if (a.t !== b.t) return a.t - b.t
    // 同一毫秒：先「进入」后「返回」，否则会出现"还没调用就返回了"
    if (a.kind !== b.kind) return a.kind === 'call' ? -1 : 1
    // 同一毫秒的多次进入：外层先；多次返回：内层先
    if (a.kind === 'ret') return b.depth - a.depth
    return a.depth - b.depth
  })

  return events.map((e) => {
    const parent = e.span.parentId ? byId.get(e.span.parentId) : null
    const from = actorOf(parent)
    const to = actorOf(e.span)
    return {
      kind: e.kind,
      span: e.span,
      from,
      to,
      selfCall: parent ? from === to : false,
      y: 0
    }
  })
}

/** 当前布局（非响应式：每次重算成本不高，但没必要进 Vue 的依赖追踪）。 */
let layout = null
/** 布局对应的 nodes 引用，用于判断"数据没变就别重算"。 */
let layoutNodes = null
/** 文字测量的临时上下文，复用避免每次 new canvas。 */
let probeCtx = null

/**
 * 计算布局：参与者横向排开，消息行纵向排开。
 *
 * <p>布局只跟「数据」有关，与视口大小无关，
 * 所以按 nodes 引用缓存 —— 拖动滚动条、切换 tab 都不会重算。
 */
function computeLayout(nodes) {
  if (!nodes.length) return null
  const ordered = orderByCallSequence(nodes)
  const { actors, indexOf } = buildActors(ordered)
  const rows = buildRows(ordered, indexOf)

  if (!probeCtx) {
    probeCtx = document.createElement('canvas').getContext('2d')
  }
  const widths = actorWidths(probeCtx, actors)

  // 参与者水平坐标：依次排开
  const xs = []
  let cursor = PAD_X
  widths.forEach((w) => {
    xs.push(cursor + w / 2)
    cursor += w + ACTOR_GAP
  })
  const w = Math.max(cursor + PAD_X, 480)

  // 每行一个固定 y；进入/退出成对出现
  rows.forEach((r, i) => { r.y = FIRST_ROW_OFFSET + i * ROW_GAP })
  const h = FIRST_ROW_OFFSET + rows.length * ROW_GAP + PAD_BOTTOM

  // 调用 → 返回的行号，画激活条时要把两端连起来
  const range = new Map()
  rows.forEach((r) => {
    const cur = range.get(r.span.spanId) || {}
    if (r.kind === 'call') cur.callY = r.y
    else cur.retY = r.y
    cur.actor = r.to
    cur.span = r.span
    range.set(r.span.spanId, cur)
  })

  // spanId → 节点：tooltip 里要按 parentId 反查调用方，
  // 用 map 而不是 rows.find，否则每次 mouseover 都是 O(n)
  const byId = new Map(rows.map((r) => [r.span.spanId, r.span]))

  return { actors, widths, xs, rows, w, h, range, byId }
}

function ensureLayout(nodes) {
  if (layoutNodes === nodes && layout) return layout
  layoutNodes = nodes
  layout = computeLayout(nodes)
  return layout
}

// ------------------------------------------------------------------
// 绘制
// ------------------------------------------------------------------

let rafId = 0
let timerId = 0

function cancelPendingDraw() {
  if (rafId) {
    cancelAnimationFrame(rafId)
    rafId = 0
  }
  if (timerId) {
    clearTimeout(timerId)
    timerId = 0
  }
}

/**
 * 合并同一帧内的多次重绘请求（滚动 + 数据变化 + 尺寸变化经常同时发生）。
 *
 * <h2>为什么要加 setTimeout 兜底</h2>
 * 浏览器对<b>不可见窗口</b>（最小化、被完全遮住、工具窗口未激活）会停止派发
 * requestAnimationFrame 帧。只靠 rAF 排队时，数据已经加载完，
 * 画布却一直停在空白状态，直到窗口重新可见才补画 ——
 * 表现就是"切回来是空的，动一下鼠标才出现"。
 * 用一个 150ms 的定时器兜底，保证"该画的一次都不会漏"，
 * 而 rAF 仍负责滚动时的顺滑刷新（定时器会被取消，不会重复画）。
 */
function scheduleDraw() {
  if (!props.active) return
  if (rafId || timerId) return
  rafId = requestAnimationFrame(() => {
    rafId = 0
    if (timerId) {
      clearTimeout(timerId)
      timerId = 0
    }
    draw()
  })
  timerId = setTimeout(() => {
    timerId = 0
    if (rafId) {
      cancelAnimationFrame(rafId)
      rafId = 0
    }
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
  const ctx = canvas.getContext('2d')
  // 面板被 v-show 隐藏时量不到尺寸（clientWidth = 0），
  // 此时不要画 —— 否则会按 0 宽算出错误的画布，等可见后由 ResizeObserver 再画。
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
  // 撑出滚动条（只在变化时赋值，避免无谓的组件更新）
  if (contentW.value !== L.w) contentW.value = L.w
  if (contentH.value !== L.h) contentH.value = L.h

  const sx = scroll.scrollLeft
  const sy = scroll.scrollTop

  // ---- 内容层：整体按滚动量平移，只画可视窗口内的行 ----
  ctx.save()
  ctx.translate(-sx, -sy)

  // 1. 生命线
  const lineBottom = L.h - PAD_BOTTOM + 12
  ctx.strokeStyle = C_LIFELINE
  ctx.lineWidth = 1
  ctx.setLineDash([4, 4])
  L.xs.forEach((x) => {
    ctx.beginPath()
    ctx.moveTo(x, HEAD_H)
    ctx.lineTo(x, lineBottom)
    ctx.stroke()
  })
  ctx.setLineDash([])

  // 2. 激活条：方法"活着"的区间，把进入行和返回行连起来，
  //    这样即使不看箭头也能一眼看出某一层占了多长时间跨度。
  L.range.forEach((r) => {
    if (r.callY == null || r.retY == null || r.retY - r.callY < 2) return
    if (r.retY < sy - ROW_GAP || r.callY > sy + viewH + ROW_GAP) return
    if (r.actor < 0) return
    const x = L.xs[r.actor] - BAR_W / 2
    const y0 = r.callY + 4
    const y1 = r.retY - 4
    const exc = r.span.exception
    ctx.fillStyle = exc ? 'rgba(224, 108, 108, 0.18)' : 'rgba(108, 182, 255, 0.13)'
    ctx.fillRect(x, y0, BAR_W, Math.max(2, y1 - y0))
    ctx.strokeStyle = exc ? 'rgba(224, 108, 108, 0.55)' : 'rgba(108, 182, 255, 0.45)'
    ctx.lineWidth = 1
    ctx.strokeRect(x + 0.5, y0 + 0.5, BAR_W - 1, Math.max(2, y1 - y0) - 1)
  })

  // 3. 消息行：只画可视窗口，避免长链上做几千次 measureText
  const i0 = Math.max(0, Math.floor((sy - ROW_GAP - FIRST_ROW_OFFSET) / ROW_GAP))
  const i1 = Math.min(L.rows.length - 1, Math.ceil((sy + viewH + ROW_GAP - FIRST_ROW_OFFSET) / ROW_GAP))
  for (let i = i0; i <= i1; i++) {
    const r = L.rows[i]
    if (i < 0) continue
    if (r.kind === 'call') drawCall(ctx, L, r)
    else drawReturn(ctx, L, r)
  }

  ctx.restore()

  // ---- 顶部参与者区：固定在视口顶部，滚动时始终能看清每条竖线属于谁 ----
  drawHeader(ctx, L, viewW, sx)
}

/** 顶部参与者方框（固定层）。 */
function drawHeader(ctx, L, viewW, sx) {
  ctx.fillStyle = C_BG
  ctx.fillRect(0, 0, viewW, HEAD_H - 6)
  ctx.strokeStyle = '#3c3f41'
  ctx.lineWidth = 1
  ctx.beginPath()
  ctx.moveTo(0, HEAD_H - 6.5)
  ctx.lineTo(viewW, HEAD_H - 6.5)
  ctx.stroke()

  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  L.actors.forEach((a, i) => {
    const w = L.widths[i]
    const cx = L.xs[i] - sx
    const x = cx - w / 2
    // 完全移出视口的不画
    if (x + w < 0 || x > viewW) return
    ctx.fillStyle = '#2f3b4d'
    ctx.strokeStyle = '#4d6a8f'
    ctx.lineWidth = 1
    roundRect(ctx, x, BOX_TOP, w, BOX_H, 4)
    ctx.fill()
    ctx.stroke()

    ctx.fillStyle = '#dbe4f0'
    ctx.font = 'bold ' + LABEL_FONT
    ctx.fillText(clip(ctx, a.name, w - 14), cx, BOX_TOP + BOX_H / 2)
  })
  ctx.textAlign = 'left'
}

/** 调用箭头（实线，指向被调用方）。 */
function drawCall(ctx, L, r) {
  const span = r.span
  const toX = r.to >= 0 ? L.xs[r.to] : (L.xs[0] || 60)
  const dur = span.durationMs
  const color = span.exception ? C_BAD
    : (dur != null && dur >= 1000 ? C_BAD : (dur != null && dur >= 300 ? C_WARN : C_CALL))

  if (r.selfCall) {
    drawSelfLoop(ctx, L.xs[r.to], r.y, color, false)
    labelAt(ctx, L.xs[r.to] + 38, r.y, span.method || '?', formatDuration(dur), '#d0d0d0')
    return
  }
  // 无调用方（请求入口）：从左侧边缘画进来
  const fromX = r.from >= 0 ? L.xs[r.from] : Math.max(8, toX - 110)
  drawArrowLine(ctx, fromX, toX, r.y, color, false)
  const midX = (fromX + toX) / 2
  labelCentered(ctx, midX, r.y, fromX, toX, span.method || '?', formatDuration(dur), '#d0d0d0')
}

/** 返回箭头（虚线，指回调用方）。 */
function drawReturn(ctx, L, r) {
  const span = r.span
  const fromX = r.to >= 0 ? L.xs[r.to] : (L.xs[0] || 60)
  const color = span.exception ? C_BAD : C_RET

  if (r.selfCall) {
    drawSelfLoop(ctx, L.xs[r.to], r.y, color, true)
    return
  }
  // 根调用没有上层：画成"响应回到左侧"（客户端方向）
  const toX = r.from >= 0 ? L.xs[r.from] : Math.max(8, fromX - 110)
  drawArrowLine(ctx, fromX, toX, r.y, color, true)

  const text = span.exception ? '↩ 异常抛出' : '↩ 返回'
  const wide = Math.abs(toX - fromX) > 96
  labelCentered(ctx, (fromX + toX) / 2, r.y, fromX, toX, wide ? text : '↩', '', color)
}

/** 画一条带箭头的连线。 */
function drawArrowLine(ctx, x0, x1, y, color, dashed) {
  const rightward = x1 >= x0
  const dir = rightward ? 1 : -1
  const xa = x0 + dir * 4
  const xb = x1 - dir * 6

  ctx.strokeStyle = color
  ctx.fillStyle = color
  ctx.lineWidth = dashed ? 1 : 1.2
  if (dashed) ctx.setLineDash([5, 4])
  ctx.beginPath()
  ctx.moveTo(xa, y)
  ctx.lineTo(xb, y)
  ctx.stroke()
  ctx.setLineDash([])

  // 箭头头部（实心三角）
  ctx.beginPath()
  ctx.moveTo(xb, y)
  ctx.lineTo(xb - dir * ARROW_H, y - ARROW_H * 0.5)
  ctx.lineTo(xb - dir * ARROW_H, y + ARROW_H * 0.5)
  ctx.closePath()
  ctx.fill()
}

/** 自调用：生命线上的一个小回环（UML 里表示对象调用自身）。 */
function drawSelfLoop(ctx, x, y, color, dashed) {
  const loopW = 30
  const loopH = 12
  ctx.strokeStyle = color
  ctx.fillStyle = color
  ctx.lineWidth = dashed ? 1 : 1.2
  if (dashed) ctx.setLineDash([5, 4])
  ctx.beginPath()
  ctx.moveTo(x, y - loopH / 2)
  ctx.lineTo(x + loopW, y - loopH / 2)
  ctx.lineTo(x + loopW, y + loopH / 2)
  ctx.lineTo(x + 6, y + loopH / 2)
  ctx.stroke()
  ctx.setLineDash([])
  ctx.beginPath()
  ctx.moveTo(x + 6, y + loopH / 2)
  ctx.lineTo(x + 6 + ARROW_H, y + loopH / 2 - ARROW_H * 0.5)
  ctx.lineTo(x + 6 + ARROW_H, y + loopH / 2 + ARROW_H * 0.5)
  ctx.closePath()
  ctx.fill()
}

/**
 * 在连线中点写标签；箭头太短时退到右侧，避免压住箭头本身。
 *
 * <p>「方法名 + 耗时」是<b>一个整体</b>，判断放得下必须按两者总宽算 ——
 * 只按方法名判断会让 {@code doService (640 ms)} 这种长标签
 * 在中点居中后被推到画布左侧之外（实测第一行标签会被裁掉前半截）。
 */
function labelCentered(ctx, midX, y, xa, xb, name, durText, color) {
  ctx.font = LABEL_FONT
  const tw = ctx.measureText(name).width
  const durW = durText ? ctx.measureText('(' + durText + ')').width + 4 : 0
  const totalW = tw + durW
  const span = Math.abs(xb - xa)
  let x = totalW + 8 <= span ? midX - totalW / 2 : Math.max(xa, xb) + 8
  // 左边界兜底：根调用的箭头从画布边缘起画，居中后可能算出负坐标
  x = Math.max(4, x)
  labelAt(ctx, x, y, name, durText, color)
}

/** 在指定位置画「方法名 + 耗时」。 */
function labelAt(ctx, x, y, name, durText, color) {
  ctx.textAlign = 'left'
  ctx.textBaseline = 'bottom'
  ctx.font = LABEL_FONT
  ctx.fillStyle = color
  ctx.fillText(name, x, y - 2)
  if (durText) {
    const tw = ctx.measureText(name).width
    ctx.font = LABEL_FONT_TIME
    ctx.fillStyle = '#8a8a8a'
    ctx.fillText('(' + durText + ')', x + tw + 4, y - 2)
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

// ------------------------------------------------------------------
// 交互
// ------------------------------------------------------------------

/** 命中测试：根据鼠标 y 找对应的消息行。 */
function hitTest(ev) {
  if (!layout || !layout.rows || !layout.rows.length) return -1
  const canvas = canvasEl.value
  const scroll = scrollEl.value
  const rect = canvas.getBoundingClientRect()
  const yView = ev.clientY - rect.top
  // 顶部固定区不参与命中
  if (yView < HEAD_H) return -1
  const yContent = yView + (scroll ? scroll.scrollTop : 0)
  const i = Math.round((yContent - FIRST_ROW_OFFSET) / ROW_GAP)
  if (i < 0 || i >= layout.rows.length) return -1
  // 与真实行位置偏差过大时不算命中（避免半行也触发）
  if (Math.abs(FIRST_ROW_OFFSET + i * ROW_GAP - yContent) > ROW_GAP * 0.45) return -1
  return i
}

function onCanvasClick(ev) {
  if (!props.active) return
  const i = hitTest(ev)
  if (i < 0) return
  emit('jump', layout.rows[i].span)
}

/** 悬浮提示：生命线密集时，文字标签会互相压住，靠它补全信息。 */
function onMouseMove(ev) {
  if (!props.active) return
  const i = hitTest(ev)
  if (i < 0) {
    tip.visible = false
    return
  }
  const r = layout.rows[i]
  const n = r.span
  const wrap = canvasEl.value?.closest('.seq-wrap')
  const wrapRect = wrap ? wrap.getBoundingClientRect() : { left: 0, top: 0 }
  tip.visible = true
  tip.x = ev.clientX - wrapRect.left + 14
  tip.y = ev.clientY - wrapRect.top + 12
  tip.label = (n.className ? n.className.split('.').pop() + '#' : '') + (n.method || '?')
  tip.kind = r.kind === 'call' ? '调用' : '返回'
  tip.duration = formatDuration(n.durationMs)
  tip.self = n.selfMs != null && n.childCount > 0 ? formatDuration(n.selfMs) : ''
  const parentNode = n.parentId ? layout.byId.get(n.parentId) : null
  tip.caller = parentNode
    ? (parentNode.className ? parentNode.className.split('.').pop() + '#' : '') + (parentNode.method || '?')
    : '请求入口'
}

function onMouseLeave() {
  tip.visible = false
}

/** 滚动时隐藏 tooltip（否则它浮在错误的位置），并重画可视窗口。 */
function onScroll() {
  tip.visible = false
  scheduleDraw()
}

let ro = null

watch(() => props.nodes, () => { scheduleDraw() })
watch(() => props.active, (v) => { if (v) nextTick(scheduleDraw) })

onMounted(() => {
  // ResizeObserver 同时覆盖了三种情况：窗口缩放、tab 从隐藏变可见、
  // 侧边栏拖动改变宽度 —— 比监听 window.resize 更准。
  if (typeof ResizeObserver !== 'undefined' && scrollEl.value) {
    ro = new ResizeObserver(() => scheduleDraw())
    ro.observe(scrollEl.value)
  }
  nextTick(scheduleDraw)
})

onBeforeUnmount(() => {
  if (ro) {
    ro.disconnect()
    ro = null
  }
  cancelPendingDraw()
})
</script>

<style scoped>
.seq-wrap {
  position: relative;
  height: 100%;
  overflow: hidden;
}

/* 双向滚动：宽度随参与者数量增长，高度随消息条数增长，
   两者都由 .seq-inner 撑开，画布只占视口 */
.seq-scroll {
  width: 100%;
  height: 100%;
  overflow: auto;
  position: relative;
}
.seq-inner {
  position: relative;
  min-width: 100%;
  min-height: 100%;
}
/* sticky 让画布钉在滚动视口的左上角：
   滚动条照常工作，但画布始终只有一屏大小 */
.seq-canvas {
  position: sticky;
  top: 0;
  left: 0;
  display: block;
}

.empty {
  position: absolute; top: 40%; width: 100%; text-align: center;
  color: #666; font-size: 14px;
}
.legend {
  position: absolute; top: 8px; right: 12px; font-size: 11px;
  color: #8a8a8a; background: rgba(0, 0, 0, .55); padding: 4px 8px;
  border-radius: 4px; pointer-events: none; max-width: 62%;
}
.legend .hint { color: #666; }
.legend .trace-id {
  display: block; margin-top: 2px; color: #5f6b7a;
  font-family: Consolas, Monaco, monospace; font-size: 10px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}

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
