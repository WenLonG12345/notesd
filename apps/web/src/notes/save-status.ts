import { useEffect, useState } from 'react'
import { timing, type Snapshot } from './saver'

/**
 * Saving should go unnoticed. "now": show it (a conflict, a failure a retry
 * didn't fix); "pending": show it only if it lasts; "none": all saved.
 */
export function urgency(snap: Snapshot): 'none' | 'pending' | 'now' {
  if (snap.state === 'conflict') return 'now'
  if (snap.state === 'failed') return quietFailure(snap) ? 'pending' : 'now'
  if (snap.state === 'unsaved' || snap.state === 'saving') return 'pending'
  return 'none'
}

/** One unreachable-server failure is usually a blip the automatic retry fixes. */
function quietFailure(snap: Snapshot): boolean {
  return snap.problem?.kind === 'offline' && snap.failures < 2
}

/** The "couldn't reach the server" notice, once a retry has failed too. */
export function offlineNotice(snap: Snapshot): boolean {
  return snap.problem?.kind === 'offline' && !quietFailure(snap)
}

/** Whether the status pill is worth showing right now. */
export function useStatusShown(snap: Snapshot): boolean {
  const level = urgency(snap)
  const lingering = useLasting(level === 'pending', timing.quietMs)
  return level === 'now' || lingering
}

/** True once [active] has held for [ms]. */
function useLasting(active: boolean, ms: number): boolean {
  const [lasted, setLasted] = useState(false)
  useEffect(() => {
    if (!active) return
    const timer = setTimeout(() => setLasted(true), ms)
    return () => {
      clearTimeout(timer)
      setLasted(false)
    }
  }, [active, ms])
  return active && lasted
}
