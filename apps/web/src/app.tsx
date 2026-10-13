import { AuthProvider, useAuth } from './auth/auth-context'
import { Login, LoginForm } from './auth/login'
import { Onboarding } from './auth/onboarding'
import { Button } from './ui/button'
import { Dialog } from './ui/dialog'
import styles from './auth/auth.module.css'

export function App() {
  return (
    <AuthProvider>
      <AuthGate />
    </AuthProvider>
  )
}

function AuthGate() {
  const { state, retry } = useAuth()
  switch (state.status) {
    case 'loading':
      return null
    case 'unreachable':
      return (
        <main className={styles.screen}>
          <div className={styles.panel} role="alert">
            <h1>Can't reach the server</h1>
            <p>Check that the notes server is running, then try again.</p>
            <div className={styles.actions}>
              <Button variant="primary" onClick={retry}>
                Try again
              </Button>
            </div>
          </div>
        </main>
      )
    case 'setup':
      return <Onboarding />
    case 'signed-out':
      return <Login />
    case 'signed-in':
    case 'expired':
      return (
        <>
          <Shell username={state.username} />
          {state.status === 'expired' && (
            <Dialog labelledBy="session-ended-title">
              <LoginForm
                headingId="session-ended-title"
                username={state.username}
                notice="Your session ended. Sign in again."
              />
            </Dialog>
          )}
        </>
      )
  }
}

// Replaced by the notes shell in the next step.
function Shell({ username }: { username: string }) {
  const { signOut } = useAuth()
  return (
    <main className={styles.screen}>
      <div className={styles.panel}>
        <h1>Signed in as {username}</h1>
        <div className={styles.actions}>
          <Button onClick={() => void signOut()}>Sign out</Button>
        </div>
      </div>
    </main>
  )
}
