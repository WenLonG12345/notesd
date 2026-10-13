import { useSyncExternalStore } from 'react'

export type Route = { name: 'notes' } | { name: 'note'; id: string } | { name: 'settings' }

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
  if (replace) window.history.replaceState(null, '', path)
  else window.history.pushState(null, '', path)
  for (const listener of listeners) listener()
}

export function parseRoute(pathname: string): Route {
  if (pathname === '/settings') return { name: 'settings' }
  const match = /^\/notes\/([A-Za-z0-9_-]{1,64})$/.exec(pathname)
  if (match?.[1]) return { name: 'note', id: match[1] }
  return { name: 'notes' }
}

export function usePathname(): string {
  return useSyncExternalStore(subscribe, () => window.location.pathname)
}
