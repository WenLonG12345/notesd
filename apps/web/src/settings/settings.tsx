import { useAuth } from '../auth/auth-context'
import { Button } from '../ui/button'
import { Kicker } from '../ui/kicker'
import { Sheet } from '../ui/sheet'
import { Surface } from '../ui/surface'
import styles from './settings.module.css'

// Phase 5 adds appearance, password and devices.
export function SettingsSheet({ username, onClose }: { username: string; onClose: () => void }) {
  const { signOut } = useAuth()
  return (
    <Sheet labelledBy="settings-title" variant="stack" onClose={onClose}>
      <div className={styles.top}>
        <h2 id="settings-title">Settings</h2>
        <Button size="md" onClick={onClose}>
          Done
        </Button>
      </div>
      <div className={styles.row}>
        <Surface className={[styles.card, styles.wide].join(' ')}>
          <Kicker>Signed in as</Kicker>
          <div className={styles.identity}>
            <div className={styles.avatar} aria-hidden="true">
              {(username[0] ?? '·').toUpperCase()}
            </div>
            <div className={styles.who}>
              <span className={styles.name}>{username}</span>
              <span className={styles.host}>Owner · {window.location.host}</span>
            </div>
          </div>
        </Surface>
        <Surface className={styles.card}>
          <span className={styles.text}>Sign out of this browser. Your notes stay on the server.</span>
          <Button variant="danger" size="md" onClick={() => void signOut()}>
            Sign out
          </Button>
        </Surface>
      </div>
    </Sheet>
  )
}
