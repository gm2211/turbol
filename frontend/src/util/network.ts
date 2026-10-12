/**
 * Whether background prefetching is worth the user's bandwidth. Uses the Network Information API where the browser
 * has it (Chromium): no Data Saver, a 4G-class connection and at least ~2 Mbps. Browsers without it (Safari,
 * Firefox) are assumed fine: timing small map tiles measures latency more than bandwidth, so it would mislead.
 */
interface NetworkInformation extends EventTarget {
  saveData?: boolean
  effectiveType?: 'slow-2g' | '2g' | '3g' | '4g'
  downlink?: number
}

const minMbps = 2

function connection(): NetworkInformation | undefined {
  return (navigator as Navigator & { connection?: NetworkInformation }).connection
}

export function goodConnection(): boolean {
  const c = connection()
  if (c?.saveData) return false
  if (c?.effectiveType && c.effectiveType !== '4g') return false
  if (c?.downlink !== undefined && c.downlink < minMbps) return false
  return true
}

/**
 * Looser than goodConnection, for one-off downloads that make a likely next step instant (the 3D code and volume,
 * ~1 MB together): only Data Saver and 2G rule them out. Chromium rates the connection from observed round trips,
 * so a slow server alone can drop a fast link to "3g".
 */
export function fineForOneOffs(): boolean {
  const c = connection()
  return !c?.saveData && c?.effectiveType !== '2g' && c?.effectiveType !== 'slow-2g'
}

/** Calls `listener` when the connection changes (Chromium only), e.g. to resume prefetching on Wi-Fi. */
export function onConnectionChange(listener: () => void): () => void {
  const c = connection()
  c?.addEventListener('change', listener)
  return () => c?.removeEventListener('change', listener)
}
