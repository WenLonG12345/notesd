import { useCallback, useRef, useState, type FormEvent } from 'react'
import { auth } from '../api/auth'
import { ApiError } from '../api/client'
import { Button } from '../ui/button'
import { Field } from '../ui/field'
import { useAuth } from './auth-context'
import { genericMessage } from './errors'
import styles from './auth.module.css'

type Step = 'welcome' | 'token' | 'account'
const order: Step[] = ['welcome', 'token', 'account']

type FieldErrors = Partial<Record<'username' | 'password' | 'confirm', string>>

export function Onboarding() {
  const { signedIn, retry } = useAuth()
  const [step, setStep] = useState<Step>('welcome')
  const [token, setToken] = useState('')
  const [tokenError, setTokenError] = useState<string>()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [errors, setErrors] = useState<FieldErrors>({})
  const [formError, setFormError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const navigated = useRef(false)

  function go(next: Step) {
    navigated.current = true
    setStep(next)
  }

  // Stable ref callback: each step renders its own <h1>, so this runs once
  // per step. Focus makes screen readers announce it; skipped on first load.
  const heading = useCallback((el: HTMLHeadingElement | null) => {
    if (el && navigated.current) el.focus()
  }, [])

  function submitToken(e: FormEvent) {
    e.preventDefault()
    if (!token.trim()) {
      setTokenError('Paste the setup token from the server log.')
      return
    }
    setTokenError(undefined)
    go('account')
  }

  async function submitAccount(e: FormEvent) {
    e.preventDefault()
    const next: FieldErrors = {}
    if (!username.trim()) next.username = 'Choose a username.'
    if (password.length < 8) next.password = 'Use at least 8 characters.'
    else if (confirm !== password) next.confirm = "The passwords don't match."
    setErrors(next)
    setFormError(undefined)
    if (Object.keys(next).length > 0) return

    setBusy(true)
    try {
      await auth.setup(token.trim(), username.trim(), password)
    } catch (err) {
      setBusy(false)
      if (err instanceof ApiError) {
        if (err.code === 'invalid_setup_token') {
          setTokenError("That token didn't match. Copy it from the server log again.")
          go('token')
          return
        }
        if (err.code === 'already_set_up') {
          retry()
          return
        }
        if (err.status === 400 && (err.field === 'username' || err.field === 'password')) {
          setErrors({ [err.field]: sentence(err.message) })
          return
        }
      }
      setFormError(genericMessage(err))
      return
    }

    try {
      signedIn(await auth.login(username.trim(), password))
    } catch {
      // The account exists now; the regular sign-in screen takes it from here.
      retry()
    }
  }

  const stepNumber = order.indexOf(step) + 1

  return (
    <main className={styles.screen}>
      <div className={styles.panel}>
        <p className={styles.progress}>Step {stepNumber} of 3</p>

        {step === 'welcome' && (
          <>
            <h1 ref={heading} tabIndex={-1}>
              Welcome to Notes
            </h1>
            <p>
              This server doesn't have an account yet. You'll create the owner account now. It's the only
              account, and every device you sign in on uses it.
            </p>
            <div className={styles.actions}>
              <Button variant="primary" onClick={() => go('token')}>
                Set up my account
              </Button>
            </div>
          </>
        )}

        {step === 'token' && (
          <form onSubmit={submitToken} noValidate>
            <h1 ref={heading} tabIndex={-1}>
              Paste the setup token
            </h1>
            <p>The server printed a setup token in its log when it started. Paste it here. The line looks like:</p>
            <pre className={styles.example}>
              <code>setup_token=THQvj2U_aMzG…</code>
            </pre>
            <Field
              label="Setup token"
              name="setupToken"
              autoComplete="off"
              spellCheck={false}
              value={token}
              onChange={(e) => setToken(e.target.value)}
              error={tokenError}
              hint={
                <>
                  No access to the log? Run <code>notes-server create-owner</code> on the server instead.
                </>
              }
            />
            <div className={styles.actions}>
              <Button variant="primary" type="submit">
                Continue
              </Button>
              <Button variant="quiet" onClick={() => go('welcome')}>
                Back
              </Button>
            </div>
          </form>
        )}

        {step === 'account' && (
          <form onSubmit={(e) => void submitAccount(e)} noValidate>
            <h1 ref={heading} tabIndex={-1}>
              Create your account
            </h1>
            <Field
              label="Username"
              name="username"
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              error={errors.username}
            />
            <Field
              label="Password"
              name="password"
              type="password"
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              error={errors.password}
              hint="At least 8 characters."
            />
            <Field
              label="Confirm password"
              name="confirm"
              type="password"
              autoComplete="new-password"
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
              error={errors.confirm}
            />
            {formError && (
              <p role="alert" className={styles.formError}>
                {formError}
              </p>
            )}
            <div className={styles.actions}>
              <Button variant="primary" type="submit" busy={busy}>
                {busy ? 'Creating account…' : 'Create account'}
              </Button>
              <Button variant="quiet" onClick={() => go('token')} disabled={busy}>
                Back
              </Button>
            </div>
          </form>
        )}
      </div>
    </main>
  )
}

function sentence(message: string): string {
  const s = message.charAt(0).toUpperCase() + message.slice(1)
  return s.endsWith('.') ? s : `${s}.`
}
