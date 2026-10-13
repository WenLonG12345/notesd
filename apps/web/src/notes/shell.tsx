import { useCallback, useState } from 'react'
import { navigate, parseRoute, usePathname } from '../router'
import { SettingsSheet } from '../settings/settings'
import { Backdrop } from '../ui/backdrop'
import { Brand } from '../ui/brand'
import { Button } from '../ui/button'
import { ConnectionBanner } from './connection'
import { NoteGrid } from './note-grid'
import { NoteSheet } from './note-sheet'
import type { NoteSaver } from './saver'
import { NotesProvider, useNotes } from './store'
import styles from './shell.module.css'

export function Shell({ username }: { username: string }) {
  return (
    <NotesProvider>
      <ShellView username={username} />
    </NotesProvider>
  )
}

function ShellView({ username }: { username: string }) {
  const route = parseRoute(usePathname())
  const { newDraft } = useNotes()
  // A note the server doesn't have yet: open, but not in the URL until it's saved.
  const [draft, setDraft] = useState<NoteSaver | null>(null)
  const [query, setQuery] = useState('')

  const startNote = () => {
    navigate('/')
    setDraft(newDraft())
  }

  const close = useCallback(() => {
    setDraft(null)
    navigate('/')
  }, [])

  const created = useCallback((id: string) => {
    setDraft(null)
    navigate(`/notes/${id}`, { replace: false })
  }, [])

  return (
    <>
      <Backdrop />
      <div className={styles.app}>
        <header className={styles.header}>
          <div className={styles.bar}>
            <Brand />
            <div className={styles.search}>
              <span className={styles.searchIcon} aria-hidden="true" />
              <label htmlFor="search" className="visually-hidden">
                Search notes
              </label>
              <input
                id="search"
                type="search"
                className={styles.searchInput}
                placeholder="Search notes"
                spellCheck={false}
                autoComplete="off"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Escape') setQuery('')
                }}
              />
            </div>
            <Button variant="accent" size="md" className={styles.newNote} onClick={startNote}>
              <span className={styles.plus} aria-hidden="true">
                +
              </span>
              New note
            </Button>
            <button
              type="button"
              className={styles.account}
              onClick={() => navigate('/settings')}
              aria-label={`Settings for ${username}`}
              title="Settings"
            >
              {(username[0] ?? '·').toUpperCase()}
            </button>
          </div>
        </header>
        <ConnectionBanner />
        <NoteGrid onNew={startNote} query={query} />
      </div>
      <button type="button" className={styles.fab} onClick={startNote} aria-label="New note" title="New note">
        +
      </button>

      {draft ? (
        <NoteSheet key={draft.id} draft={draft} onClose={close} onCreated={created} onOpenDraft={setDraft} />
      ) : route.name === 'note' ? (
        <NoteSheet key={route.id} id={route.id} onClose={close} onCreated={created} onOpenDraft={setDraft} />
      ) : route.name === 'settings' ? (
        <SettingsSheet username={username} onClose={close} />
      ) : null}
    </>
  )
}
