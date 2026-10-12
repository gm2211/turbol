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
// the service worker already has. Only once a minute, so a file that can't load offline doesn't loop.
window.addEventListener('vite:preloadError', (event) => {
  const key = 'turbol.reloadedForNewBuild'
  try {
    const last = Number(sessionStorage.getItem(key) ?? 0)
    if (Date.now() - last < 60_000) return
    sessionStorage.setItem(key, String(Date.now()))
  } catch {
    return
  }
  event.preventDefault()
  window.location.reload()
})

if (import.meta.env.DEV) {
  axios.defaults.baseURL = 'http://localhost:8081'
}
