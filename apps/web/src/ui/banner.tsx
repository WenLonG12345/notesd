import type { ReactNode } from 'react'
import styles from './banner.module.css'

type Props = {
  tone: 'error' | 'warn'
  children: ReactNode
  /** Buttons, right-aligned. Use Button size="sm". */
  actions?: ReactNode
}

/** Inline notice for something that needs attention, e.g. a failed save or a conflict. */
export function Banner({ tone, children, actions }: Props) {
  return (
    <div className={[styles.banner, styles[tone]].join(' ')} role={tone === 'error' ? 'alert' : 'status'}>
      <span className={styles.text}>{children}</span>
      {actions && <div className={styles.actions}>{actions}</div>}
    </div>
  )
}
