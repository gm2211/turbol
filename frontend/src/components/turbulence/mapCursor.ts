/**
 * Precision "+" cursor for inspecting the map: four short arms with an open centre and a dot, drawn
 * dark over a white halo so it reads on any basemap or overlay colour. Hotspot is the centre.
 */
const svg =
  '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24">' +
  '<g stroke-linecap="round" fill="none">' +
  '<path d="M12 3v5M12 16v5M3 12h5M16 12h5" stroke="#fff" stroke-width="3.5"/>' +
  '<path d="M12 3v5M12 16v5M3 12h5M16 12h5" stroke="#0f172a" stroke-width="1.5"/>' +
  '</g>' +
  '<circle cx="12" cy="12" r="2" fill="#fff"/>' +
  '<circle cx="12" cy="12" r="1" fill="#0f172a"/>' +
  '</svg>'

export const inspectCursor = `url("data:image/svg+xml,${encodeURIComponent(svg)}") 12 12, crosshair`

/** deck.gl getCursor: grabbing while dragging, pointer over pickable things, otherwise the inspect cursor. */
export function deckCursor({
  isDragging,
  isHovering
}: {
  isDragging: boolean
  isHovering: boolean
}) {
  return isDragging ? 'grabbing' : isHovering ? 'pointer' : inspectCursor
}
