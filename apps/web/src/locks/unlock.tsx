import { useId, useState, type FormEvent } from 'react'
import { genericMessage } from '../auth/errors'
import { Button } from '../ui/button'
import { Dialog } from '../ui/dialog'
import { Field } from '../ui/field'
import { PasswordMeter } from '../ui/password-meter'
import { useShake } from '../ui/shake'
import { LockedError } from './crypto'
import { lockSession } from './session'
import styles from './locks.module.css'

/** Shortest lock password accepted; it's the only thing between the server and the notes. */
export const MIN_LOCK_PASSWORD = 8

/** The lock password field and its button ([action], Unlock by default); inline in the note pane or inside LockNoteDialog. */
export function UnlockForm({
  onUnlocked,
  onCancel,
  autoFocus = true,
  action = ['Unlock', 'Unlocking…'],
}: {
  onUnlocked: () => void
  onCancel?: () => void
  autoFocus?: boolean
  action?: [string, string]
}) {
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [ref, shake] = useShake<HTMLFormElement>()

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!password) return
    setBusy(true)
    setError(undefined)
    try {
      await lockSession.unlock(password)
      onUnlocked()
    } catch (err) {
      setError(err instanceof LockedError ? 'Wrong password.' : genericMessage(err))
      shake()
    } finally {
      setBusy(false)
    }
  }

  return (
    <form ref={ref} className={onCancel ? styles.stack : styles.inline} onSubmit={(e) => void submit(e)} noValidate>
      <Field
        label="Lock password"
        type="password"
        autoComplete="current-password"
        value={password}
        onChange={(e) => {
          setPassword(e.target.value)
          setError(undefined)
        }}
        error={error}
        autoFocus={autoFocus}
      />
      <div className={styles.actions}>
        {onCancel && (
          <Button size="md" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
        )}
        <Button variant="solid" size="md" type="submit" busy={busy} grow={!onCancel}>
          {busy ? action[1] : action[0]}
        </Button>
      </div>
    </form>
  )
}

/**
 * Lock note, when a lock password already exists (made on any device) but
 * isn't entered yet: every locked note shares it, so it's asked for, not
 * created.
 */
export function LockNoteDialog({ onUnlocked, onClose }: { onUnlocked: () => void; onClose: () => void }) {
  const titleId = useId()
  return (
    <Dialog labelledBy={titleId} onClose={onClose}>
      <div className={styles.stack}>
        <h2 id={titleId} className={styles.title}>
          Lock this note
        </h2>
        <p className={styles.text}>
          Locked notes share one lock password, made the first time a note was locked on any of your devices. Enter
          it to lock this note.
        </p>
        <UnlockForm onUnlocked={onUnlocked} onCancel={onClose} action={['Lock', 'Locking…']} />
      </div>
    </Dialog>
  )
}

/** First lock: choose the password every locked note will share. */
export function SetupDialog({ onDone, onClose }: { onDone: () => void; onClose: () => void }) {
  const titleId = useId()
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<{ field: 'password' | 'confirm'; message: string }>()
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (password.length < MIN_LOCK_PASSWORD) {
      setError({ field: 'password', message: `Use at least ${MIN_LOCK_PASSWORD} characters.` })
      return
    }
    if (confirm !== password) {
      setError({ field: 'confirm', message: 'The passwords don’t match.' })
      return
    }
    setBusy(true)
    try {
      await lockSession.setup(password)
      onDone()
    } catch (err) {
      setError({ field: 'password', message: genericMessage(err) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog labelledBy={titleId} onClose={onClose}>
      <form className={styles.stack} onSubmit={(e) => void submit(e)} noValidate>
        <h2 id={titleId} className={styles.title}>
          Create a lock password
        </h2>
        <p className={styles.text}>
          Locked notes are encrypted in this browser and on your devices; the server can’t read them. Every locked note
          uses this password. If you forget it, those notes can’t be recovered.
        </p>
        <Field
          label="Lock password"
          type="password"
          autoComplete="new-password"
          value={password}
          onChange={(e) => {
            setPassword(e.target.value)
            setError(undefined)
          }}
          error={error?.field === 'password' ? error.message : undefined}
          autoFocus
        >
          <PasswordMeter password={password} />
        </Field>
        <Field
          label="Confirm"
          type="password"
          autoComplete="new-password"
          value={confirm}
          onChange={(e) => {
            setConfirm(e.target.value)
            setError(undefined)
          }}
          error={error?.field === 'confirm' ? error.message : undefined}
        />
        <div className={styles.actions}>
          <Button size="md" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button variant="solid" size="md" type="submit" busy={busy}>
            {busy ? 'Creating…' : 'Create and lock'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
