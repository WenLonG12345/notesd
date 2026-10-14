/**
 * The token from the setup link the server logs on first run (/setup#TOKEN).
 * Taken off the address bar straight away so it isn't left in history or
 * bookmarks.
 */
export function takeSetupLink(): string | undefined {
  const { pathname, hash } = window.location
  if (pathname !== '/setup') return undefined
  window.history.replaceState(null, '', '/')
  let token = ''
  try {
    token = decodeURIComponent(hash.slice(1)).trim()
  } catch {
    // Mangled when copied: fall back to pasting the token.
  }
  return token || undefined
}
