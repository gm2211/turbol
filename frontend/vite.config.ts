import { fileURLToPath, URL } from 'node:url'

import { defineConfig, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'
import vuetify from 'vite-plugin-vuetify'
import { appShell } from './build/appShell.ts'
import { preload3d } from './build/preload3d.ts'

/**
 * luma.gl loads its WebGL device with a dynamic import, which the build turns into a tiny file of its own: one more
 * round trip before 3D can draw. Import it statically instead (it's in the 3D chunk anyway).
 */
const staticWebglDevice: Plugin = {
  name: 'static-webgl-device',
  transform(code, id) {
    if (!id.replaceAll('\\', '/').endsWith('@luma.gl/webgl/dist/adapter/webgl-adapter.js')) return
    const dynamic = 'await import("./webgl-device.js")'
    if (!code.includes(dynamic)) return
    return `import * as staticWebglDevice from "./webgl-device.js";\n${code.replaceAll(dynamic, 'staticWebglDevice')}`
  }
}

// https://vitejs.dev/config/
export default defineConfig({
  // autoImport bundles only the Vuetify components the app uses (all of them made the main chunk ~1 MB).
  plugins: [
    vue(),
    staticWebglDevice,
    preload3d(),
    vuetify({ autoImport: true }),
    // Keeps the app on the phone so it opens with no connection (see src/sw.ts).
    appShell({
      swSource: fileURLToPath(new URL('./src/sw.ts', import.meta.url)),
      publicDir: fileURLToPath(new URL('./public', import.meta.url))
    })
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  }
})
