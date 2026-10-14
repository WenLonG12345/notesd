import { useEffect, useState, type FormEvent } from 'react'
import { auth } from '../api/auth'
import { ApiError } from '../api/client'
import type { Session } from '../api/types'
import { platformBadge, sessionName } from '../api/user-agent'
import { useAuth } from '../auth/auth-context'
import { genericMessage } from '../auth/errors'
import { relativeTime } from '../notes/relative-time'
import { applyTheme, readTheme, type ThemeChoice } from '../theme'
import { Button } from '../ui/button'
import { Command } from '../ui/code'
import { Field } from '../ui/field'
import { Kicker } from '../ui/kicker'
import { Sheet } from '../ui/sheet'
import { Surface } from '../ui/surface'
import styles from './settings.module.css'

export function SettingsSheet({ username, onClose }: { username: string; onClose: () => void }) {
  const { signOut } = useAuth()
  // Bumped after a password change, which signs out every other session.
  const [sessionsVersion, setSessionsVersion] = useState(0)

  return (
    <Sheet labelledBy="settings-title" onClose={onClose}>
      <div className={styles.top}>
        <h2 id="settings-title">Settings</h2>
        <Button size="md" onClick={onClose}>
          Done
        </Button>
      </div>

      <div className={styles.row}>
        <Surface as="section" className={[styles.card, styles.wide].join(' ')} aria-labelledby="account-kicker">
          <Kicker id="account-kicker">Signed in as</Kicker>
          <div className={styles.identity}>
            <div className={styles.avatar} aria-hidden="true">
              {(username[0] ?? '·').toUpperCase()}
            </div>
            <div className={styles.who}>
              <span className={styles.name}>{username}</span>
              <span className={styles.muted}>Owner · {window.location.host}</span>
            </div>
          </div>
        </Surface>
        <Appearance />
      </div>

      <div className={styles.row}>
        <PasswordCard onChanged={() => setSessionsVersion((v) => v + 1)} />
        <Surface as="section" className={styles.card} aria-label="Sign out">
          <span className={styles.text}>Sign out of this browser. Your notes stay on the server.</span>
          <Button variant="danger" size="md" onClick={() => void signOut()}>
            Sign out
          </Button>
        </Surface>
      </div>

      {/* Remounted (and refetched) after a password change signs other sessions out. */}
      <Devices key={sessionsVersion} />
    </Sheet>
  )
}

const themes: { value: ThemeChoice; label: string }[] = [
  { value: 'system', label: 'System' },
  { value: 'light', label: 'Light' },
  { value: 'dark', label: 'Dark' },
]

function Appearance() {
  const [theme, setTheme] = useState<ThemeChoice>(readTheme)
  return (
    <Surface as="section" className={styles.card} aria-labelledby="appearance-kicker">
      <Kicker id="appearance-kicker">Appearance</Kicker>
      <div className={styles.segmented} role="radiogroup" aria-labelledby="appearance-kicker">
        {themes.map((t) => (
          <button
            key={t.value}
            type="button"
            role="radio"
            aria-checked={theme === t.value}
            className={styles.segment}
            onClick={() => {
              setTheme(t.value)
              applyTheme(t.value)
            }}
          >
            {t.label}
          </button>
        ))}
      </div>
    </Surface>
  )
}

type PasswordErrors = Partial<Record<'current' | 'next' | 'confirm', string>>

function PasswordCard({ onChanged }: { onChanged: () => void }) {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [errors, setErrors] = useState<PasswordErrors>({})
  const [formError, setFormError] = useState<string>()
  const [done, setDone] = useState(false)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    const found: PasswordErrors = {}
    if (!current) found.current = 'Enter your current password.'
    if (next.length < 8) found.next = 'Use at least 8 characters.'
    else if (confirm !== next) found.confirm = "The passwords don't match."
    setErrors(found)
    setFormError(undefined)
    setDone(false)
    if (Object.keys(found).length > 0) return

    setBusy(true)
    try {
      await auth.changePassword(current, next)
      setCurrent('')
      setNext('')
      setConfirm('')
      setDone(true)
      onChanged()
    } catch (err) {
      if (err instanceof ApiError && err.code === 'invalid_credentials') {
        setErrors({ current: 'That isn’t your current password.' })
      } else if (err instanceof ApiError && err.status === 400 && err.field === 'password') {
        setErrors({ next: err.message.charAt(0).toUpperCase() + err.message.slice(1) + '.' })
      } else {
        setFormError(genericMessage(err))
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <Surface as="section" className={[styles.card, styles.password].join(' ')} aria-labelledby="password-kicker">
      <Kicker id="password-kicker">Password</Kicker>
      <form className={styles.form} onSubmit={(e) => void submit(e)} noValidate>
        <Field
          label="Current password"
          type="password"
          autoComplete="current-password"
          value={current}
          onChange={(e) => setCurrent(e.target.value)}
          error={errors.current}
        />
        <div className={styles.pair}>
          <Field
            label="New password"
            type="password"
            autoComplete="new-password"
            placeholder="At least 8 characters"
            value={next}
            onChange={(e) => setNext(e.target.value)}
            error={errors.next}
          />
          <Field
            label="Confirm new password"
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
            Password changed. Every other browser and device was signed out and needs the new password.
          </p>
        )}
        <div>
          <Button variant="solid" size="md" type="submit" busy={busy}>
            {busy ? 'Changing…' : 'Change password'}
          </Button>
        </div>
      </form>
      <div className={styles.forgot}>
        <span className={styles.muted}>Forgot it? Reset it on the server:</span>
        <Command command="notesd reset-password" tone="dark" />
      </div>
    </Surface>
  )
}

type DevicesState = { status: 'loading' | 'ready' | 'error'; sessions: Session[]; currentId: string }

function Devices() {
  const [state, setState] = useState<DevicesState>({ status: 'loading', sessions: [], currentId: '' })
  const [revoking, setRevoking] = useState<string | null>(null)
  const [error, setError] = useState<string>()

  useEffect(() => {
    let cancelled = false
    auth.sessions().then(
      ({ sessions, currentId }) => {
        if (!cancelled) setState({ status: 'ready', sessions: sortSessions(sessions, currentId), currentId })
      },
      () => {
        if (!cancelled) setState((s) => ({ ...s, status: 'error' }))
      },
    )
    return () => {
      cancelled = true
    }
  }, [])

  async function revoke(id: string) {
    setRevoking(id)
    setError(undefined)
    try {
      await auth.revokeSession(id)
      setState((s) => ({ ...s, sessions: s.sessions.filter((x) => x.id !== id) }))
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) {
        setState((s) => ({ ...s, sessions: s.sessions.filter((x) => x.id !== id) }))
      } else setError(genericMessage(err))
    } finally {
      setRevoking(null)
    }
  }

  const n = state.sessions.length
  return (
    <Surface as="section" className={styles.devices} aria-labelledby="devices-kicker">
      <div className={styles.devicesHead}>
        <Kicker id="devices-kicker">Signed-in devices</Kicker>
        {state.status === 'ready' && (
          <span className={styles.muted}>
            {n} {n === 1 ? 'device' : 'devices'}
          </span>
        )}
      </div>
      {state.status === 'loading' && <p className={styles.deviceNote}>Loading…</p>}
      {state.status === 'error' && <p className={styles.deviceNote}>Couldn’t load the list of devices.</p>}
      {error && (
        <p role="alert" className={[styles.deviceNote, styles.error].join(' ')}>
          {error}
        </p>
      )}
      <ul className={styles.deviceList}>
        {state.sessions.map((s) => {
          const current = s.id === state.currentId
          const name = sessionName(s.name, s.kind)
          return (
            <li key={s.id} className={styles.device}>
              <span className={styles.badge} aria-hidden="true">
                {platformBadge(name, s.kind)}
              </span>
              <span className={styles.deviceText}>
                <span className={styles.deviceName}>{name}</span>
                <span className={styles.muted}>
                  {current ? 'Active now' : `Last used ${relativeTime(s.lastUsedAt).toLowerCase()}`}
                </span>
              </span>
              {current ? (
                <span className={styles.here}>This browser</span>
              ) : (
                <Button
                  size="sm"
                  busy={revoking === s.id}
                  onClick={() => void revoke(s.id)}
                  aria-label={`Sign out ${name}`}
                >
                  Sign out
                </Button>
              )}
            </li>
          )
        })}
      </ul>
    </Surface>
  )
}

function sortSessions(sessions: Session[], currentId: string): Session[] {
  return sessions.toSorted((a, b) =>
    a.id === currentId ? -1 : b.id === currentId ? 1 : b.lastUsedAt - a.lastUsedAt,
  )
}
