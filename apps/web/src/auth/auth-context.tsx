import { createContext, use, useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { auth } from '../api/auth'
import { ApiError, onUnauthenticated, setCsrfToken } from '../api/client'
import type { SessionInfo } from '../api/types'

export type AuthState =
  | { status: 'loading' }
  | { status: 'unreachable' }
  | { status: 'setup' }
  | { status: 'signed-out' }
  // The session ended while the app was in use. The shell stays mounted
  // under a sign-in dialog so unsaved edits survive and retry afterwards.
  | { status: 'expired'; username: string }
  // firstRun: just finished onboarding, for the welcome empty state.
  | { status: 'signed-in'; username: string; firstRun?: boolean }

type AuthContextValue = {
  state: AuthState
  signedIn: (info: SessionInfo, opts?: { firstRun?: boolean }) => void
  signOut: () => Promise<void>
  retry: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'loading' })

  useEffect(() => {
    void boot().then(setState)
  }, [])

  useEffect(
    () =>
      onUnauthenticated(() => {
        setCsrfToken(null)
        setState((s) => (s.status === 'signed-in' ? { status: 'expired', username: s.username } : s))
      }),
    [],
  )

  const signedIn = useCallback((info: SessionInfo, opts: { firstRun?: boolean } = {}) => {
    setCsrfToken(info.csrfToken)
    setState({ status: 'signed-in', username: info.username, firstRun: opts.firstRun ?? false })
  }, [])

  const signOut = useCallback(async () => {
    try {
      await auth.logout()
    } finally {
      setCsrfToken(null)
      setState({ status: 'signed-out' })
    }
  }, [])

  const retry = useCallback(() => {
    setState({ status: 'loading' })
    void boot().then(setState)
  }, [])

  const value = useMemo(() => ({ state, signedIn, signOut, retry }), [state, signedIn, signOut, retry])
  return <AuthContext value={value}>{children}</AuthContext>
}

async function boot(): Promise<AuthState> {
  try {
    const { setupRequired } = await auth.status()
    if (setupRequired) return { status: 'setup' }
    const info = await auth.session()
    setCsrfToken(info.csrfToken)
    return { status: 'signed-in', username: info.username }
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) return { status: 'signed-out' }
    return { status: 'unreachable' }
  }
}

export function useAuth(): AuthContextValue {
  const ctx = use(AuthContext)
  if (!ctx) throw new Error('useAuth outside AuthProvider')
  return ctx
}
