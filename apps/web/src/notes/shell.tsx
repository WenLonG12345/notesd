import { useCallback, useEffect, useState } from 'react'
import { navigate, parseRoute, usePathname } from '../router'
import { SettingsSheet } from '../settings/settings'
import { Backdrop } from '../ui/backdrop'
import { PHONE, useMediaQuery } from '../ui/media'
import { Toast } from '../ui/toast'
import { EmptyPane } from './empty'
import { NoteList } from './note-list'
import { NotePane } from './note-pane'
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

type Notice = { id: string; text: string; undo: boolean }

/** Messages after a delete that didn't go through stay up this long. */
const NOTICE_MS = 5000

function ShellView({ username }: { username: string }) {
  const route = parseRoute(usePathname())
  const mobile = useMediaQuery(PHONE)
  const { items, status, newDraft, remove, undoRemove, removed } = useNotes()
  // A note the server doesn't have yet: open, but not in the URL until it's saved.
  const [draft, setDraft] = useState<NoteSaver | null>(null)
  const [query, setQuery] = useState('')
  const [notice, setNotice] = useState<Notice | null>(null)

  // Settings opens over the app; the note behind it stays open.
  const routeNote = route.name === 'note' ? route.id : null
  const [behindSettings, setBehindSettings] = useState(routeNote)
  if (route.name !== 'settings' && routeNote !== behindSettings) setBehindSettings(routeNote)
  const noteId = route.name === 'settings' ? behindSettings : routeNote
  const selectedId = draft?.id ?? noteId

  // Back/forward replaces whatever draft was open.
  useEffect(() => {
    const onPop = () => setDraft(null)
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [])

  // Side by side, the pane always shows a note when there is one.
  const first = items[0]?.id
  useEffect(() => {
    if (mobile || draft || route.name !== 'notes' || status !== 'ready' || !first) return
    navigate(`/notes/${first}`, { replace: true })
  }, [mobile, draft, route.name, status, first])

  useEffect(() => {
    if (!notice || notice.undo) return
    const t = setTimeout(() => setNotice(null), NOTICE_MS)
    return () => clearTimeout(t)
  }, [notice])

  const select = useCallback(
    (id: string) => {
      if (draft?.id === id) return
      setDraft(null)
      navigate(`/notes/${id}`)
    },
    [draft],
  )

  const startNote = () => {
    setQuery('')
    setDraft(newDraft())
  }

  const created = useCallback((id: string) => {
    setDraft(null)
    navigate(`/notes/${id}`)
  }, [])

  /** Where to go when the open note goes away: the next one down, or the list on a phone. */
  const leave = (id: string) => {
    const rest = items.filter((n) => n.id !== id)
    const at = items.findIndex((n) => n.id === id)
    const next = mobile ? undefined : (rest[at] ?? rest[at - 1])
    setDraft(null)
    navigate(next ? `/notes/${next.id}` : '/')
  }

  const deleteNote = (saver: NoteSaver) => {
    const id = saver.id
    leave(id)
    // An untouched new note just disappears (the pane releases it).
    if (saver.isBlankDraft()) return
    setNotice({ id, text: 'Note deleted', undo: true })
    void remove(id).then((outcome) => {
      if (outcome === 'changed') setNotice({ id, text: 'Not deleted: it changed on another device.', undo: false })
      else if (outcome === 'failed') setNotice({ id, text: 'Couldn’t delete the note. Check your connection.', undo: false })
      else setNotice((n) => (n?.id === id ? null : n))
    })
  }

  const undo = (id: string) => {
    undoRemove(id)
    select(id)
  }

  const showList = !mobile || selectedId === null
  const showPane = !mobile || selectedId !== null

  return (
    <>
      <Backdrop />
      <div className={styles.app}>
        {showList && (
          <NoteList
            selectedId={selectedId}
            query={query}
            onQuery={setQuery}
            onSelect={select}
            onNew={startNote}
            onSettings={() => navigate('/settings')}
            mobile={mobile}
          />
        )}
        {showPane &&
          (selectedId ? (
            <NotePane
              key={selectedId}
              id={draft ? undefined : selectedId}
              draft={draft ?? undefined}
              mobile={mobile}
              onBack={() => {
                setDraft(null)
                navigate('/')
              }}
              onNew={startNote}
              onCreated={created}
              onOpenDraft={setDraft}
              onDelete={deleteNote}
              onGone={(id) => {
                leave(id)
                removed(id)
              }}
            />
          ) : (
            <EmptyPane hasNotes={items.length > 0} onNew={startNote} />
          ))}
      </div>

      {notice && <Toast text={notice.text} onUndo={notice.undo ? () => undo(notice.id) : undefined} />}

      {route.name === 'settings' && (
        <SettingsSheet
          username={username}
          onClose={() => navigate(behindSettings ? `/notes/${behindSettings}` : '/')}
        />
      )}
    </>
  )
}
