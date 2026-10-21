/**
 * 构建后处理：将内联后的 dist 产物同步到 IDEA 插件 web 资源目录
 * 用法: node scripts/sync-dist.js
 *
 * 目标目录通过相对路径推导（frontend/ 的父目录即插件根），
 * 避免硬编码绝对路径导致换机器 / 换目录后失效。
 */
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const distDir = path.resolve(__dirname, '../dist')
const pluginRoot = path.resolve(__dirname, '../..')
const targets = [path.join(pluginRoot, 'src', 'main', 'resources', 'web')]

if (!fs.existsSync(path.join(distDir, 'index.html'))) {
  console.error('[sync-dist] dist/index.html 不存在，请先执行 vite build')
  process.exit(1)
}

for (const target of targets) {
  fs.rmSync(target, { recursive: true, force: true })
  fs.mkdirSync(target, { recursive: true })
  fs.cpSync(distDir, target, { recursive: true })
  console.log('[sync-dist] 已同步到:', target)
}
console.log('[sync-dist] Done.')
