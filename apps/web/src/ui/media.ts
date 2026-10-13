import { useSyncExternalStore } from 'react'

/** Phones get one pane at a time. Keep in step with the CSS breakpoints (max-width: 759px). */
export const PHONE = '(max-width: 759px)'

export function useMediaQuery(query: string): boolean {
  return useSyncExternalStore(
    (onChange) => {
      const list = window.matchMedia(query)
      list.addEventListener('change', onChange)
      return () => list.removeEventListener('change', onChange)
    },
    () => window.matchMedia(query).matches,
  )
}
