import { useAuth } from '../auth/auth-context'
import { Button } from '../ui/button'
import { Kicker } from '../ui/kicker'
import styles from './empty.module.css'

/** The editor pane with no note open. */
export function EmptyPane({ hasNotes, onNew }: { hasNotes: boolean; onNew: () => void }) {
  const { state } = useAuth()
  const firstRun = state.status === 'signed-in' && state.firstRun === true
  const username = state.status === 'signed-in' || state.status === 'expired' ? state.username : ''
  const kicker = hasNotes ? 'Nothing selected' : firstRun ? `You’re all set, ${username}` : 'No notes yet'
  return (
    <section className={styles.pane} aria-labelledby="empty-title">
      <div className={styles.text}>
        <Kicker>{kicker}</Kicker>
        <h2 id="empty-title">Your first note starts here.</h2>
        <p>The first line becomes the title. Everything saves as you type.</p>
        <Button variant="solid" size="md" className={styles.write} onClick={onNew}>
          Write a note
        </Button>
      </div>
    </section>
  )
}
