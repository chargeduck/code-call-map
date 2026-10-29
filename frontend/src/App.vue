<template>
  <div class="callmap-app">
    <div class="toolbar">
      <el-button type="success" size="small" :loading="loading" @click="loadSession">加载真实数据</el-button>
      <el-button size="small" :loading="loading" @click="loadSample">加载示例数据</el-button>
      <el-checkbox v-model="autoRefresh" size="small" class="auto-refresh">自动刷新</el-checkbox>

      <!-- 会话选择：agent 每次启动产生一个录制文件，可切换查看任意一次运行。
           filterable：录制多了以后（一天十几次）靠鼠标翻列表很痛苦，输入时间即可筛。 -->
      <el-select
        v-model="currentSessionId"
        size="small"
        placeholder="-- 选择录制会话 --"
        class="session-select"
        :loading="sessionsLoading"
        filterable
        @change="onSessionChange"
      >
        <el-option
          v-for="s in sessions"
          :key="s.id"
          :value="s.id"
          :label="sessionLabel(s)"
        />
      </el-select>

      <!-- 调用链选择：filterable + 自定义筛选，支持按接口路径/类名/traceId 搜 -->
      <el-select
        v-model="currentTraceId"
        size="small"
        placeholder="-- 选择调用链(trace) --"
        class="trace-select"
        filterable
        :filter-method="filterTrace"
        @change="onTraceChange"
      >
        <el-option
          v-for="tid in filteredTraceIds"
          :key="tid"
          :value="tid"
          :label="traceLabel(tid, traces[tid])"
        />
      </el-select>
      <!-- 方法搜索：与「加载数据」同处一行。
           放在这里是因为它是最常用的入口 —— 排查时往往只记得方法名，
           想立刻知道"这个方法在哪条链里被调了、最慢那次是多少"。
           compact 形态只显示摘要与命中统计，点「查看明细」再进面板。 -->
      <MethodSearchPanel
        :spans="spans"
        :session-id="currentSessionId"
        compact
        :initial-keyword="searchKeyword"
        @hit-change="onSearchHit"
        @open-detail="openSearchDetail"
      />

      <span class="status">{{ status }}</span>
      <!-- 慢调用提示：这些信息以前是打印到应用 Terminal 的，现在只在面板里看。
           给出明确入口，避免用户以为"告警没了 = 功能没了"。 -->
      <el-tooltip
        v-if="slowCount > 0"
        :content="'≥' + formatDuration(SLOW_AFTER_MS) + ' 的调用视为慢调用，详见「耗时热点」页；明细也记录在 .callmap/slow-calls.txt'"
        placement="bottom"
      >
        <span class="slow-badge">慢调用 {{ slowCount }}</span>
      </el-tooltip>
    </div>

    <!-- 七个页签全部常驻（el-tab-pane 默认 lazily=false + v-show）：
         不要改成 v-if / lazy —— 那会让每次切页都重新挂载表格和画布，
         体感就是"切一下卡一下"，而且滚动位置、展开状态都会被重置。
         各画布面板内部自己做视口渲染 + 布局缓存，所以常驻并不费内存。 -->
    <el-tabs v-model="activeTab" class="main-tabs">
      <el-tab-pane label="耗时热点" name="hotspot">
        <HotspotPanel :nodes="currentNodes" @jump="onJump" />
      </el-tab-pane>
      <el-tab-pane label="瀑布图" name="waterfall">
        <WaterfallPanel :nodes="currentNodes" :active="activeTab === 'waterfall'" />
      </el-tab-pane>
      <el-tab-pane label="时序图" name="sequence">
        <SequencePanel :nodes="currentNodes" :trace-id="currentTraceId" :active="activeTab === 'sequence'" @jump="onJump" />
      </el-tab-pane>
      <el-tab-pane label="Span 列表" name="table">
        <SpanTable :nodes="currentNodes" />
      </el-tab-pane>
      <!-- 方法搜索明细：按方法去重汇总，展开每条调用链的完整路径 -->
      <el-tab-pane label="方法搜索明细" name="search">
        <MethodSearchPanel
          :spans="spans"
          :session-id="currentSessionId"
          :initial-keyword="searchKeyword"
          @open-trace="openTrace"
        />
      </el-tab-pane>
      <!-- 调用统计：跨 trace 聚合，回答"反复调用时哪个接口/方法慢" -->
      <el-tab-pane label="调用统计" name="stats">
        <StatsPanel
          :sessions="sessions"
          :current-session-id="currentSessionId"
          :active="activeTab === 'stats'"
        />
      </el-tab-pane>
      <!-- 会话对比：两次录制按方法聚合，找性能回退 -->
      <el-tab-pane label="会话对比" name="diff">
        <DiffPanel :sessions="sessions" :active="activeTab === 'diff'" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { cefCall, logBridgeStatus } from './utils/bridge'
import { buildTraces, buildNodes } from './utils/span'
import { formatDuration } from './utils/duration'
import HotspotPanel from './components/HotspotPanel.vue'
import WaterfallPanel from './components/WaterfallPanel.vue'
import SequencePanel from './components/SequencePanel.vue'
import SpanTable from './components/SpanTable.vue'
import MethodSearchPanel from './components/MethodSearchPanel.vue'
import DiffPanel from './components/DiffPanel.vue'
import StatsPanel from './components/StatsPanel.vue'

const spans = ref([])
const traces = ref({})
/** 按 trace 开始时间升序排列的 id 列表，供下拉稳定展示。 */
const traceIds = ref([])
const currentTraceId = ref('')
const activeTab = ref('hotspot')
const loading = ref(false)
const status = ref('就绪')

const sessions = ref([])
const sessionsLoading = ref(false)
const currentSessionId = ref('')

/** 后端实际扫描的数据目录，用于出错时给出可操作的信息。 */
const lastError = ref('')
const dataDir = ref('')

/**
 * 自动刷新：后端 JSONL 在应用运行期间持续追加写入，
 * 前端若不轮询就永远停留在打开面板那一刻的快照上 ——
 * 表现就是"我明明调了接口，页面上却没有新的调用链"。
 *
 * 这里每 2 秒检查一次「会话列表 + 当前会话文件大小」是否有变化，
 * 有变化才真正重新拉数据，避免无谓的全量 reload。
 */
const AUTO_REFRESH_MS = 2000
const autoRefresh = ref(true)
let timer = null
/** 上一次看到的 (sessionId → sizeBytes) 快照，用于判断"数据有没有变"。 */
let lastFingerprint = ''
/** 上一次用户手动选择的 trace，用于刷新后尽量保持选中项不变。 */
let pinnedTraceId = ''
/** 用户是否手动选过 trace（选过就不再自动跳到请求 trace）。 */
let userPickedTrace = false

/**
 * 顶部方法搜索的关键字。
 *
 * <p>顶部搜索条命中后直接切到"最慢那次"所在的 trace，
 * 让用户输完就能看到图，不必再点一下。
 */
const searchKeyword = ref('')

/** 调用链下拉的筛选关键字（el-select filterable 的自定义筛选）。 */
const traceFilterText = ref('')

/**
 * 调用链下拉的候选列表。
 *
 * <p>不用默认的 label 匹配，而是同时搜 路径/类名/traceId：
 * 用户可能记得 "page" 这个接口名，也可能记得 traceId 的前几位。
 */
const filteredTraceIds = computed(() => {
  const q = (traceFilterText.value || '').trim().toLowerCase()
  const ids = traceIds.value || []
  if (!q) return ids
  return ids.filter((tid) => {
    const label = traceLabel(tid, traces.value[tid]) || ''
    if (label.toLowerCase().includes(q)) return true
    if (String(tid).toLowerCase().includes(q)) return true
    // 再往 span 内容里找：类名/方法名，方便按 "page" 定位接口
    const list = traces.value[tid] || []
    return list.some((s) =>
      (s.method || '').toLowerCase().includes(q) ||
      (s.className || '').toLowerCase().includes(q))
  })
})

/** el-select filterable 的筛选回调。 */
function filterTrace(text) {
  traceFilterText.value = text || ''
}

/** 顶部搜索结果变化：直接定位到最慢那次调用所在的 trace。 */
function onSearchHit(hit) {
  // 搜索词被清空：解除"手动锁定"，恢复自动跟随最新请求 trace 的行为。
  // 不这么做的话，用户搜过一次之后，自动刷新就再也不会切到新请求了。
  if (!hit) {
    userPickedTrace = false
    return
  }
  if (!traces.value[hit.traceId]) return
  currentTraceId.value = hit.traceId
  pinnedTraceId = hit.traceId
  userPickedTrace = true
}

/** 点「查看明细」：把关键字带到明细面板并切过去。 */
function openSearchDetail(kw) {
  searchKeyword.value = kw || ''
  activeTab.value = 'search'
}

const currentNodes = computed(() =>
  currentTraceId.value ? buildNodes(traces.value[currentTraceId.value]) : []
)

/**
 * 慢调用阈值与计数。
 *
 * <p>这个徽标是为了替代「以前打印在 Terminal 里的慢调用告警」——
 * 信息不能在界面上凭空消失，要让用户能看到"有几条慢调用"，
 * 点进「耗时热点」页再细看。
 */
const SLOW_AFTER_MS = 3000
const slowCount = computed(() =>
  (currentNodes.value || []).filter(n => (n.durationMs || 0) >= SLOW_AFTER_MS).length
)

/**
 * 会话显示文案：把 callmap-20261019-140837-123 转成 10-19 14:08:37，
 * 并附上文件大小，方便判断哪次录制有数据。
 */
function sessionLabel(s) {
  const m = /callmap-(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})-/.exec(s.id || '')
  const time = m ? `${m[2]}-${m[3]} ${m[4]}:${m[5]}:${m[6]}` : s.id
  const kb = Math.max(1, Math.round((s.sizeBytes || 0) / 1024))
  return `${time}  (${kb} KB)`
}

/** 拉取会话列表；默认选中最新一次录制。 */
async function refreshSessions() {
  console.log('[CCM] 刷新会话列表...')
  sessionsLoading.value = true
  const resp = await cefCall('listSessions')
  sessionsLoading.value = false
  console.log('[CCM] listSessions 返回:', {
    ok: resp && resp.ok,
    count: resp && resp.sessions ? resp.sessions.length : -1,
    outputDir: resp && resp.outputDir,
    error: resp && resp.error,
  })
  if (!resp || !resp.ok) {
    // 把失败原因带出来：桥接不可用 与 目录为空 是两种完全不同的问题，
    // 混在一起会让用户以为"插件没采集"，实际可能是通信没打通。
    lastError.value = (resp && resp.error) ? resp.error : '会话列表获取失败'
    return
  }
  lastError.value = ''
  sessions.value = resp.sessions || []
  // 记下后端实际扫描的目录，出错时能直接告诉用户去哪里看
  if (resp.outputDir) dataDir.value = resp.outputDir
  if (!currentSessionId.value && sessions.value.length) {
    // 列表已按时间倒序，第一条即最新
    currentSessionId.value = sessions.value[0].id
  }
  // 指纹：所有会话的 id + 大小。任一文件增长就说明有新数据写入。
  lastFingerprint = (sessions.value || [])
    .map(s => (s.id || '') + ':' + (s.sizeBytes || 0))
    .join('|')
}

/**
 * 计算"当前应该展示哪条 trace"。
 *
 * <h2>为什么不能简单取第一条</h2>
 * JSONL 是按「方法结束时间」追加的，而启动方法 {@code main} 耗时十几秒，
 * 它必然落在文件最前面。所以 {@code Object.keys(traces)[0]} 拿到的永远是
 * 启动链 {@code DemoApplication#main}，用户调接口产生的那条排在后面 ——
 * 打开面板看到的是"应用启动过程"，自然以为"我的请求没被采集"。
 *
 * <p>优先顺序：
 * <ol>
 *   <li>用户手动选过 → 保持不变；</li>
 *   <li>有 HTTP 入口（span.path 形如 "GET /api/xxx"）→ 取<b>最近</b>一条；</li>
 *   <li>有线程名像请求线程（http-nio / exec）→ 取最近一条；</li>
 *   <li>兜底取最后一条（最新的）。</li>
 * </ol>
 */
function pickTraceId(built) {
  if (userPickedTrace && pinnedTraceId && built.traces[pinnedTraceId]) {
    return pinnedTraceId
  }
  // 用已按开始时间升序排好的 id 列表，不能依赖 Object.keys 的顺序
  const ids = built.traceIds || Object.keys(built.traces)
  if (!ids.length) return ''
  const isRequest = (arr) => {
    const root = (arr || []).find(s => !s.parentId)
    if (root && root.path) return true
    const th = (arr && arr[0] && arr[0].thread) || ''
    return /http-nio|exec-|worker|qtp/i.test(th)
  }
  // 从后往前找：升序排列，越靠后越新
  for (let i = ids.length - 1; i >= 0; i--) {
    if (isRequest(built.traces[ids[i]])) return ids[i]
  }
  return ids[ids.length - 1]
}

async function loadSpans(request, label) {
  console.log('[CCM] 加载数据:', request, '/', label)
  loading.value = true
  status.value = '加载中...'
  const resp = await cefCall(request)
  loading.value = false
  console.log('[CCM] 数据返回:', {
    ok: resp && resp.ok,
    spanCount: resp && resp.spans ? resp.spans.length : -1,
    session: resp && resp.session,
    error: resp && resp.error,
  })
  if (!resp || !resp.ok) {
    status.value = '加载失败: ' + ((resp && resp.error) || '未知错误')
    return
  }
  spans.value = resp.spans || []
  const built = buildTraces(spans.value)
  traces.value = built.traces
  traceIds.value = built.traceIds || []
  currentTraceId.value = pickTraceId(built)
  pinnedTraceId = currentTraceId.value
  status.value = label + '完成: ' + spans.value.length + ' 条事件, '
    + traceIds.value.length + ' 条调用链'
    + (resp.session ? ', 会话: ' + resp.session : '')
}

/** 加载当前选中会话的数据。 */
function loadSession() {
  if (!currentSessionId.value) {
    status.value = '没有可加载的会话（请先在会话下拉中选择，或用 Run/Debug with CCM 跑一次）'
    return
  }
  loadSpans('loadSpans:' + currentSessionId.value, '会话 ' + currentSessionId.value)
}

function onSessionChange() { loadSession() }

/**
 * 从「方法搜索」跳到指定 trace 的时序图。
 *
 * <p>顺带把 userPickedTrace 置为 true：用户既然主动指定了要看哪条链，
 * 后续自动刷新就不该再把它跳走。
 */
function openTrace(traceId) {
  if (!traceId || !traces.value[traceId]) return
  currentTraceId.value = traceId
  userPickedTrace = true
  pinnedTraceId = traceId
  activeTab.value = 'sequence'
}

function loadSample() { loadSpans('loadSample', '示例数据') }

/**
 * 用户手动切换 trace：记住选择，后续自动刷新不再强行跳回请求 trace。
 */
function onTraceChange() {
  userPickedTrace = true
  pinnedTraceId = currentTraceId.value
}

/**
 * 定时检查是否有新数据；只在「会话文件变大/变多」时才重新加载。
 *
 * <p>不直接每次都 reload 的理由：全量解析 JSONL 在几万条时明显卡顿，
 * 而大部分时候（应用空闲）数据根本没变。用文件大小做指纹足够灵敏 ——
 * 新增 span 必然让文件变长。
 */
async function tickAutoRefresh() {
  if (!autoRefresh.value || loading.value) return
  const before = lastFingerprint
  await refreshSessions()
  if (lastFingerprint !== before) {
    console.log('[CCM] 检测到新数据，自动刷新')
    loadSession()
  }
}

/**
 * trace 下拉的显示文本：优先用接口路径，其次退化为 traceId。
 *
 * 用户的核心诉求是「接口哪里慢」，如果下拉里只有 traceId（t-1-94504...），
 * 根本没法一眼找到「我要看 /api/order/list 那条」。
 * 路径来自 Servlet/DispatcherServlet 入口探针写入的 span.path，
 * 形如 "GET /api/order/list"。
 */
function traceLabel(tid, traceSpans) {
  const root = (traceSpans || []).find(s => !s.parentId)
  const path = root && root.path
  if (path) {
    return `${path}  (${(traceSpans || []).length} 事件)`
  }
  return `${tid}  (${(traceSpans || []).length} 事件)`
}

async function onJump(span) {
  if (!span) return
  // 【关键】优先用 className+method 定位，而不是 sourceFile+lineNumber。
  //
  // 原因：插桩采集的 sourceFile/lineNumber 来自 CallMapRecorder.callerFrame()，
  // 那是在「探针内部」取的栈帧 —— 实测所有记录的 sourceFile 都是
  // SpanBridge.java、行号 82/95，也就是探针自己的代码位置，
  // 跟业务方法毫无关系。以前按它跳转，永远跳到 SpanBridge.java 第 82 行。
  //
  // 而 openMethod 走的是后端 PSI 查找（findMethodLine），
  // 能精确定位到方法声明的真实行号，这才是"跳到这个方法"该有的行为。
  let request = ''
  if (span.className && span.method) {
    // spanId 一起传过去：同一方法在一次 trace 里可能被调用多次，
    // 后端要靠它定位"用户点的到底是第几次"，从而显示那一次的实参/返回值。
    // 不传也能工作（后端退回第一次），但多次调用时就会显示错的那次。
    request = 'openMethod?className=' + encodeURIComponent(span.className) +
      '&method=' + encodeURIComponent(span.method) +
      (span.spanId ? '&spanId=' + encodeURIComponent(span.spanId) : '')
  } else if (span.sourceFile) {
    // 兜底：确实拿不到类名/方法名时才用文件+行号（手工埋点场景）
    request = 'openFile?file=' + encodeURIComponent(span.sourceFile) +
      '&line=' + (span.lineNumber != null ? span.lineNumber : '')
  } else {
    status.value = '该 Span 缺少类名与方法名，无法定位源码'
    return
  }
  status.value = '正在打开 ' + (span.label || span.method) + ' ...'
  const resp = await cefCall(request)
  if (resp && resp.ok) {
    status.value = '已打开 ' + (resp.file || '') + ':' + (resp.line != null ? resp.line : '?')
  } else {
    status.value = '打开失败: ' + ((resp && resp.error) || '未知错误')
  }
}

onMounted(async () => {
  // 先打一条自检日志：桥接是否可用、页面地址是什么
  logBridgeStatus()
  // 先拉会话列表（决定默认加载哪一次），再加载数据
  await refreshSessions()
  if (currentSessionId.value) {
    loadSession()
  } else if (lastError.value) {
    // 通信失败：明确说是桥接问题，别让用户误以为没采集
    status.value = '与插件的通信失败：' + lastError.value
  } else {
    // 通信正常但目录为空：给出实际扫描的路径，便于核对
    status.value = '没有找到录制文件，已扫描目录：' + (dataDir.value || '（未知）')
  }
  // 开始轮询：应用运行期间 JSONL 会持续增长，必须自动跟进来
  timer = setInterval(tickAutoRefresh, AUTO_REFRESH_MS)
})

onUnmounted(() => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
})
</script>

<style scoped>
.callmap-app {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #1e1f22;
  color: #c8c8c8;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #2b2d30;
  border-bottom: 1px solid #3c3f41;
  flex-wrap: wrap;
  flex-shrink: 0;
}
/* 宽度按"同一行放得下"重新分配：
   2 个按钮 + 复选框 + 会话下拉 + trace 下拉 + 搜索框。
   trace 下拉从 300 收到 240、搜索框 260 → 220，
   否则 1920 以下的屏幕会把 status 挤掉行。
   注意：列宽只能靠显式 width，el-select 不会自动收缩。 */
.trace-select { width: 240px; }
.session-select { width: 170px; }
.auto-refresh {
  /* 与深色工具栏对齐，且不抢按钮的视觉权重 */
  --el-checkbox-text-color: #b8b8b8;
  --el-checkbox-checked-text-color: #d0d0d0;
  height: 24px;
  font-size: 12px;
}
/* 慢调用徽标：用琥珀色，与"警告"语义一致但不刺眼 */
.slow-badge {
  font-size: 11px;
  color: #e0a050;
  border: 1px solid #6b5232;
  background: #2f2820;
  border-radius: 9px;
  padding: 1px 7px;
  cursor: help;
  white-space: nowrap;
}
.status {
  margin-left: auto;
  color: #8a8a8a;
  font-size: 11px;
  max-width: 55%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.main-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.main-tabs :deep(.el-tabs__header) {
  margin: 0;
  padding: 0 12px;
  background: #26282b;
  flex-shrink: 0;
}
.main-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  padding: 0;
}
.main-tabs :deep(.el-tab-pane) {
  height: 100%;
}
</style>
