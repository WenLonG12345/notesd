import type { Editor } from '@tiptap/core'
import { useEffect, useId, useLayoutEffect, useRef, useState, useSyncExternalStore, type ReactNode } from 'react'
import { ApiError } from '../api/client'
import { notes as api } from '../api/notes'
import type { Note } from '../api/types'
import { ChecklistTool, IndentTools, LinkForm, LinkTool, MarkTools, NoteContent, TextStyles, Tool, replaceNote, useNoteEditor } from '../editor/editor'
import { noteFields, noteSummary, titleOf } from '../editor/note-doc'
import { Banner } from '../ui/banner'
import { Button } from '../ui/button'
import { ComposeIcon, LockIcon, TrashIcon } from '../ui/icons'
import { LockedError } from '../locks/crypto'
import { lockSession, useLockStatus } from '../locks/session'
import { UnlockForm } from '../locks/unlock'
import { StatusPill } from '../ui/status-pill'
import { offlineNotice, useStatusShown } from './save-status'
import { openNote, type NoteSaver } from './saver'
import { NoteMenu } from './share'
import { useNotes } from './store'
import { tagKey } from './tags'
import styles from './note-pane.module.css'

type Props = {
  /** An existing note, loaded by id… */
  id?: string | undefined
  /** …or a new one the server doesn't have yet. */
  draft?: NoteSaver | undefined
  mobile: boolean
  onBack: () => void
  onNew: () => void
  /** The draft reached the server; it can go in the URL now. */
  onCreated: (id: string) => void
  /** Open another not-yet-saved note (a recovered copy). */
  onOpenDraft: (saver: NoteSaver) => void
  onDelete: (saver: NoteSaver) => void
  /** The note is gone for good (deleted elsewhere, discarded). */
  onGone: (id: string) => void
}

type Load =
  | { kind: 'loading' }
  | { kind: 'missing' }
  | { kind: 'failed' }
  /** A locked note, and locked notes are locked: only its title is readable. */
  | { kind: 'locked'; note: Note }
  | { kind: 'ready'; saver: NoteSaver }

export function NotePane({ id, draft, mobile, onBack, onNew, onCreated, onOpenDraft, onDelete, onGone }: Props) {
  const { existingSaver, saverFor, release, removed } = useNotes()
  const lockStatus = useLockStatus()
  const [load, setLoad] = useState<Load>(() => {
    const ready = draft ?? (id ? existingSaver(id) : undefined)
    // A locked note's saver holds plain text: only show it while unlocked.
    return ready && !(ready.isLocked() && !lockSession.key()) ? { kind: 'ready', saver: ready } : { kind: 'loading' }
  })

  // Locked notes locked again: the open note closes (during render, so its
  // text is never shown again) and reloads as a locked placeholder.
  if (load.kind === 'ready' && load.saver.isLocked() && lockStatus !== 'unlocked' && id) setLoad({ kind: 'loading' })

  useEffect(() => {
    if (load.kind !== 'loading' || !id) return
    let cancelled = false
    api.get(id).then(
      async (stored) => {
        let opened
        try {
          opened = await openNote(stored)
        } catch (err) {
          if (!cancelled) setLoad(err instanceof LockedError ? { kind: 'locked', note: stored } : { kind: 'failed' })
          return
        }
        if (cancelled) return
        const { note, locked, envelope } = opened
        const saver = saverFor({
          id: note.id,
          fields: { title: note.title, content: note.content },
          revision: note.revision,
          updatedAt: note.updatedAt,
          locked,
          envelope,
        })
        setLoad({ kind: 'ready', saver })
      },
      (err: unknown) => {
        if (cancelled) return
        if (err instanceof ApiError && (err.status === 404 || err.status === 410)) {
          // Gone from the server: drop any stale row too.
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
  // Leaving the note: save what's left, then let the store forget it.
  useEffect(() => {
    if (!saver) return
    return () => void saver.flush().finally(() => release(saver.id))
  }, [saver, release])

  return (
    <section className={styles.pane} aria-label="Note">
      {saver ? (
        <NoteEditor
          saver={saver}
          isDraft={draft !== undefined}
          mobile={mobile}
          onBack={onBack}
          onNew={onNew}
          onCreated={onCreated}
          onDelete={() => onDelete(saver)}
          onGone={() => onGone(saver.id)}
          onOpenDraft={onOpenDraft}
        />
      ) : (
        <>
          <div className={styles.bar}>
            {mobile && <BackButton onClick={onBack} />}
          </div>
          <div className={styles.placeholder}>
            {load.kind === 'loading' && <span className={styles.muted}>Loading…</span>}
            {load.kind === 'missing' && (
              <>
                <h2 className={styles.placeholderTitle}>This note doesn’t exist.</h2>
                <span className={styles.muted}>It may have been deleted on another device.</span>
              </>
            )}
            {load.kind === 'locked' && (
              <>
                <span className={styles.lockMark}>
                  <LockIcon size={24} />
                </span>
                <h2 className={styles.placeholderTitle}>{load.note.title.trim() || 'Locked note'}</h2>
                {lockStatus === 'none' || lockStatus === 'unlocked' ? (
                  // The keyring was reset since this note was locked: its key is gone.
                  <span className={styles.muted}>This note was locked with a password that was reset. It can’t be opened.</span>
                ) : (
                  <>
                    <span className={styles.muted}>This note is locked.</span>
                    <UnlockForm onUnlocked={() => setLoad({ kind: 'loading' })} autoFocus={!mobile} />
                  </>
                )}
              </>
            )}
            {load.kind === 'failed' && (
              <>
                <h2 className={styles.placeholderTitle}>Couldn’t load this note.</h2>
                <Button size="sm" onClick={() => setLoad({ kind: 'loading' })}>
                  Try again
                </Button>
              </>
            )}
          </div>
        </>
      )}
    </section>
  )
}

/** The list row's title and preview; a locked note's body stays out of the list. */
function summary(saver: NoteSaver, doc: Parameters<typeof noteSummary>[0]) {
  const s = noteSummary(doc)
  return saver.isLocked() ? { ...s, preview: '' } : s
}

function BackButton({ onClick }: { onClick: () => void }) {
  return (
    <button type="button" className={styles.back} onClick={onClick}>
      <span className={styles.chevron} aria-hidden="true">
        ‹
      </span>
      Notes
    </button>
  )
}

type EditorProps = {
  saver: NoteSaver
  isDraft: boolean
  mobile: boolean
  onBack: () => void
  onNew: () => void
  onCreated: (id: string) => void
  onDelete: () => void
  onGone: () => void
  onOpenDraft: (saver: NoteSaver) => void
}

const APP_TITLE = 'notesd'

/** How often the list row follows the typing. */
const TOUCH_MS = 150

function NoteEditor({ saver, isDraft, mobile, onBack, onNew, onCreated, onDelete, onGone, onOpenDraft }: EditorProps) {
  const snap = useSyncExternalStore(saver.subscribe, saver.getSnapshot)
  const { touch, tags, setFilter } = useNotes()
  const [initial] = useState(() => saver.fields())
  const [openedAt] = useState(() => Date.now())
  const [linkOpen, setLinkOpen] = useState(false)
  const [stylesOpen, setStylesOpen] = useState(false)
  const touchTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const stylesId = useId()
  const popover = useRef<HTMLDivElement>(null)

  const editor = useNoteEditor({
    initial,
    onChange: (ed) => {
      saver.edit()
      touchTimer.current ??= setTimeout(() => {
        touchTimer.current = undefined
        if (!ed.isDestroyed) touch(saver.id, summary(saver, ed.state.doc))
      }, TOUCH_MS)
    },
    onLinkShortcut: () => {
      setStylesOpen(false)
      setLinkOpen(true)
    },
    tags,
    onOpenTag: (name) => {
      const key = tagKey(name)
      setFilter({ tag: key, name: tags.find((t) => t.key === key)?.name ?? name })
      // On a phone the list is a separate screen.
      if (mobile) onBack()
    },
  })

  // Layout effect: its cleanup runs before useEditor destroys the editor,
  // so detaching can still read the final text.
  useLayoutEffect(() => {
    const detach = saver.attach(() => noteFields(editor.state.doc))
    const off = saver.onRemoteChange((next) => {
      replaceNote(editor, next)
      touch(saver.id, summary(saver, editor.state.doc))
    })
    return () => {
      off()
      detach()
    }
  }, [saver, editor, touch])

  useEffect(() => () => clearTimeout(touchTimer.current), [])

  // An installed web app shows this in its title bar and the Window menu. Transactions, not
  // updates: remote changes replace the text without an update event.
  useEffect(() => {
    const show = () => {
      document.title = `${titleOf(editor.state.doc).trim() || 'New note'} — ${APP_TITLE}`
    }
    show()
    editor.on('transaction', show)
    return () => {
      editor.off('transaction', show)
      document.title = APP_TITLE
    }
  }, [editor])

  useEffect(() => {
    if (isDraft) editor.commands.focus('start')
  }, [isDraft, editor])

  useEffect(() => {
    if (isDraft && snap.created) onCreated(saver.id)
  }, [isDraft, snap.created, saver.id, onCreated])

  useEffect(() => {
    if (!stylesOpen) return
    const close = (e: Event) => {
      const target = e.target as Element
      // The Aa button toggles on its own.
      if (popover.current?.contains(target) || target.closest?.(`[aria-controls="${stylesId}"]`)) return
      setStylesOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      setStylesOpen(false)
      editor.commands.focus()
    }
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', close)
      document.removeEventListener('keydown', onKey)
    }
  }, [stylesOpen, stylesId, editor])

  const toggleLink = () => {
    setStylesOpen(false)
    setLinkOpen((v) => !v)
  }
  const toggleStyles = () => {
    setLinkOpen(false)
    setStylesOpen((v) => !v)
  }

  const problem = snap.problem
  const statusShown = useStatusShown(snap)
  // A 401 or a refusal also reads "Couldn't save"; the tooltip says which.
  const statusDetail =
    snap.state !== 'failed'
      ? undefined
      : problem?.kind === 'rejected'
        ? 'The server didn’t accept this save'
        : problem?.kind === 'unlock'
          ? 'Unlock locked notes to save'
          : problem === null
            ? 'Waiting for you to sign in again'
            : undefined
  const edited = snap.created && snap.updatedAt !== null ? snap.updatedAt : openedAt

  return (
    <>
      <div className={styles.bar}>
        {mobile && <BackButton onClick={onBack} />}
        <span className={styles.spacer} />
        <StatusPill state={snap.state} shown={statusShown} detail={statusDetail} />
        {snap.locked && (
          <button
            type="button"
            className={mobile ? styles.lockNowMobile : styles.lockNow}
            onClick={() => void lockSession.lock()}
            aria-label="Lock now"
            title="Locked note. Lock now"
          >
            <LockIcon size={mobile ? 19 : 16} />
          </button>
        )}
        {mobile ? <NoteMenu saver={saver} mobile /> : <NoteMenu saver={saver} mobile={false} onDelete={onDelete} />}
        {mobile && <DeleteButton onClick={onDelete} />}
      </div>

      {/* Phones keep the link field up top: a bottom one would sit under the keyboard. */}
      {linkOpen && mobile && (
        <div className={styles.linkRow}>
          <LinkForm editor={editor} onDone={() => setLinkOpen(false)} />
        </div>
      )}

      {offlineNotice(snap) && (
        <Notice
          tone="error"
          actions={
            <Button variant="solid" size="sm" onClick={() => saver.retryNow()}>
              Retry
            </Button>
          }
        >
          Couldn’t reach the server. Changes are kept here and retried.
        </Notice>
      )}
      {problem?.kind === 'rejected' && (
        <Notice
          tone="error"
          actions={
            <Button variant="solid" size="sm" onClick={() => saver.retryNow()}>
              Retry
            </Button>
          }
        >
          The server didn’t accept this save: {problem.message}
        </Notice>
      )}
      {problem?.kind === 'conflict' && (
        <Notice
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
        </Notice>
      )}
      {problem?.kind === 'deleted' && (
        <Notice
          tone="warn"
          actions={
            <>
              <Button size="sm" onClick={onGone}>
                Discard
              </Button>
              <Button
                variant="solid"
                size="sm"
                onClick={() => {
                  const copy = saver.recover()
                  if (!copy) return
                  onGone()
                  onOpenDraft(copy)
                }}
              >
                Keep as new note
              </Button>
            </>
          }
        >
          This note was deleted on another device.
        </Notice>
      )}

      <div className={styles.scroller}>
        <div className={styles.page}>
          <div className={styles.date}>{formatEdited(edited)}</div>
          <NoteContent editor={editor} />
        </div>
      </div>

      {linkOpen && !mobile ? (
        <div className={[styles.dock, styles.dockLink].join(' ')}>
          <LinkForm editor={editor} onDone={() => setLinkOpen(false)} />
        </div>
      ) : (
        <div className={styles.dock} role="toolbar" aria-label="Formatting">
          {mobile ? (
            <>
              <ChecklistTool editor={editor} className={styles.bottomTool} iconSize={20} />
              <StylesButton editor={editor} id={stylesId} open={stylesOpen} onToggle={toggleStyles} mobile />
              <IndentTools editor={editor} className={styles.bottomTool} iconSize={19} />
              <LinkTool editor={editor} open={linkOpen} onToggle={toggleLink} className={styles.bottomTool} iconSize={19} />
              <button type="button" className={styles.bottomTool} onClick={onNew} aria-label="New note" title="New note">
                <ComposeIcon />
              </button>
            </>
          ) : (
            <>
              <StylesButton editor={editor} id={stylesId} open={stylesOpen} onToggle={toggleStyles} />
              <MarkTools editor={editor} />
              <span className={styles.sep} aria-hidden="true" />
              <ChecklistTool editor={editor} />
              <IndentTools editor={editor} />
              <LinkTool editor={editor} open={linkOpen} onToggle={toggleLink} />
            </>
          )}
        </div>
      )}

      {stylesOpen && (
        <div ref={popover} className={styles.popover}>
          <TextStyles
            editor={editor}
            id={stylesId}
            onApplied={() => {
              if (mobile) setStylesOpen(false)
            }}
          />
        </div>
      )}
    </>
  )
}

function StylesButton({ editor, id, open, onToggle, mobile = false }: { editor: Editor; id: string; open: boolean; onToggle: () => void; mobile?: boolean }) {
  return (
    <Tool
        label="Text styles"
        on={open}
        expanded={open}
        controls={id}
        className={[styles.aa, mobile && styles.bottomTool].filter(Boolean).join(' ')}
        run={() => {
          if (!editor.isFocused) editor.commands.focus()
          onToggle()
        }}
      >
      <span className={styles.aaBig}>A</span>a
    </Tool>
  )
}

function DeleteButton({ onClick }: { onClick: () => void }) {
  return (
    <button type="button" className={styles.deleteMobile} onClick={onClick} aria-label="Delete note" title="Delete note">
      <TrashIcon size={18} />
    </button>
  )
}

function Notice({ tone, actions, children }: { tone: 'error' | 'warn'; actions: ReactNode; children: ReactNode }) {
  return (
    <div className={styles.notice}>
      <Banner tone={tone} actions={actions}>
        {children}
      </Banner>
    </div>
  )
}

function formatEdited(ts: number): string {
  return new Date(ts).toLocaleString(undefined, {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  })
}
