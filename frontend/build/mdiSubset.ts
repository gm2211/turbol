import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import subsetFont from 'subset-font'
import type { Plugin } from 'vite'

/**
 * The Material Design Icons stylesheet styles all ~7,400 icons (418 KB of CSS, render-blocking) and its font is
 * 403 KB, which a cold load fetched before any icon showed. The app uses about 80. In the build this keeps only the
 * icons named anywhere in the app's source (plus Vuetify's own, from its mdi icon set), subsets the font to their
 * glyphs and inlines it, so icons draw with the stylesheet and cost no request. Dev keeps the full set.
 *
 * Icon names must appear whole in the source (e.g. 'mdi-airplane'); a name built from pieces would be missing.
 */
export function mdiSubset(opts: { srcDirs: string[]; extraFiles: string[] }): Plugin {
  return {
    name: 'turbol-mdi-subset',
    apply: 'build',
    enforce: 'pre',
    async load(id) {
      const path = id.split('?')[0]
      if (!path.endsWith('/@mdi/font/css/materialdesignicons.css')) return
      const used = new Set<string>()
      const scan = (file: string) => {
        for (const m of readFileSync(file, 'utf8').matchAll(/mdi-[a-z0-9]+(?:-[a-z0-9]+)*/g))
          used.add(m[0])
      }
      const walk = (dir: string) => {
        for (const name of readdirSync(dir)) {
          const p = join(dir, name)
          if (statSync(p).isDirectory()) walk(p)
          else if (/\.(vue|ts|js|html)$/.test(name)) scan(p)
        }
      }
      opts.srcDirs.forEach(walk)
      opts.extraFiles.forEach(scan)

      const css = readFileSync(path, 'utf8')
      const rule = /\.(mdi-[a-z0-9-]+)::before \{\s*content: "\\([0-9A-F]+)";\s*\}\n*/g
      const codepoints: number[] = []
      const kept = css.replace(rule, (whole, name: string, hex: string) => {
        if (!used.has(name)) return ''
        codepoints.push(parseInt(hex, 16))
        return whole
      })
      if (codepoints.length === 0) throw new Error('mdiSubset: no icons found in the source')
      const font = readFileSync(join(path, '../../fonts/materialdesignicons-webfont.woff2'))
      const subset = await subsetFont(font, String.fromCodePoint(...codepoints), {
        targetFormat: 'woff2'
      })
      const fontFace = /@font-face \{[^}]*\}/
      if (!fontFace.test(kept)) throw new Error('mdiSubset: no @font-face in the stylesheet')
      return kept.replace(
        fontFace,
        '@font-face {\n  font-family: "Material Design Icons";\n' +
          `  src: url("data:font/woff2;base64,${subset.toString('base64')}") format("woff2");\n` +
          '  font-weight: normal;\n  font-style: normal;\n  font-display: block;\n}'
      )
    }
  }
}
