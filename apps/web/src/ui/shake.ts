import { useCallback, useRef } from 'react'

/**
 * Shakes the referenced element once, e.g. on a rejected form. Uses the Web
 * Animations API so it restarts cleanly without remounting (which would drop
 * input focus).
 */
export function useShake<T extends HTMLElement>() {
  const ref = useRef<T>(null)
  const shake = useCallback(() => {
    const el = ref.current
    if (!el || typeof el.animate !== 'function') return
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
    el.animate(
      [
        { transform: 'none' },
        { transform: 'translateX(-6px)' },
        { transform: 'translateX(6px)' },
        { transform: 'none' },
      ],
      { duration: 300, easing: 'ease-in-out' },
    )
  }, [])
  return [ref, shake] as const
}
