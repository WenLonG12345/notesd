import type { ReactNode } from 'react'
import styles from './kicker.module.css'

/** Small monospace caps label above a heading or a section. */
export function Kicker({ children, id }: { children: ReactNode; id?: string }) {
  return (
    <span id={id} className={styles.kicker}>
      {children}
    </span>
  )
}
