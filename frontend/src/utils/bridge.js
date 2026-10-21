/**
 * 与 IDEA 插件侧 JSBridge 的 cefQuery 通信封装。
 *
 * <h2>日志约定</h2>
 * 所有日志统一带 {@code [CCM]} 前缀，方便在 Chrome DevTools 的 Console
 * 里按前缀过滤（输入 {@code [CCM]} 即可只看插件相关输出）。
 *
 * <h2>调试</h2>
 * IDE 里开启 Registry {@code ide.browser.jcef.debug.port=93333} 后，
 * 插件会把可用的 Chrome 调试地址写到
 * {@code <项目>/.callmap/debug-info.json}，用 Chrome 打开即可看到这些日志。
 */

const PREFIX = '[CCM]'

/** 统一的日志出口：既写 console，也便于将来接 IDE 日志。 */
function log(...args) {
  console.log(PREFIX, ...args)
}

function logWarn(...args) {
  console.warn(PREFIX, ...args)
}

function logError(...args) {
  console.error(PREFIX, ...args)
}

/** 统计调用次数，排查"请求到底发没发出去"时很有用。 */
let seq = 0

/**
 * 等待通信通道就绪，最多等 timeoutMs。
 *
 * <h2>为什么需要它</h2>
 * {@code window.cefQuery} 由 CEF 在渲染进程启动时创建，而 Vue 应用可能在
 * 它出现之前就发出首批请求（拉会话列表）。直接判断一次就报
 * "通道未就绪"是错的 —— 通道通常只是晚了几十毫秒。
 *
 * 实测在 IDEA 2026.2 上这个时间差比 2025.x 更明显：页面渲染更快，
 * 首屏请求更早，于是更容易抢在 cefQuery 创建之前。
 *
 * 用 50ms 轮询等待，既不会明显拖慢首屏，也不会因为时序抖动而失败。
 */
function waitForChannel(timeoutMs = 3000) {
  return new Promise((resolve) => {
    const probe = () => {
      if (typeof window.cefQuery === 'function') return window.cefQuery
      if (typeof window.codeCallMapQuery === 'function') return window.codeCallMapQuery
      return null
    }

    const found = probe()
    if (found) {
      resolve(found)
      return
    }

    const startedAt = Date.now()
    let tries = 0
    const timer = setInterval(() => {
      tries += 1
      const ch = probe()
      if (ch) {
        clearInterval(timer)
        log('通道就绪，等待了 ' + (Date.now() - startedAt) + 'ms（' + tries + ' 次探测）')
        resolve(ch)
        return
      }
      if (Date.now() - startedAt >= timeoutMs) {
        clearInterval(timer)
        resolve(null)
      }
    }, 50)
  })
}

export function cefCall(request) {
  const id = ++seq
  const startedAt = Date.now()
  log('→ 请求 #' + id, request)

  return new Promise(async (resolve) => {
    // 通道选择：优先 window.cefQuery —— 它由 CefMessageRouter 在页面加载前
    // 就注册好，是 JCEF 原生通道，最可靠、少一层包装。
    // 远端/调试环境若没有它（例如在普通浏览器里打开页面），再退回
    // codeCallMapQuery（注入脚本提供的同语义别名）。
    //
    // 关键：不能只判断一次。首屏请求常常早于 CEF 创建好 window.cefQuery，
    // 单次判断会把"晚几十毫秒"误报成"通道不存在"。
    const channel = await waitForChannel()

    if (!channel) {
      logError('请求 #' + id + ' 失败：等待 3 秒后仍未找到可用的桥接通道。'
        + '当前可用通道=', {
          cefQuery: typeof window.cefQuery,
          codeCallMapQuery: typeof window.codeCallMapQuery,
          bridge: typeof window.codeCallMapBridge,
        })
      resolve({
        ok: false,
        error: '与插件的通信通道未就绪（cefQuery / codeCallMapQuery 都不存在）。'
          + '请确认工具窗口已加载完成，或查看 Console 中的 [CCM] 日志。',
      })
      return
    }
    log('使用通道:', typeof window.cefQuery === 'function'
      ? 'cefQuery' : 'codeCallMapQuery')

    let settled = false
    const finish = (result, isError) => {
      if (settled) {
        // 同一请求被回调两次属于异常，记下来便于发现桥接层的问题
        logWarn('请求 #' + id + ' 重复回调，已忽略后一次')
        return
      }
      settled = true
      const ms = Date.now() - startedAt
      if (isError) {
        logError('← 请求 #' + id + ' 失败 (' + ms + 'ms):', result && result.error)
      } else {
        log('← 请求 #' + id + ' 完成 (' + ms + 'ms):', result)
      }
      resolve(result)
    }

    try {
      channel({
        request: request,
        onSuccess: function (resp) {
          let parsed
          try {
            parsed = JSON.parse(resp)
          } catch (e) {
            // 把原始响应片段打出来 —— 之前踩过"错误串被当成正常响应"的坑，
            // 出现这种情况时这行日志能立刻定位
            logError('请求 #' + id + ' 响应不是合法 JSON:',
              { raw: String(resp).slice(0, 300), error: e.message })
            finish({ ok: false, error: '响应解析失败: ' + e.message }, true)
            return
          }
          finish(parsed, false)
        },
        onFailure: function (err, msg) {
          finish({ ok: false, error: msg || ('错误码 ' + err) }, true)
        },
      })
    } catch (e) {
      logError('请求 #' + id + ' 调用 cefQuery 抛异常:', e)
      finish({ ok: false, error: '调用 cefQuery 异常: ' + e.message }, true)
    }
  })
}

/** 前端启动自检：把桥接可用性一次性打出来，便于快速判断环境问题。 */
export function logBridgeStatus() {
  log('前端已加载', {
    href: location.href,
    cefQuery: typeof window.cefQuery,
    codeCallMapQuery: typeof window.codeCallMapQuery,
    codeCallMapBridge: typeof window.codeCallMapBridge,
  })
}
