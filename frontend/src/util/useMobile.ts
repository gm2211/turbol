import { ref } from 'vue'

// Phones, and tablets held in portrait: narrow screens, or touch-first screens that aren't wide.
const query = '(max-width: 760px), (pointer: coarse) and (max-width: 1024px)'
const mql = typeof window !== 'undefined' ? window.matchMedia(query) : undefined
const mobile = ref(mql?.matches ?? false)
mql?.addEventListener('change', (e) => (mobile.value = e.matches))
document.documentElement.classList.toggle('mobile', mobile.value)
mql?.addEventListener('change', (e) =>
  document.documentElement.classList.toggle('mobile', e.matches)
)

/** True on phone-sized or touch-first screens; follows rotation and window resizes. */
export function useMobile() {
  return mobile
}
