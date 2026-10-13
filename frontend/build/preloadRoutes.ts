import type { Plugin } from 'vite'

/**
 * Lazily loaded pages start downloading with the app's own code when the URL opens them, instead of after the app has
 * loaded and asked for them: a round trip (0.4-0.9 s on a cold load) sooner.
 * - 3D links (/map?view=3d, /3d): the 3D code and everything it imports. Other map pages don't fetch it until the
 *   live map is idle (see LiveMapView's warm3d).
 * - Analyze (/analyze, and the old /fly links that redirect there): the page's code and styles.
 */
const routes = [
  {
    module: '/components/turbulence/Map3D.vue',
    when: "/(^|[?&])view=3d/.test(location.search) || location.pathname.startsWith('/3d')"
  },
  {
    module: '/views/AnalyzeView.vue',
    when: '/^\\/(analyze|fly)/.test(location.pathname)'
  }
]

export function preloadRoutes(): Plugin {
  return {
    name: 'turbol-preload-routes',
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
        const scripts = routes.map(({ module, when }) => {
          const entry = [...chunks.values()].find((c) => c.facadeModuleId?.endsWith(module))
          if (!entry) throw new Error(`preloadRoutes: no chunk for ${module}`)
          const js = new Set<string>()
          const css = new Set<string>()
          const add = (fileName: string) => {
            const chunk = chunks.get(fileName)
            if (!chunk || js.has(fileName)) return
            js.add(fileName)
            chunk.viteMetadata?.importedCss.forEach((f) => css.add(f))
            chunk.imports.forEach(add)
          }
          add(entry.fileName)
          const files = JSON.stringify([...js, ...css].map((f) => `/${f}`))
          return (
            `<script>if (${when})` +
            ` ${files}.forEach(function (f) { var l = document.createElement('link');` +
            ` l.rel = f.endsWith('.css') ? 'preload' : 'modulepreload'; if (l.rel === 'preload') l.as = 'style';` +
            ` l.crossOrigin = ''; l.href = f; document.head.appendChild(l) })</script>`
          )
        })
        const script = scripts.join('\n  ')
        // Ahead of the stylesheets: an inline script after a stylesheet that's still loading waits for it, which held
        // these preloads back by up to a second.
        const at = html.indexOf('<script type="module"')
        return at < 0
          ? html.replace('</head>', `${script}\n</head>`)
          : `${html.slice(0, at)}${script}\n  ${html.slice(at)}`
      }
    }
  }
}
