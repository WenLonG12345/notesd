import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { ApiError } from '../api/client'
import { notes as api } from '../api/notes'
import { NoteBody, type NoteBodyHandle } from '../editor/editor'
import { Banner } from '../ui/banner'
import { Button } from '../ui/button'
import { Dialog } from '../ui/dialog'
import { Sheet } from '../ui/sheet'
import { StatusPill } from '../ui/status-pill'
import { relativeTime } from './relative-time'
import type { Fields, NoteSaver } from './saver'
import { useNotes } from './store'
import styles from './note-sheet.module.css'

type Props = {
  /** An existing note, loaded by id… */
  id?: string
  /** …or a new one the server doesn't have yet. */
  draft?: NoteSaver
  onClose: () => void
  /** The draft reached the server; it can go in the URL now. */
  onCreated: (id: string) => void
  /** Open another not-yet-saved note (a recovered copy). */
  onOpenDraft: (saver: NoteSaver) => void
}

type Load = { kind: 'loading' } | { kind: 'missing' } | { kind: 'failed' } | { kind: 'ready'; saver: NoteSaver }

export function NoteSheet({ id, draft, onClose, onCreated, onOpenDraft }: Props) {
  const { existingSaver, saverFor, release, removed } = useNotes()
  const [load, setLoad] = useState<Load>(() => {
    const ready = draft ?? (id ? existingSaver(id) : undefined)
    return ready ? { kind: 'ready', saver: ready } : { kind: 'loading' }
  })

  useEffect(() => {
    if (load.kind !== 'loading' || !id) return
    let cancelled = false
    api.get(id).then(
      (note) => {
        if (cancelled) return
        const saver = saverFor({
          id: note.id,
          fields: { title: note.title, content: note.content },
          revision: note.revision,
          updatedAt: note.updatedAt,
        })
        setLoad({ kind: 'ready', saver })
      },
      (err: unknown) => {
        if (cancelled) return
        if (err instanceof ApiError && (err.status === 404 || err.status === 410)) {
          // Gone from the server: drop any stale tile too.
          removed(id)
          setLoad({ kind: 'missing' })
        } else setLoad({ kind: 'failed' })
      },
    )
    return () => {
      cancelled = true
    }
  }, [id, load.kind, saverFor, removed])

  const saver = load.kind === 'ready' ? load.saver : null
  const close = () => {
    if (saver) {
      void saver.flush().finally(() => release(saver.id))
    }
    onClose()
  }

  return (
    <Sheet label="Edit note" onClose={close}>
      {saver ? (
        <Editor
          saver={saver}
          isDraft={draft !== undefined}
          onClose={close}
          onCreated={onCreated}
          onDeleted={() => {
            removed(saver.id)
            onClose()
          }}
          onOpenDraft={onOpenDraft}
        />
      ) : (
        <>
          <div className={styles.header}>
            <BackButton onClick={close} />
          </div>
          <div className={styles.placeholder}>
            {load.kind === 'loading' && <span className={styles.muted}>Loading…</span>}
            {load.kind === 'missing' && (
              <>
                <h1 className={styles.placeholderTitle}>This note doesn’t exist.</h1>
                <span className={styles.muted}>It may have been deleted on another device.</span>
              </>
            )}
            {load.kind === 'failed' && (
              <>
                <h1 className={styles.placeholderTitle}>Couldn’t load this note.</h1>
                <Button
                  size="sm"
                  onClick={() => setLoad({ kind: 'loading' })}
                >
                  Try again
                </Button>
              </>
            )}
          </div>
        </>
      )}
    </Sheet>
  )
}

function BackButton({ onClick }: { onClick: () => void }) {
  return (
    <Button variant="ghost" size="md" className={styles.back} onClick={onClick}>
      <span className={styles.chevron} aria-hidden="true">
        ‹
      </span>
      <span className={styles.backWide}>All notes</span>
      <span className={styles.backNarrow}>Notes</span>
    </Button>
  )
}

type EditorProps = {
  saver: NoteSaver
  isDraft: boolean
  onClose: () => void
  onCreated: (id: string) => void
  onDeleted: () => void
  onOpenDraft: (saver: NoteSaver) => void
}

function Editor({ saver, isDraft, onClose, onCreated, onDeleted, onOpenDraft }: EditorProps) {
  const snap = useSyncExternalStore(saver.subscribe, saver.getSnapshot)
  const [initial] = useState<Fields>(() => saver.fields())
  const [title, setTitle] = useState(initial.title)
  // The saver reads the title from here and the body from the editor, at save time.
  const liveTitle = useRef(initial.title)
  const titleRef = useRef<HTMLInputElement>(null)
  const bodyRef = useRef<NoteBodyHandle>(null)
  const finalBody = useRef<string | null>(null)
  const [confirmDelete, setConfirmDelete] = useState<'ask' | 'changed' | null>(null)
  const [deleteError, setDeleteError] = useState<string>()
  const [deleting, setDeleting] = useState(false)

  useEffect(() => {
    const detach = saver.attach(() => ({
      title: liveTitle.current,
      content: bodyRef.current?.markdown() ?? finalBody.current ?? initial.content,
    }))
    const off = saver.onRemoteChange((next) => {
      liveTitle.current = next.title
      setTitle(next.title)
      bodyRef.current?.replace(next.content)
    })
    return () => {
      off()
      detach()
    }
  }, [saver, initial.content])

  useEffect(() => {
    if (isDraft) titleRef.current?.focus()
  }, [isDraft])

  useEffect(() => {
    if (isDraft && snap.created) onCreated(saver.id)
  }, [isDraft, snap.created, saver.id, onCreated])


  const runDelete = async () => {
    setDeleting(true)
    setDeleteError(undefined)
    try {
      const outcome = await saver.delete()
      if (outcome === 'deleted') onDeleted()
      else setConfirmDelete('changed')
    } catch {
      setDeleteError('Couldn’t delete the note. Check your connection and try again.')
    } finally {
      setDeleting(false)
    }
  }

  const problem = snap.problem
  const meta =
    snap.created && snap.updatedAt !== null ? `Edited ${relativeTime(snap.updatedAt).toLowerCase()}` : 'New note'

  return (
    <>
      <div className={styles.header}>
        <BackButton onClick={onClose} />
        <div className={styles.spacer} />
        <StatusPill state={snap.state} />
        <Button variant="danger-text" size="md" onClick={() => setConfirmDelete('ask')}>
          Delete
        </Button>
      </div>

      {problem?.kind === 'offline' && (
        <div className={styles.banner}>
          <Banner
            tone="error"
            actions={
              <Button variant="solid" size="sm" onClick={() => saver.retryNow()}>
                Retry
              </Button>
            }
          >
            Couldn’t reach the server. Your changes are kept here and will be retried.
          </Banner>
        </div>
      )}
      {problem?.kind === 'rejected' && (
        <div className={styles.banner}>
          <Banner
            tone="error"
            actions={
              <Button variant="solid" size="sm" onClick={() => saver.retryNow()}>
                Retry
              </Button>
            }
          >
            The server didn’t accept this save: {problem.message}
          </Banner>
        </div>
      )}
      {problem?.kind === 'conflict' && (
        <div className={styles.banner}>
          <Banner
            tone="warn"
            actions={
              <>
                <Button size="sm" onClick={() => saver.takeTheirs()}>
                  Use theirs
                </Button>
                <Button variant="solid" size="sm" onClick={() => saver.keepMine()}>
                  Keep mine
                </Button>
              </>
            }
          >
            Changed on another device while you were editing. “Keep mine” saves your version as a separate note.
          </Banner>
        </div>
      )}
      {problem?.kind === 'deleted' && (
        <div className={styles.banner}>
          <Banner
            tone="warn"
            actions={
              <>
                <Button size="sm" onClick={onDeleted}>
                  Discard
                </Button>
                <Button
                  variant="solid"
                  size="sm"
                  onClick={() => {
                    const copy = saver.recover()
                    if (!copy) return
                    onDeleted()
                    onOpenDraft(copy)
                  }}
                >
                  Keep as new note
                </Button>
              </>
            }
          >
            This note was deleted on another device.
          </Banner>
        </div>
      )}

      <div className={styles.titleBlock}>
        <label htmlFor="note-title" className="visually-hidden">
          Title
        </label>
        <input
          id="note-title"
          ref={titleRef}
          className={styles.title}
          value={title}
          placeholder="Title"
          spellCheck={false}
          autoComplete="off"
          onChange={(e) => {
            liveTitle.current = e.target.value
            setTitle(e.target.value)
            saver.edit()
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === 'ArrowDown') {
              e.preventDefault()
              bodyRef.current?.focusStart()
            }
          }}
        />
        <div className={styles.meta}>{meta}</div>
      </div>

      <NoteBody
        ref={bodyRef}
        initialMarkdown={initial.content}
        onChange={() => saver.edit()}
        onExitToTitle={() => titleRef.current?.focus()}
        onUnmount={(markdown) => {
          finalBody.current = markdown
        }}
      />

      {confirmDelete && (
        <Dialog labelledBy="delete-title" onClose={() => setConfirmDelete(null)}>
          <div className={styles.confirm}>
            <h1 id="delete-title">{confirmDelete === 'ask' ? 'Delete this note?' : 'This note just changed'}</h1>
            <p>
              {confirmDelete === 'ask'
                ? 'It’s removed from every device signed in to this server.'
                : 'It was edited on another device; the latest version is shown now. Delete it anyway?'}
            </p>
            {deleteError && (
              <p role="alert" className={styles.error}>
                {deleteError}
              </p>
            )}
            <div className={styles.confirmActions}>
              <Button onClick={() => setConfirmDelete(null)}>Cancel</Button>
              <Button variant="danger" busy={deleting} onClick={() => void runDelete()}>
                Delete note
              </Button>
            </div>
          </div>
        </Dialog>
      )}
    </>
  )
}
