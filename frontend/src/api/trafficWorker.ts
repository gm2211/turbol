// Fetches, parses and crops live traffic off the page's main thread (see fetchTraffic), replying in small batches so
// no single message takes long to unpack on the page.
import { type TrafficWorkerReply, type TrafficWorkerRequest, cropTraffic } from '@/api/traffic'

const batch = 500

self.onmessage = async (e: MessageEvent<TrafficWorkerRequest>) => {
  const { id, url, bounds } = e.data
  const post = (m: TrafficWorkerReply) => self.postMessage(m)
  try {
    const res = await fetch(url)
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const { aircraft, complete } = cropTraffic(await res.json(), bounds)
    for (let i = 0; i < aircraft.length || i === 0; i += batch) {
      const done = i + batch >= aircraft.length
      post({ id, aircraft: aircraft.slice(i, i + batch), done, complete })
    }
  } catch (err) {
    post({ id, error: (err as Error).message })
  }
}
