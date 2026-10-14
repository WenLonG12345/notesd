import { useEffect, useSyncExternalStore } from 'react'
import { settings } from '../api/settings'
import type { EmailSettings } from '../api/types'

// One copy for the share menu and Settings, fetched once per page load and
// replaced when Settings saves.
let current: EmailSettings | null = null
let loading: Promise<void> | null = null
const listeners = new Set<() => void>()

export function setEmailSettings(next: EmailSettings | null) {
  current = next
  listeners.forEach((l) => l())
}

function load() {
  loading ??= settings.email().then(
    (s) => setEmailSettings(s),
    () => {
      // Retried the next time something asks.
      loading = null
    },
  )
}

/** The email settings, or null until they've loaded. */
export function useEmailSettings(): EmailSettings | null {
  const value = useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => listeners.delete(l)
    },
    () => current,
  )
  useEffect(load, [])
  return value
}

/** For tests: forget what was loaded. */
export function resetEmailSettings() {
  current = null
  loading = null
}
