import { useSyncExternalStore } from 'react'

/** Phones get one pane at a time. Keep in step with the CSS breakpoints (max-width: 759px). */
export const PHONE = '(max-width: 759px)'

/** Room for the tags sidebar beside the list and the note. Keep in step with the CSS (min-width: 1100px). */
export const WIDE = '(min-width: 1100px)'

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
