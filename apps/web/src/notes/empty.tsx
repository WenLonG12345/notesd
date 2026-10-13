import { useAuth } from '../auth/auth-context'
import { Button } from '../ui/button'
import { Kicker } from '../ui/kicker'
import styles from './empty.module.css'

export function EmptyNotes({ onNew }: { onNew: () => void }) {
  const { state } = useAuth()
  const firstRun = state.status === 'signed-in' && state.firstRun === true
  const username = state.status === 'signed-in' || state.status === 'expired' ? state.username : ''
  return (
    <div className={styles.grid}>
      <section className={styles.card} aria-labelledby="empty-title">
        <div className={styles.text}>
          <Kicker>{firstRun ? `You’re all set, ${username}` : 'No notes yet'}</Kicker>
          <h3 id="empty-title">Your first note starts here.</h3>
          <p>A grocery list, a half‑formed idea, the name of that song. Everything saves as you type.</p>
        </div>
        <Button variant="solid" size="md" onClick={onNew}>
          Write a note
        </Button>
      </section>
      <div className={styles.ghost} aria-hidden="true" />
      <div className={[styles.ghost, styles.tall].join(' ')} aria-hidden="true" />
      <div className={styles.ghost} aria-hidden="true" />
    </div>
  )
}
