/**
 * 构建后处理：将 js/ 目录下的 JS 文件内联到 index.html 中，然后删除 js/ 目录。
 * 解决 file:/// 协议下跨域脚本错误被浏览器隐藏（Script error.）的问题。
 */
import fs from 'fs'
import path from 'path'
const outDir = 'dist'
const htmlPath = path.join(outDir, 'index.html')
const jsDir = path.join(outDir, 'js')

if (!fs.existsSync(htmlPath)) {
    console.error('[inline-postbuild] index.html not found')
    process.exit(1)
}

// 读取 HTML 原始内容
let html = fs.readFileSync(htmlPath, 'utf-8')
const htmlOrigSize = html.length
console.log(`[inline-postbuild] HTML before: ${htmlOrigSize} bytes`)

// 查找 <script src="./js/..."></script> 标签的位置
const tagStart = html.indexOf('<script src="./js/')
if (tagStart === -1) {
    console.log('[inline-postbuild] No external script found, skipping')
    process.exit(0)
}

// 找到整个 script 标签
const tagEnd = html.indexOf('</script>', tagStart) + '</script>'.length
if (tagEnd < '</script>'.length) {
    console.error('[inline-postbuild] Could not find closing </script>')
    process.exit(1)
}

const tagContent = html.substring(tagStart, tagEnd)
console.log(`[inline-postbuild] Found script tag: ${tagContent.substring(0, 80)}...`)

// 读取所有 JS 文件
let allJs = ''
if (fs.existsSync(jsDir)) {
    const files = fs.readdirSync(jsDir).filter(f => f.endsWith('.js'))
    files.forEach(f => {
        const content = fs.readFileSync(path.join(jsDir, f), 'utf-8')
        console.log(`[inline-postbuild] Read ${f}: ${content.length} bytes`)
        allJs += content + ';\n'
    })
}

console.log(`[inline-postbuild] Total JS: ${allJs.length} bytes`)

// 转义 JS 中的 </script>
const safeJs = allJs.replace(/<\/script>/gi, '<\\/script>')

// 用字符串拼接替代 regex replace，确保精确替换一次
const before = html.substring(0, tagStart)
const after = html.substring(tagEnd)
html = before + '<script>\n' + safeJs + '\n</script>' + after

console.log(`[inline-postbuild] HTML after: ${html.length} bytes`)

// 验证
const residualScripts = (html.match(/src="\.\/js\//g) || []).length
console.log(`[inline-postbuild] Residual src=\"./js/ count: ${residualScripts}`)

fs.writeFileSync(htmlPath, html, 'utf-8')

// 删除 js/ 目录
if (fs.existsSync(jsDir)) {
    fs.rmSync(jsDir, { recursive: true, force: true })
    console.log('[inline-postbuild] Removed js/ directory')
}

console.log(`[inline-postbuild] Done. Final: ${fs.statSync(htmlPath).size} bytes`)
