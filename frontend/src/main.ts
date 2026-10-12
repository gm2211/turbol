// Opening the map in 3D: fetch the 3D code alongside the app's rather than after it has started.
if (/(^|[?&])view=3d/.test(location.search) || location.pathname.startsWith('/3d'))
  import('@/components/turbulence/Map3D.vue').catch(() => undefined)

import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import { downloads } from './fly/downloads'

// Vuetify
import 'vuetify/styles'
import { createVuetify } from 'vuetify'
import { aliases, mdi } from 'vuetify/iconsets/mdi'
import '@mdi/font/css/materialdesignicons.css'
import './assets/style/base.css'
// Uncomment when local dev
import axios from 'axios'

const vuetify = createVuetify({
  theme: {
    defaultTheme: 'light'
  },
  icons: {
    defaultSet: 'mdi',
    aliases,
    sets: {
      mdi
    }
  }
})

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(vuetify)

app.mount('#app')

// Flights downloaded for offline use stay current from whichever page is open (see src/fly/downloads.ts).
downloads.start()

// The service worker keeps the app on the phone so it opens with no connection (see src/sw.ts).
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker
      .register('/sw.js')
      .catch((e) => console.warn('Offline support unavailable', e))
  })
}

// After a deploy, an open page can ask for a file of the old build that is gone: reload into the new build, which
// the service worker already has. Only when the server really has a new build (a stylesheet that is slow or blocked
// in one browser also lands here, and reloading for it left a blank map mid-load), and once a minute at most, so a
// file that can't load offline doesn't loop.
const currentBuild = document.querySelector<HTMLScriptElement>(
  'script[type="module"][src*="/assets/index-"]'
)?.src
window.addEventListener('vite:preloadError', (event) => {
  // Carry on with the import either way; a module that really is missing still fails where it's used.
  event.preventDefault()
  console.warn('Preload failed', event.payload)
  const key = 'turbol.reloadedForNewBuild'
  newBuildDeployed().then((deployed) => {
    if (!deployed) return
    try {
      const last = Number(sessionStorage.getItem(key) ?? 0)
      if (Date.now() - last < 60_000) return
      sessionStorage.setItem(key, String(Date.now()))
    } catch {
      return
    }
    window.location.reload()
  })
})

/**
 * Whether the server's index.html names a different build from this page's. In Safari, requests made while a new
 * service worker takes over the page fail (preloads and this check alike), so a failed check is asked again a moment
 * later, and only counts as yes when the phone is offline (the case this reload is for).
 */
async function newBuildDeployed(): Promise<boolean> {
  if (!currentBuild) return true
  const name = new URL(currentBuild).pathname
  for (const wait of [0, 2000]) {
    await new Promise((resolve) => setTimeout(resolve, wait))
    try {
      const html = await (await fetch('/map', { cache: 'no-store' })).text()
      return !html.includes(name)
    } catch {
      // Ask again, or decide below.
    }
  }
  return !navigator.onLine
}

if (import.meta.env.DEV) {
  axios.defaults.baseURL = 'http://localhost:8081'
}
