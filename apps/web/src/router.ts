import { useSyncExternalStore } from 'react'
import { flushSync } from 'react-dom'
import { PHONE } from './ui/media'

export type Route = { name: 'notes' } | { name: 'note'; id: string } | { name: 'settings' } | { name: 'tags' }

const listeners = new Set<() => void>()

function subscribe(listener: () => void) {
  listeners.add(listener)
  window.addEventListener('popstate', listener)
  return () => {
    listeners.delete(listener)
    window.removeEventListener('popstate', listener)
  }
}

export function navigate(path: string, { replace = false } = {}) {
  if (path === window.location.pathname) return
  const go = () => {
    if (replace) window.history.replaceState(null, '', path)
    else window.history.pushState(null, '', path)
    for (const listener of listeners) listener()
  }
  const nav = replace ? null : transitionFor(window.location.pathname, path)
  if (!nav || !document.startViewTransition || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    go()
    return
  }
  const root = document.documentElement
  root.dataset.nav = nav
  // flushSync: the transition snapshots the DOM as soon as the callback returns.
  const transition = document.startViewTransition(() => flushSync(go))
  void transition.finished.finally(() => {
    if (root.dataset.nav === nav) delete root.dataset.nav
  })
}

/** Phones: the tags, the list and a note stack like screens; deeper pushes, shallower pops. */
const depth = (route: Route) => ({ tags: 0, notes: 1, note: 2, settings: null })[route.name]

/** How a user-initiated move animates (see base.css), or null for none. */
export function transitionFor(from: string, to: string): 'forward' | 'back' | 'switch' | null {
  const a = parseRoute(from)
  const b = parseRoute(to)
  if (!window.matchMedia(PHONE).matches) return a.name === 'note' && b.name === 'note' ? 'switch' : null
  const da = depth(a)
  const db = depth(b)
  if (da === null || db === null || da === db) return null
  return db > da ? 'forward' : 'back'
}

export function parseRoute(pathname: string): Route {
  if (pathname === '/settings') return { name: 'settings' }
  if (pathname === '/tags') return { name: 'tags' }
  const match = /^\/notes\/([A-Za-z0-9_-]{1,64})$/.exec(pathname)
  if (match?.[1]) return { name: 'note', id: match[1] }
  return { name: 'notes' }
}

export function usePathname(): string {
  return useSyncExternalStore(subscribe, () => window.location.pathname)
}
