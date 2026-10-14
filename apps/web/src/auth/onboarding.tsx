import { useCallback, useRef, useState, type FormEvent } from 'react'
import { auth } from '../api/auth'
import { ApiError } from '../api/client'
import { Button } from '../ui/button'
import { Dim, Hi, Terminal } from '../ui/code'
import { Field } from '../ui/field'
import { Kicker } from '../ui/kicker'
import { PasswordMeter } from '../ui/password-meter'
import { useShake } from '../ui/shake'
import { Steps } from '../ui/steps'
import { useAuth } from './auth-context'
import { AuthLayout } from './auth-layout'
import { genericMessage } from './errors'
import styles from './auth.module.css'

type Step = 'welcome' | 'token' | 'account'
const stepName: Record<Step, string> = { welcome: 'Welcome', token: 'Connect', account: 'Account' }

type FieldErrors = Partial<Record<'username' | 'password' | 'confirm', string>>

/** linkToken: from the server's setup link, which makes pasting the token unnecessary. */
export function Onboarding({ linkToken }: { linkToken?: string }) {
  const { signedIn, retry } = useAuth()
  const [step, setStep] = useState<Step>('welcome')
  const [viaLink, setViaLink] = useState(Boolean(linkToken))
  const [token, setToken] = useState(linkToken ?? '')
  const [tokenError, setTokenError] = useState<string>()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [errors, setErrors] = useState<FieldErrors>({})
  const [formError, setFormError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [tokenRef, shakeToken] = useShake<HTMLDivElement>()
  const [accountRef, shakeAccount] = useShake<HTMLDivElement>()
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
      shakeToken()
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
    if (Object.keys(next).length > 0) {
      shakeAccount()
      return
    }

    setBusy(true)
    try {
      await auth.setup(token.trim(), username.trim(), password)
    } catch (err) {
      setBusy(false)
      if (err instanceof ApiError) {
        if (err.code === 'invalid_setup_token') {
          setTokenError(
            viaLink
              ? 'That setup link is out of date: the server prints a new one each time it starts. Paste the latest token from its log.'
              : "That token didn't match. Copy it from the server log again.",
          )
          if (viaLink) setToken('')
          setViaLink(false)
          go('token')
          return
        }
        if (err.code === 'already_set_up') {
          retry()
          return
        }
        if (err.status === 400 && (err.field === 'username' || err.field === 'password')) {
          setErrors({ [err.field]: sentence(err.message) })
          shakeAccount()
          return
        }
      }
      setFormError(genericMessage(err))
      shakeAccount()
      return
    }

    try {
      signedIn(await auth.login(username.trim(), password), { firstRun: true })
    } catch {
      // The account exists now; the regular sign-in screen takes it from here.
      retry()
    }
  }

  const order: Step[] = viaLink ? ['welcome', 'account'] : ['welcome', 'token', 'account']
  const index = order.indexOf(step)
  const kicker = (s: Step) => `Step ${order.indexOf(s) + 1} of ${order.length} · ${stepName[s]}`

  return (
    <AuthLayout aside={<Steps current={index} total={order.length} />}>
      {step === 'welcome' && (
        <div className={styles.stack}>
          <Kicker>{kicker('welcome')}</Kicker>
          <h1 ref={heading} tabIndex={-1} className={styles.hero}>
            A quiet place for everything you write.
          </h1>
          <p className={styles.lede}>
            notesd runs on your own server. What you write stays there, and every device you sign in on stays in
            sync. This server has no account yet, so let’s create yours.
          </p>
          <Button variant="solid" onClick={() => go(viaLink ? 'account' : 'token')}>
            Get started
          </Button>
        </div>
      )}

      {step === 'token' && (
        <form className={styles.stack} onSubmit={submitToken} noValidate>
          <Kicker>{kicker('token')}</Kicker>
          <h1 ref={heading} tabIndex={-1}>
            Paste your setup token
          </h1>
          <p className={styles.lede}>
            When the server starts without an account, it prints a setup link and a one‑time token to its log.
            They prove you’re the person who installed it. Opening the link skips this step.
          </p>
          <Terminal label="Example server log">
            <Dim>level=WARN </Dim>msg="no owner account yet…"{'\n'}
            <Dim>{'           '}</Dim>setup_url=https://…/setup#THQvj2U_aMzG…{'\n'}
            <Dim>{'           '}</Dim>setup_token=<Hi>THQvj2U_aMzG…</Hi>
          </Terminal>
          <div ref={tokenRef}>
            <Field
              label="Setup token"
              hideLabel
              mono
              name="setupToken"
              placeholder="Paste the token"
              autoComplete="off"
              spellCheck={false}
              value={token}
              onChange={(e) => {
                setToken(e.target.value)
                setTokenError(undefined)
              }}
              error={tokenError}
              hint={
                <>
                  No access to the log? Run <code>notesd create-owner --username NAME</code> on the server instead.
                </>
              }
            />
          </div>
          <div className={styles.actions}>
            <Button onClick={() => go('welcome')}>Back</Button>
            <Button variant="solid" type="submit" grow>
              Continue
            </Button>
          </div>
        </form>
      )}

      {step === 'account' && (
        <form className={styles.stack} onSubmit={(e) => void submitAccount(e)} noValidate>
          <Kicker>{kicker('account')}</Kicker>
          <h1 ref={heading} tabIndex={-1}>
            Choose how you’ll sign in
          </h1>
          <p className={styles.lede}>This is the only account on this server.</p>
          <div ref={accountRef} className={styles.fields}>
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
              placeholder="At least 8 characters"
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              error={errors.password}
            >
              <PasswordMeter password={password} />
            </Field>
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
          </div>
          <div className={styles.actions}>
            <Button onClick={() => go(viaLink ? 'welcome' : 'token')} disabled={busy}>
              Back
            </Button>
            <Button variant="accent" type="submit" grow busy={busy}>
              {busy ? 'Creating account…' : 'Create account'}
            </Button>
          </div>
        </form>
      )}
    </AuthLayout>
  )
}

function sentence(message: string): string {
  const s = message.charAt(0).toUpperCase() + message.slice(1)
  return s.endsWith('.') ? s : `${s}.`
}
