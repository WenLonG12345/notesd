const KEY = 'notes.signed-in'

// Only says the last visit ended signed in, so boot can draw the shell before
// the server confirms. Not a credential.
export function hadSession(): boolean {
  try {
    return localStorage.getItem(KEY) === 'true'
  } catch {
    return false
  }
}

export function rememberSession(signedIn: boolean) {
  try {
    if (signedIn) localStorage.setItem(KEY, 'true')
    else localStorage.removeItem(KEY)
  } catch {
    // Storage blocked: boot just shows nothing until the server answers.
  }
}
