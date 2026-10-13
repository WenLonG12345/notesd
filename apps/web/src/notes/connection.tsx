import { useSyncExternalStore } from 'react'
import { connectivity } from '../api/client'
import styles from './connection.module.css'

/** Quiet notice while requests can't reach the server; saves retry on their own. */
export function ConnectionBanner() {
  const online = useSyncExternalStore(connectivity.subscribe, connectivity.get)
  if (online) return null
  return (
    <div className={styles.wrap}>
      <p className={styles.banner} role="status">
        No connection to the server — changes will be saved when it’s back.
      </p>
    </div>
  )
}
