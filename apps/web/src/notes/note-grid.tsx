import { useEffect, useRef } from 'react'
import type { ListItem } from '../api/types'
import { navigate } from '../router'
import { Button } from '../ui/button'
import { Kicker } from '../ui/kicker'
import { EmptyNotes } from './empty'
import { relativeTime } from './relative-time'
import { useNotes } from './store'
import styles from './note-grid.module.css'

export function NoteGrid({ onNew }: { onNew: () => void }) {
  const { items, status, hasMore, loadMore, refresh } = useNotes()
  const count = items.length

  return (
    <main className={styles.main}>
      <div className={styles.heading}>
        <h2>All notes</h2>
        {count > 0 && (
          <span className={styles.count}>
            {count}
            {hasMore ? '+' : ''} {count === 1 ? 'note' : 'notes'}
          </span>
        )}
      </div>

      {status === 'error' && (
        <div className={styles.message} role="alert">
          <span className={styles.messageTitle}>Couldn’t load your notes.</span>
          <span>Check that the server is running.</span>
          <Button size="sm" onClick={refresh}>
            Try again
          </Button>
        </div>
      )}
      {status === 'ready' && count === 0 && <EmptyNotes onNew={onNew} />}
      {count > 0 && (
        <ul className={styles.grid} aria-label="Notes">
          {items.map((n, i) => (
            <Tile key={n.id} note={n} feature={i === 0 && count > 2} />
          ))}
        </ul>
      )}
      {hasMore && <LoadMore onVisible={loadMore} />}
    </main>
  )
}

function Tile({ note, feature }: { note: ListItem; feature: boolean }) {
  const title = note.title.trim()
  const tall = !feature && note.preview.length > 150
  const className = [styles.tile, feature && styles.feature, tall && styles.tall].filter(Boolean).join(' ')
  const href = `/notes/${note.id}`
  return (
    <li className={className}>
      <a
        href={href}
        className={styles.link}
        onClick={(e) => {
          if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return
          e.preventDefault()
          navigate(href)
        }}
      >
        {feature && <Kicker>Last edited</Kicker>}
        <span className={[styles.title, !title && styles.untitled].filter(Boolean).join(' ')}>
          {title || 'Untitled'}
        </span>
        <span className={styles.preview}>{note.preview || 'No additional text'}</span>
        <span className={styles.time}>{relativeTime(note.updatedAt)}</span>
      </a>
    </li>
  )
}

/** Infinite scroll: loads the next page when this scrolls into view. */
function LoadMore({ onVisible }: { onVisible: () => void }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    const io = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) onVisible()
    })
    io.observe(el)
    return () => io.disconnect()
  }, [onVisible])
  return (
    <div ref={ref} className={styles.more}>
      <Button size="sm" onClick={onVisible}>
        Load more
      </Button>
    </div>
  )
}
