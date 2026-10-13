/** '#rrggbb' as [r, g, b]. Kept apart from scene3d so 2D pages that need it don't load deck.gl. */
export function hexRgb(hex: string): [number, number, number] {
  const n = parseInt(hex.slice(1), 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}
