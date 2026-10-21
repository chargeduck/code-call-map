/**
 * 时长格式化：按数量级自动切换到合适的单位。
 *
 * <h2>为什么不能只写死 ms 和 s 两档</h2>
 * 早期实现在 {@code >= 1000} 时就硬切到秒，于是：
 * <ul>
 *   <li>1200ms 显示成 {@code 1.2 s}，和 1000ms 的 {@code 1 s} 看起来差不多，
 *       但实际差了 20%；</li>
 *   <li>超过 60 秒变成 {@code 61.23 s}，读起来很别扭 ——
 *       用户明确要求"按正常时间往上递增"。</li>
 * </ul>
 *
 * <p>规则（与日常读数习惯一致）：
 * <pre>
 *   &lt; 1000ms        → "123 ms"
 *   &lt; 60s           → "1.23 s"
 *   &lt; 60min         → "1m 5s"
 *   &lt; 24h           → "1h 2m"
 *   否则            → "1d 3h"
 * </pre>
 *
 * <p>注意 {@code <} 与 {@code <=} 的边界：1000ms 整显示 {@code 1.00 s} 而不是
 * {@code 1000 ms}，这样从 999ms 到 1000ms 的过渡是连续的。
 */

/** 毫秒阈值 */
const MS = 1
const SECOND = 1000
const MINUTE = 60 * SECOND
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/**
 * 把毫秒数格式化成可读的时长。
 *
 * @param {number|null|undefined} ms 毫秒
 * @returns {string} 形如 "3 ms" / "1.23 s" / "1m 5s" / "2h 3m"
 */
export function formatDuration(ms) {
  if (ms == null || Number.isNaN(ms)) return '-'
  const v = Number(ms)
  if (v < 0) return '-'

  // 小于 1 秒：直接用毫秒，不保留小数（毫秒级小数没有意义）
  if (v < SECOND) {
    return Math.round(v) + ' ms'
  }
  // 1 秒 ~ 1 分钟：保留两位小数，1.00s ~ 59.99s
  if (v < MINUTE) {
    // 边界修正：59999ms 会格式化成 "60.00 s"，这与下一档的 "1m" 冲突，
    // 看起来像"还没到一分钟却显示 60 秒"。四舍五入后等于 60 就进位成分钟。
    const secs = v / SECOND
    const rounded = Number(secs.toFixed(2))
    if (rounded >= 60) {
      return '1m'
    }
    return rounded.toFixed(2) + ' s'
  }
  // 1 分钟以上：拆成 "Xm Ys"，秒取整。这里不用小数秒，
  // 因为分钟级已经不需要亚秒精度，写成 1m 5s 比 1m 5.23s 好读
  if (v < HOUR) {
    const m = Math.floor(v / MINUTE)
    const s = Math.round((v % MINUTE) / SECOND)
    // 边界：59.6s 四舍五入成 60s 时要进位成下一分钟，
    // 否则会出现 "1m 60s" 这种错误显示
    if (s === 60) {
      // 进位后可能正好满一小时（如 3599950ms → 59m + 60s → 60m），
      // 这时要再进位成 "1h"，否则会显示 "60m" —— 明显违反读数习惯
      if (m + 1 >= 60) {
        return '1h'
      }
      return (m + 1) + 'm'
    }
    return s === 0 ? m + 'm' : m + 'm ' + s + 's'
  }
  if (v < DAY) {
    const h = Math.floor(v / HOUR)
    const m = Math.round((v % HOUR) / MINUTE)
    if (m === 60) {
      // 同理：23h + 60m 应进位成 "1d"
      if (h + 1 >= 24) {
        return '1d'
      }
      return (h + 1) + 'h'
    }
    return m === 0 ? h + 'h' : h + 'h ' + m + 'm'
  }
  const d = Math.floor(v / DAY)
  const h = Math.round((v % DAY) / HOUR)
  if (h === 24) {
    return (d + 1) + 'd'
  }
  return h === 0 ? d + 'd' : d + 'd ' + h + 'h'
}

/**
 * 紧凑版：用于空间紧张的列（表格、坐标轴刻度），不带空格。
 *
 * @param {number|null|undefined} ms 毫秒
 * @returns {string} 形如 "3ms" / "1.23s" / "1m5s"
 */
export function formatDurationCompact(ms) {
  return formatDuration(ms).replace(/ /g, '')
}

/** 保留一个极短的单位名，用于图表坐标轴（"1.2s" 这种已经够短）。 */
export function formatDurationAxis(ms) {
  if (ms == null || Number.isNaN(ms)) return ''
  const v = Number(ms)
  if (v === 0) return '0'
  if (v < SECOND) return Math.round(v) + 'ms'
  if (v < MINUTE) return (v / SECOND).toFixed(1) + 's'
  if (v < HOUR) return Math.floor(v / MINUTE) + 'm'
  return Math.floor(v / HOUR) + 'h'
}

export { MS, SECOND, MINUTE, HOUR, DAY }
