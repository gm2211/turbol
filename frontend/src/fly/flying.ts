/**
 * Tells the service worker (src/sw.ts) a saved flight is in the air, until when: it then keeps the build on the
 * phone as it is instead of downloading a new one over the plane's Wi-Fi.
 */
const stateCache = 'turbol-state'
const flyingKey = '/__turbol/flying'

export async function setFlying(until: number | undefined): Promise<void> {
  if (typeof caches === 'undefined') return
  try {
    const cache = await caches.open(stateCache)
    if (until === undefined) await cache.delete(flyingKey)
    else
      await cache.put(
        flyingKey,
        new Response(JSON.stringify({ until }), { headers: { 'Content-Type': 'application/json' } })
      )
  } catch {
    // Storage blocked (private mode): updates just aren't held back.
  }
}
