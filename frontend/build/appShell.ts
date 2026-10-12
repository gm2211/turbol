import { createHash } from 'node:crypto'
import { readdirSync, readFileSync } from 'node:fs'
import { join, relative } from 'node:path'
import { transformWithOxc, type Plugin } from 'vite'

/** Fonts and images worth keeping offline; the icon font's woff/ttf/eot copies are for browsers with no woff2. */
const keptAsset = /\.(woff2|png|jpe?g|svg|ico|webp|gif|css)$/

/**
 * Builds the service worker (src/sw.ts, see there) into /sw.js with the app shell it keeps on the phone: index.html,
 * the entry script and everything it imports, each page in src/views and what that imports, the CSS they use, the
 * fonts and images that CSS and script reference, and the files in public/. Extras loaded on demand, such as the 3D
 * view's WebGL stack, stay online-only, so a first visit fetches about 1 MB in the background.
 */
export function appShell(options: { swSource: string; publicDir: string }): Plugin {
  return {
    name: 'turbol-app-shell',
    apply: 'build',
    enforce: 'post',
    async generateBundle(_options, bundle) {
      const outputs = Object.values(bundle)
      const chunks = new Map(
        outputs.flatMap((o) => (o.type === 'chunk' ? [[o.fileName, o] as const] : []))
      )
      const kept = new Set<string>()

      const keepChunk = (fileName: string) => {
        const chunk = chunks.get(fileName)
        if (!chunk || kept.has(fileName)) return
        kept.add(fileName)
        chunk.viteMetadata?.importedCss.forEach((f) => kept.add(f))
        chunk.viteMetadata?.importedAssets.forEach((f) => kept.add(f))
        chunk.imports.forEach(keepChunk)
      }
      for (const chunk of chunks.values()) {
        const page = chunk.facadeModuleId?.includes('/src/views/') ?? false
        if (chunk.isEntry || (chunk.isDynamicEntry && page)) keepChunk(chunk.fileName)
      }

      const indexAsset = bundle['index.html']
      if (indexAsset?.type !== 'asset') throw new Error('appShell: index.html is not in the bundle')
      const indexHtml = String(indexAsset.source)
      // What index.html links (icon, entry script, its CSS and preloads), and what kept CSS points at.
      for (const m of indexHtml.matchAll(/(?:href|src)="\/(assets\/[^"]+)"/g)) kept.add(m[1]!)
      for (const fileName of [...kept].filter((f) => f.endsWith('.css'))) {
        const css = bundle[fileName]
        if (css?.type !== 'asset') continue
        for (const m of String(css.source).matchAll(/url\(\s*['"]?\/(assets\/[^'")?#]+)/g))
          kept.add(m[1]!)
      }

      const publicFiles = listFiles(options.publicDir)
      const files = [
        '/index.html',
        ...[...kept].filter((f) => f.endsWith('.js') || keptAsset.test(f)).map((f) => `/${f}`),
        ...publicFiles.map((f) => `/${f}`)
      ].sort()

      const version = createHash('sha256')
      version.update(indexHtml)
      files.forEach((f) => version.update(f))
      publicFiles.forEach((f) => version.update(readFileSync(join(options.publicDir, f))))

      const { code } = await transformWithOxc(
        readFileSync(options.swSource, 'utf8'),
        options.swSource,
        {
          lang: 'ts'
        }
      )
      const source = code
        .replaceAll('__SHELL_VERSION__', JSON.stringify(version.digest('hex').slice(0, 16)))
        .replaceAll('__SHELL_FILES__', JSON.stringify(files))
        .replaceAll('__SHELL_INDEX_HTML__', JSON.stringify(indexHtml))
      this.emitFile({ type: 'asset', fileName: 'sw.js', source })
    }
  }
}

function listFiles(dir: string): string[] {
  return readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter((e) => e.isFile())
    .map((e) => relative(dir, join(e.parentPath, e.name)).split('\\').join('/'))
}
