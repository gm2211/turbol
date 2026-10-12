import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vuetify from 'vite-plugin-vuetify'
import { appShell } from './build/appShell.ts'

// https://vitejs.dev/config/
export default defineConfig({
  // autoImport bundles only the Vuetify components the app uses (all of them made the main chunk ~1 MB).
  plugins: [
    vue(),
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
