/// <reference lib="webworker" />
/**
 * Turbol's service worker. It keeps the app shell (index.html and the scripts, styles, fonts and images the app
 * needs to start) on the phone, so the app opens with no connection: in airplane mode, behind a captive portal, or
 * when the plane's Wi-Fi drops. It caches no data; the Fly view keeps its flights in IndexedDB.
 *
 * build/appShell.ts compiles this file to /sw.js and fills in the three constants below for each build.
 */
declare const __SHELL_VERSION__: string
declare const __SHELL_FILES__: string[]
declare const __SHELL_INDEX_HTML__: string

const sw = self as unknown as ServiceWorkerGlobalScope
const shellCache = `turbol-shell-${__SHELL_VERSION__}`
const shellFiles = new Set(__SHELL_FILES__)
/** How long opening the app waits for the network before using the copy on the phone. */
const navigationTimeoutMs = 3000

sw.addEventListener('install', (event) => {
  // A new build takes over as soon as all of it is on the phone; pages already open reload if they miss a file.
  event.waitUntil(install().then(() => sw.skipWaiting()))
})

sw.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      const keys = await caches.keys()
      await Promise.all(
        keys
          .filter((k) => k.startsWith('turbol-shell-') && k !== shellCache)
          .map((k) => caches.delete(k))
      )
      await sw.clients.claim()
    })()
  )
})

sw.addEventListener('fetch', (event) => {
  const request = event.request
  if (request.method !== 'GET') return
  const url = new URL(request.url)
  if (url.origin !== sw.location.origin || url.pathname.startsWith('/api/')) return
  if (request.mode === 'navigate') event.respondWith(navigate(request))
  else if (shellFiles.has(url.pathname)) event.respondWith(fromShell(request))
})

async function install(): Promise<void> {
  const cache = await caches.open(shellCache)
  // index.html ships inside this script, so the page and the files it names always come from the same build.
  await cache.put(
    '/index.html',
    new Response(__SHELL_INDEX_HTML__, { headers: { 'Content-Type': 'text/html; charset=utf-8' } })
  )
  const kept = new Set((await cache.keys()).map((r) => new URL(r.url).pathname))
  await Promise.all(
    [...shellFiles]
      .filter((path) => !kept.has(path))
      .map(async (path) => {
        // Fingerprinted files never change: reuse an older shell's copy rather than download it again.
        const fingerprinted = path.startsWith('/assets/')
        const response =
          (fingerprinted ? await caches.match(path) : undefined) ??
          (await fetch(path, { cache: fingerprinted ? 'default' : 'no-cache' }))
        // The server answers unknown paths with index.html, which must never stand in for a script or a font.
        const html = response.headers.get('Content-Type')?.includes('text/html') ?? false
        if (!response.ok || html)
          throw new Error(`Couldn't keep ${path} for offline use (${response.status})`)
        await cache.put(path, response)
      })
  )
}

/**
 * Opening the app asks the network first, so a deploy shows up on the next reload, but gives up after a few seconds
 * and opens the copy on the phone: on a dead or crawling connection the app opens anyway.
 */
async function navigate(request: Request): Promise<Response> {
  const network = fetch(request)
  network.catch(() => undefined) // handled below; this only keeps a late failure out of the console
  try {
    const response = await Promise.race([network, delay(navigationTimeoutMs)])
    if (response?.ok) return response
  } catch {
    // Offline or the connection dropped: fall back to the copy on the phone.
  }
  return (await caches.match('/index.html', { cacheName: shellCache })) ?? network
}

async function fromShell(request: Request): Promise<Response> {
  return (
    (await caches.match(request, { cacheName: shellCache, ignoreVary: true })) ?? fetch(request)
  )
}

function delay(ms: number): Promise<undefined> {
  return new Promise((resolve) => setTimeout(() => resolve(undefined), ms))
}
