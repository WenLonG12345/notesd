import { useState, type FormEvent } from 'react'
import { auth } from '../api/auth'
import { ApiError } from '../api/client'
import { Button } from '../ui/button'
import { Field } from '../ui/field'
import { useAuth } from './auth-context'
import { genericMessage } from './errors'
import styles from './auth.module.css'

type Props = {
  /** Shown above the form, e.g. why the user has to sign in again. */
  notice?: string
  /** Prefilled and read-only when re-authenticating an expired session. */
  username?: string
  headingId?: string
}

export function LoginForm({ notice, username: fixedUsername, headingId }: Props) {
  const { signedIn } = useAuth()
  const [username, setUsername] = useState(fixedUsername ?? '')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!username.trim() || !password) {
      setError('Enter your username and password.')
      return
    }
    setBusy(true)
    setError(undefined)
    try {
      signedIn(await auth.login(username.trim(), password))
    } catch (err) {
      setBusy(false)
      setError(
        err instanceof ApiError && err.code === 'invalid_credentials'
          ? 'Wrong username or password.'
          : genericMessage(err),
      )
    }
  }

  return (
    <form onSubmit={(e) => void submit(e)} noValidate>
      <h1 id={headingId}>Sign in</h1>
      {notice && <p className={styles.notice}>{notice}</p>}
      <Field
        label="Username"
        name="username"
        autoComplete="username"
        autoCapitalize="none"
        spellCheck={false}
        value={username}
        readOnly={fixedUsername !== undefined}
        onChange={(e) => setUsername(e.target.value)}
        autoFocus={fixedUsername === undefined}
      />
      <Field
        label="Password"
        name="password"
        type="password"
        autoComplete="current-password"
        value={password}
        onChange={(e) => setPassword(e.target.value)}
        autoFocus={fixedUsername !== undefined}
      />
      {error && (
        <p role="alert" className={styles.formError}>
          {error}
        </p>
      )}
      <div className={styles.actions}>
        <Button variant="primary" type="submit" busy={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </Button>
      </div>
      <p className={styles.footnote}>
        Forgot it? Run <code>notes-server reset-password</code> on the server.
      </p>
    </form>
  )
}

export function Login() {
  return (
    <main className={styles.screen}>
      <div className={styles.panel}>
        <LoginForm />
      </div>
    </main>
  )
}
