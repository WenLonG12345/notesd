// Before first paint, well ahead of the bundle: index.html's shell frame in the right theme, signed-in visits only.
// A file because the CSP blocks inline scripts. Keys from theme.ts, session-hint.ts, shell.tsx.
try {
  const html = document.documentElement
  const theme = localStorage.getItem('notes.theme')
  if (theme === 'light' || theme === 'dark') html.dataset.theme = theme
  if (localStorage.getItem('notes.signed-in') === 'true') html.dataset.session = ''
  if (localStorage.getItem('notes.sidebar.hidden') === 'true') html.dataset.sidebarHidden = ''
} catch {
  // Storage blocked: no frame, the app draws itself once loaded.
}
