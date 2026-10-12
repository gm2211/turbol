/// <reference lib="webworker" />
/**
 * Prepares a frame's turbulence volume (downloaded by prefetchVolume) off the main thread, so opening 3D never freezes the page.
 * Cells are classified, stacked into same-category columns (see mergeColumns in scene3d.ts) and sent back nearest
 * to the camera first, in a few batches of transferable typed arrays, so the scene fills in from the middle out.
 */
import type { ColumnBatch, VolumeMessage, VolumeRequest } from '@/components/turbulence/volumeTypes'
import { decodeVolume } from '@/components/turbulence/volumeCodec'
import { build } from '@/components/turbulence/volumeBuild'

const post = (m: VolumeMessage, transfer: Transferable[] = []) => self.postMessage(m, transfer)

self.onmessage = (e: MessageEvent<VolumeRequest>) => {
  try {
    const v = decodeVolume(e.data.volume)
    const { cols, byDistance } = build(v, e.data.thresholds, e.data.center)
    const total = byDistance.length
    post({ type: 'meta', cellKm: v.cellKm, total })
    // A small first batch paints quickly; the rest follows in one, as each batch makes the scene rebuild every column.
    let start = 0
    let size = 8000
    while (start < total) {
      const idx = byDistance.subarray(start, Math.min(start + size, total))
      const pick = <T extends Float32Array | Uint8Array>(
        src: ArrayLike<number>,
        Out: new (n: number) => T
      ): T => {
        const out = new Out(idx.length)
        for (let j = 0; j < idx.length; j++) out[j] = src[idx[j]!]!
        return out
      }
      const batch: ColumnBatch = {
        lat: pick(cols.lat, Float32Array),
        lon: pick(cols.lon, Float32Array),
        bottomFt: pick(cols.bottomFt, Float32Array),
        topFt: pick(cols.topFt, Float32Array),
        levelFt: pick(cols.levelFt, Float32Array),
        topLevelFt: pick(cols.topLevelFt, Float32Array),
        edr: pick(cols.edr, Float32Array),
        category: pick(cols.category, Uint8Array),
        sizeKm: pick(cols.sizeKm, Float32Array)
      }
      post(
        { type: 'batch', batch },
        Object.values(batch).map((a) => a.buffer)
      )
      start += size
      size = total
    }
    post({ type: 'done' })
  } catch (err) {
    post({ type: 'error', message: String(err) })
  }
}
