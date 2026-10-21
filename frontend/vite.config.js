import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

const __dirname = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'))
const outDir = 'dist'

export default defineConfig({
  plugins: [
    vue(),
    // 仅生产构建：移除 type="module"/crossorigin，script 移到 body 末尾
    {
      name: 'fix-for-file-protocol',
      apply: 'build',
      enforce: 'post',
      transformIndexHtml(html) {
        html = html.replace(/\s*type="module"/g, '')
                   .replace(/\s+crossorigin(\s*=\s*"[^"]*")?/gi, '')
        const scriptRegex = /<script[^>]*src="[^"]*"[^>]*><\/script>/gi
        const headEndIdx = html.indexOf('</head>')
        let headScripts = ''
        html = html.replace(scriptRegex, (match, offset) => {
          if (offset < headEndIdx) {
            headScripts += '\n  ' + match
            return ''
          }
          return match
        })
        return html.replace('</body>', headScripts + '\n</body>')
      }
    }
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src')
    }
  },
  base: './',
  build: {
    outDir,
    emptyOutDir: true,
    assetsInlineLimit: 0,
    minify: false,          // JCEF DevTools 调试用，保留可读代码
    // IIFE 格式绕过 file:/// 协议的 ES module CORS 限制
    target: 'es2015',
    modulePreload: false,
    rollupOptions: {
      output: {
        format: 'iife',
        inlineDynamicImports: true,
        entryFileNames: 'js/[name].js',
        chunkFileNames: 'js/[name].js',
        assetFileNames: (assetInfo) => {
          if (assetInfo.name?.endsWith('.css')) return 'css/[name].[ext]'
          return 'assets/[name].[ext]'
        }
      }
    }
  },
  server: {
    port: 5175
  }
})
