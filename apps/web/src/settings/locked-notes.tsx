import { useEffect, useState, type FormEvent } from 'react'
import { ApiError } from '../api/client'
import { genericMessage } from '../auth/errors'
import { LockedError } from '../locks/crypto'
import { PasskeyError, passkeysAvailable } from '../locks/passkey'
import { lockSession, useLockKeys, useLockStatus } from '../locks/session'
import { MIN_LOCK_PASSWORD } from '../locks/unlock'
import { Button } from '../ui/button'
import { Field } from '../ui/field'
import { Kicker } from '../ui/kicker'
import { Surface } from '../ui/surface'
import styles from './settings.module.css'

type Errors = Partial<Record<'current' | 'next' | 'confirm' | 'account', string>>

/** Locked notes (§10.1): change the lock password, lock now, or reset after forgetting it. */
export function LockedNotesCard({ username }: { username: string }) {
  const status = useLockStatus()
  useEffect(() => {
    void lockSession.load().catch(() => undefined)
  }, [])

  return (
    <Surface as="section" className={[styles.card, styles.wide].join(' ')} aria-labelledby="locks-kicker">
      <Kicker id="locks-kicker">Locked notes</Kicker>
      {status === 'unknown' && <span className={styles.muted}>Loading…</span>}
      {status === 'none' && (
        <span className={styles.text}>
          No lock password yet. Choose Lock note from a note’s menu to create one; the note is then encrypted so the
          server can’t read it.
        </span>
      )}
      {(status === 'locked' || status === 'unlocked') && (
        <>
          <Manage unlocked={status === 'unlocked'} />
          <Passkeys username={username} unlocked={status === 'unlocked'} />
        </>
      )}
    </Surface>
  )
}

function Manage({ unlocked }: { unlocked: boolean }) {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [errors, setErrors] = useState<Errors>({})
  const [formError, setFormError] = useState<string>()
  const [done, setDone] = useState(false)
  const [busy, setBusy] = useState(false)
  const [resetting, setResetting] = useState(false)
  const [account, setAccount] = useState('')

  async function change(e: FormEvent) {
    e.preventDefault()
    const found: Errors = {}
    if (!current) found.current = 'Enter your current lock password.'
    if (next.length < MIN_LOCK_PASSWORD) found.next = `Use at least ${MIN_LOCK_PASSWORD} characters.`
    else if (confirm !== next) found.confirm = 'The passwords don’t match.'
    setErrors(found)
    setFormError(undefined)
    setDone(false)
    if (Object.keys(found).length > 0) return
    setBusy(true)
    try {
      await lockSession.changePassword(current, next)
      setCurrent('')
      setNext('')
      setConfirm('')
      setDone(true)
    } catch (err) {
      if (err instanceof LockedError) setErrors({ current: 'That isn’t your lock password.' })
      else setFormError(genericMessage(err))
    } finally {
      setBusy(false)
    }
  }

  async function reset(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    try {
      await lockSession.reset(account)
    } catch (err) {
      if (err instanceof ApiError && err.code === 'invalid_credentials') setErrors({ account: 'That isn’t your account password.' })
      else setFormError(genericMessage(err))
    } finally {
      setBusy(false)
    }
  }

  if (resetting) {
    return (
      <form className={styles.form} onSubmit={(e) => void reset(e)} noValidate>
        <span className={styles.text}>
          Resetting forgets the lock password. Notes locked with it stay encrypted and can never be opened again, on any
          device. You can then create a new lock password.
        </span>
        <Field
          label="Account password"
          type="password"
          autoComplete="current-password"
          value={account}
          onChange={(e) => {
            setAccount(e.target.value)
            setErrors({})
          }}
          error={errors.account}
          autoFocus
        />
        {formError && (
          <p role="alert" className={styles.error}>
            {formError}
          </p>
        )}
        <div className={styles.buttons}>
          <Button size="md" onClick={() => setResetting(false)} disabled={busy}>
            Cancel
          </Button>
          <Button variant="danger" size="md" type="submit" busy={busy}>
            Reset locked notes
          </Button>
        </div>
      </form>
    )
  }

  return (
    <>
      <form className={styles.form} onSubmit={(e) => void change(e)} noValidate>
        <Field
          label="Current lock password"
          type="password"
          autoComplete="current-password"
          value={current}
          onChange={(e) => setCurrent(e.target.value)}
          error={errors.current}
        />
        <div className={styles.pair}>
          <Field
            label="New lock password"
            type="password"
            autoComplete="new-password"
            placeholder={`At least ${MIN_LOCK_PASSWORD} characters`}
            value={next}
            onChange={(e) => setNext(e.target.value)}
            error={errors.next}
          />
          <Field
            label="Confirm new lock password"
            type="password"
            autoComplete="new-password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            error={errors.confirm}
          />
        </div>
        {formError && (
          <p role="alert" className={styles.error}>
            {formError}
          </p>
        )}
        {done && (
          <p role="status" className={styles.success}>
            Lock password changed. Other devices use the new one once they sync.
          </p>
        )}
        <div className={styles.buttons}>
          <Button variant="solid" size="md" type="submit" busy={busy}>
            {busy ? 'Changing…' : 'Change lock password'}
          </Button>
          {unlocked && (
            <Button size="md" onClick={() => void lockSession.lock()}>
              Lock now
            </Button>
          )}
        </div>
      </form>
      <div className={styles.forgot}>
        <span className={styles.muted}>Forgot it? Locked notes can’t be recovered without it.</span>
        <div>
          <Button variant="danger-text" size="sm" onClick={() => setResetting(true)}>
            Reset locked notes…
          </Button>
        </div>
      </div>
    </>
  )
}

/** Passkeys that unlock locked notes instead of the lock password (WebAuthn PRF). */
function Passkeys({ username, unlocked }: { username: string; unlocked: boolean }) {
  const passkeys = useLockKeys().filter((k) => k.kind === 'passkey')
  const available = passkeysAvailable()
  const [adding, setAdding] = useState(false)
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string>()
  const [message, setMessage] = useState<string>()
  const [busy, setBusy] = useState(false)

  async function add(e: FormEvent) {
    e.preventDefault()
    if (!password) return
    setBusy(true)
    setError(undefined)
    setMessage(undefined)
    try {
      await lockSession.addPasskey(password, username)
      setAdding(false)
      setPassword('')
      setMessage('Passkey added. Use it to unlock locked notes wherever it’s available.')
    } catch (err) {
      if (err instanceof PasskeyError && err.reason === 'cancelled') return
      if (err instanceof LockedError) setError('That isn’t your lock password.')
      else if (err instanceof PasskeyError) setError(err.message)
      else setError(genericMessage(err))
    } finally {
      setBusy(false)
    }
  }

  async function remove(id: string) {
    setBusy(true)
    setMessage(undefined)
    try {
      await lockSession.removePasskey(id)
    } catch (err) {
      setMessage(genericMessage(err))
    } finally {
      setBusy(false)
    }
  }

  if (!available && passkeys.length === 0) return null
  return (
    <div className={styles.forgot} role="group" aria-label="Passkeys">
      <span className={styles.muted}>
        {passkeys.length === 0
          ? 'Unlock with a passkey (fingerprint, face or device PIN) instead of typing the lock password.'
          : 'These passkeys unlock locked notes:'}
      </span>
      {passkeys.map((k) => (
        <div key={k.id} className={styles.buttons}>
          <span className={styles.text}>Passkey added {new Date(k.createdAt).toLocaleDateString()}</span>
          <Button
            variant="danger-text"
            size="sm"
            onClick={() => void remove(k.id)}
            disabled={busy || !unlocked}
            title={unlocked ? undefined : 'Unlock locked notes to remove a passkey'}
            aria-label={`Remove passkey added ${new Date(k.createdAt).toLocaleDateString()}`}
          >
            Remove
          </Button>
        </div>
      ))}
      {message && (
        <p role="status" className={styles.success}>
          {message}
        </p>
      )}
      {available &&
        (adding ? (
          <form className={styles.form} onSubmit={(e) => void add(e)} noValidate>
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
              hint="Needed once, to give the passkey a copy of the key."
              autoFocus
            />
            <div className={styles.buttons}>
              <Button variant="solid" size="md" type="submit" busy={busy}>
                {busy ? 'Waiting for the passkey…' : 'Continue'}
              </Button>
              <Button size="md" onClick={() => setAdding(false)} disabled={busy}>
                Cancel
              </Button>
            </div>
          </form>
        ) : (
          <div>
            <Button size="sm" onClick={() => setAdding(true)}>
              Add a passkey
            </Button>
          </div>
        ))}
    </div>
  )
}
