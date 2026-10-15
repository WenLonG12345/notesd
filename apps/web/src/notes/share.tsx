import { useEffect, useId, useRef, useState, useSyncExternalStore, type FormEvent } from 'react'
import { ApiError } from '../api/client'
import { notes as api } from '../api/notes'
import { genericMessage } from '../auth/errors'
import { Button } from '../ui/button'
import { Dialog } from '../ui/dialog'
import { Field } from '../ui/field'
import { MoreIcon, ShareIcon } from '../ui/icons'
import { lockSession, useLockStatus } from '../locks/session'
import { SetupDialog, UnlockDialog } from '../locks/unlock'
import { useEmailSettings } from './email-settings'
import type { NoteSaver } from './saver'
import styles from './share.module.css'

/** The note as one Markdown file: the title as a heading, then the body. */
function exportMarkdown(saver: NoteSaver): string {
  const { title, content } = saver.fields()
  return title.trim() ? `# ${title.trim()}\n\n${content}` : content
}

/**
 * The note's menu: Email (when Settings has a Resend key), the browser's share
 * sheet where there is one, and Copy as Markdown. Browsers can't add our own
 * action to their share sheet, hence a menu. With [onDelete] it's the desktop's
 * one "More" menu and ends with Delete; phones keep Delete as its own button.
 * Lock note / Remove lock sit there too; a locked note can't be emailed, since
 * the server only has its ciphertext.
 */
export function NoteMenu({ saver, mobile, onDelete }: { saver: NoteSaver; mobile: boolean; onDelete?: () => void }) {
  const name = onDelete ? 'More' : 'Share'
  const email = useEmailSettings()
  const [open, setOpen] = useState(false)
  const [emailing, setEmailing] = useState(false)
  const [copied, setCopied] = useState(false)
  const [locking, setLocking] = useState<'setup' | 'unlock' | null>(null)
  const { locked } = useSyncExternalStore(saver.subscribe, saver.getSnapshot)
  const lockStatus = useLockStatus()
  const menuId = useId()
  const anchor = useRef<HTMLSpanElement>(null)
  const canShare = typeof navigator.share === 'function'

  useEffect(() => {
    if (!open) return
    const close = (e: Event) => {
      if (!anchor.current?.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', close)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  useEffect(() => {
    if (!copied) return
    const t = setTimeout(() => {
      setCopied(false)
      setOpen(false)
    }, 1200)
    return () => clearTimeout(t)
  }, [copied])

  return (
    <span ref={anchor} className={styles.anchor}>
      <button
        type="button"
        className={mobile ? styles.buttonMobile : styles.button}
        aria-label={name}
        title={name}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={() => setOpen((v) => !v)}
      >
        {onDelete ? <MoreIcon /> : <ShareIcon size={mobile ? 19 : 16} />}
      </button>
      {open && (
        <div id={menuId} role="menu" aria-label={name} className={styles.menu}>
          {email?.configured && !locked && (
            <button
              type="button"
              role="menuitem"
              className={styles.item}
              onClick={() => {
                setOpen(false)
                setEmailing(true)
              }}
            >
              Email…
            </button>
          )}
          {canShare && (
            <button
              type="button"
              role="menuitem"
              className={styles.item}
              onClick={() => {
                setOpen(false)
                const { title } = saver.fields()
                // Dismissing the sheet rejects; nothing to report.
                navigator.share({ title: title.trim(), text: exportMarkdown(saver) }).catch(() => undefined)
              }}
            >
              Share…
            </button>
          )}
          <button
            type="button"
            role="menuitem"
            className={styles.item}
            onClick={() => {
              void navigator.clipboard.writeText(exportMarkdown(saver)).then(() => setCopied(true))
            }}
          >
            {copied ? 'Copied' : 'Copy as Markdown'}
          </button>
          <div role="separator" className={styles.separator} />
          <button
            type="button"
            role="menuitem"
            className={styles.item}
            onClick={() => {
              setOpen(false)
              if (locked) saver.setLocked(false)
              else if (lockSession.key()) saver.setLocked(true)
              else setLocking(lockStatus === 'none' ? 'setup' : 'unlock')
            }}
          >
            {locked ? 'Remove lock' : 'Lock note'}
          </button>
          {onDelete && (
            <>
              <div role="separator" className={styles.separator} />
              <button
                type="button"
                role="menuitem"
                className={[styles.item, styles.danger].join(' ')}
                onClick={() => {
                  setOpen(false)
                  onDelete()
                }}
              >
                Delete note
              </button>
            </>
          )}
        </div>
      )}
      {locking === 'setup' && (
        <SetupDialog
          onClose={() => setLocking(null)}
          onDone={() => {
            setLocking(null)
            saver.setLocked(true)
          }}
        />
      )}
      {locking === 'unlock' && (
        <UnlockDialog
          onClose={() => setLocking(null)}
          onUnlocked={() => {
            setLocking(null)
            saver.setLocked(true)
          }}
        />
      )}
      {emailing && email?.configured && (
        <EmailDialog saver={saver} from={email.from} onClose={() => setEmailing(false)} />
      )}
    </span>
  )
}

function EmailDialog({ saver, from, onClose }: { saver: NoteSaver; from: string; onClose: () => void }) {
  const [to, setTo] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [sentTo, setSentTo] = useState<string[] | null>(null)
  const titleId = useId()

  async function send(e: FormEvent) {
    e.preventDefault()
    const recipients = to
      .split(/[,;\s]+/)
      .map((a) => a.trim())
      .filter(Boolean)
    if (recipients.length === 0) {
      setError('Add at least one address.')
      return
    }
    setError(undefined)
    setBusy(true)
    try {
      const revision = await saver.savedRevision()
      if (revision === null) {
        setError('This note isn’t saved on the server yet. Send it once it shows Saved.')
        return
      }
      await api.email(saver.id, recipients, revision)
      setSentTo(recipients)
    } catch (err) {
      setError(sendError(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog labelledBy={titleId} onClose={onClose}>
      {sentTo ? (
        <div className={styles.stack}>
          <h2 id={titleId} className={styles.title}>
            Sent
          </h2>
          <p role="status" className={styles.text}>
            Sent to {sentTo.join(', ')}.
          </p>
          <div className={styles.actions}>
            <Button variant="solid" size="md" onClick={onClose}>
              Done
            </Button>
          </div>
        </div>
      ) : (
        <form className={styles.stack} onSubmit={(e) => void send(e)} noValidate>
          <h2 id={titleId} className={styles.title}>
            Email this note
          </h2>
          <Field
            label="To"
            type="email"
            multiple
            autoComplete="email"
            placeholder="name@example.com"
            value={to}
            onChange={(e) => {
              setTo(e.target.value)
              setError(undefined)
            }}
            error={error}
            hint={`From ${from}. Separate addresses with commas.`}
            autoFocus
          />
          <div className={styles.actions}>
            <Button size="md" onClick={onClose} disabled={busy}>
              Cancel
            </Button>
            <Button variant="solid" size="md" type="submit" busy={busy}>
              {busy ? 'Sending…' : 'Send'}
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}

function sendError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.code === 'stale_revision') return 'This note just changed on another device. Wait for it to update, then send again.'
    if (err.code === 'email_not_configured') return 'Email isn’t set up anymore. Add a Resend API key in Settings.'
    if (err.code === 'email_failed') return `Resend didn’t send it: ${err.message}`
    if (err.status === 400 && err.field === 'to') return sentence(err.message)
    if (err.status === 404) return 'This note was deleted.'
  }
  return genericMessage(err)
}

function sentence(message: string): string {
  const s = message.charAt(0).toUpperCase() + message.slice(1)
  return s.endsWith('.') ? s : `${s}.`
}
