export type ThemeChoice = 'system' | 'light' | 'dark'

const KEY = 'notes.theme'

// --bg (styles/tokens.css): the window's title bar in Safari and installed web apps.
const BG = { light: '#F5F3F1', dark: '#121416' }

// A display preference, not data: localStorage is fine and survives reloads.
export function readTheme(): ThemeChoice {
  try {
    const v = localStorage.getItem(KEY)
    return v === 'light' || v === 'dark' ? v : 'system'
  } catch {
    return 'system'
  }
}

export function applyTheme(choice: ThemeChoice) {
  if (choice === 'system') document.documentElement.removeAttribute('data-theme')
  else document.documentElement.dataset.theme = choice
  // index.html picks light or dark by media query; a picked theme must win over the system's.
  for (const meta of document.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]')) {
    const system = meta.media.includes('dark') ? 'dark' : 'light'
    meta.content = BG[choice === 'system' ? system : choice]
  }
  try {
    if (choice === 'system') localStorage.removeItem(KEY)
    else localStorage.setItem(KEY, choice)
  } catch {
    // Storage blocked (private mode): the choice lasts for this page only.
  }
}
