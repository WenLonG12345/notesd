export type FontChoice = 'system' | 'sans' | 'serif' | 'book' | 'legible' | 'mono'

const PICKED: string[] = ['system', 'serif', 'book', 'legible', 'mono'] satisfies FontChoice[]

const KEY = 'notes.font'

// A display preference, like the theme (theme.ts): localStorage is fine and survives reloads.
export function readFont(): FontChoice {
  try {
    const v = localStorage.getItem(KEY)
    return v && PICKED.includes(v) ? (v as FontChoice) : 'sans'
  } catch {
    return 'sans'
  }
}

/** The note editor's font (tokens.css reads data-font); the rest of the UI keeps its own. */
export function applyFont(choice: FontChoice) {
  if (choice === 'sans') document.documentElement.removeAttribute('data-font')
  else document.documentElement.dataset.font = choice
  try {
    if (choice === 'sans') localStorage.removeItem(KEY)
    else localStorage.setItem(KEY, choice)
  } catch {
    // Storage blocked (private mode): the choice lasts for this page only.
  }
}
