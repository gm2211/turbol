import type { Plugin } from 'vite'

/**
 * For 3D links (/map?view=3d, /3d), index.html starts fetching the 3D code and everything it imports straight away,
 * alongside the app's own code, instead of after the app has loaded and asked for it: about one round trip and the
 * app's start-up sooner. Other pages don't fetch it until the live map is idle (see LiveMapView's warm3d).
 */
export function preload3d(): Plugin {
  return {
    name: 'turbol-preload-3d',
    apply: 'build',
    transformIndexHtml: {
      order: 'post',
      handler(html, ctx) {
        const bundle = ctx.bundle
        if (!bundle) return html
        const chunks = new Map(
          Object.values(bundle).flatMap((o) =>
            o.type === 'chunk' ? [[o.fileName, o] as const] : []
          )
        )
        const map3d = [...chunks.values()].find((c) =>
          c.facadeModuleId?.endsWith('/components/turbulence/Map3D.vue')
        )
        if (!map3d) throw new Error('preload3d: no Map3D chunk')
        const js = new Set<string>()
        const css = new Set<string>()
        const add = (fileName: string) => {
          const chunk = chunks.get(fileName)
          if (!chunk || js.has(fileName)) return
          js.add(fileName)
          chunk.viteMetadata?.importedCss.forEach((f) => css.add(f))
          chunk.imports.forEach(add)
        }
        add(map3d.fileName)
        const files = JSON.stringify([...js, ...css].map((f) => `/${f}`))
        const script =
          `<script>if (/(^|[?&])view=3d/.test(location.search) || location.pathname.startsWith('/3d'))` +
          ` ${files}.forEach(function (f) { var l = document.createElement('link');` +
          ` l.rel = f.endsWith('.css') ? 'preload' : 'modulepreload'; if (l.rel === 'preload') l.as = 'style';` +
          ` l.href = f; document.head.appendChild(l) })</script>`
        return html.replace('</head>', `${script}\n</head>`)
      }
    }
  }
}
