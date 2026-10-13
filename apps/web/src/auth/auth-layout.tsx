import type { ReactNode } from 'react'
import { Backdrop } from '../ui/backdrop'
import { Brand } from '../ui/brand'
import { Surface } from '../ui/surface'
import styles from './auth.module.css'

type Props = {
  children: ReactNode
  /** Shown top right, e.g. onboarding progress. */
  aside?: ReactNode
  footer?: ReactNode
}

/** Centered single-card layout for everything before the notes app. */
export function AuthLayout({ children, aside, footer }: Props) {
  return (
    <>
      <Backdrop />
      <main className={styles.screen}>
        <div className={styles.column}>
          <div className={styles.top}>
            <Brand size="lg" />
            {aside}
          </div>
          <Surface variant="sheet" enter className={styles.card}>
            {children}
          </Surface>
          {footer}
        </div>
      </main>
    </>
  )
}
