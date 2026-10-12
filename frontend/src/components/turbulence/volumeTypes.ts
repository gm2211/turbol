// Messages between the 3D scene and its volume worker (kept apart so importing them doesn't start a worker).
import type { Thresholds } from '@/api/turbulence'

export interface VolumeRequest {
  url: string
  thresholds: Thresholds
  /** [lon, lat] the columns are ordered from. */
  center: [number, number]
}

/** Struct-of-arrays columns, index-aligned. Category is an index into columnCategories. */
export interface ColumnBatch {
  lat: Float32Array
  lon: Float32Array
  bottomFt: Float32Array
  topFt: Float32Array
  levelFt: Float32Array
  topLevelFt: Float32Array
  edr: Float32Array
  category: Uint8Array
  /** Block width: GTG's fine blocks, or the coarse global layer's outside GTG. */
  sizeKm: Float32Array
}

export type VolumeMessage =
  | { type: 'progress'; bytes: number }
  | { type: 'meta'; cellKm: number; total: number }
  | { type: 'batch'; batch: ColumnBatch }
  | { type: 'done' }
  | { type: 'error'; message: string }

export const columnCategories = ['Smooth', 'Light', 'Moderate', 'Severe', 'Extreme'] as const
