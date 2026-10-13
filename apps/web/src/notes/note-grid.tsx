import { useEffect, useRef, type MouseEvent } from 'react'
import type { ListItem, SearchResult } from '../api/types'
import { navigate } from '../router'
import { Button } from '../ui/button'
import { Kicker } from '../ui/kicker'
import { EmptyNotes } from './empty'
import { relativeTime } from './relative-time'
import { useSearch } from './search'
import { useNotes } from './store'
import styles from './note-grid.module.css'

export function NoteGrid({ onNew, query }: { onNew: () => void; query: string }) {
  const { items, status, hasMore, loadMore, refresh, version } = useNotes()
  const count = items.length
  if (query.trim()) return <SearchResults query={query} version={version} />

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

function SearchResults({ query, version }: { query: string; version: number }) {
  const search = useSearch(query, version)
  const n = search.results.length
  const searching = search.status === 'loading' && n === 0
  return (
    <main className={styles.main}>
      <div className={styles.heading}>
        <h2>Results</h2>
        <span className={styles.count} aria-live="polite">
          {searching ? 'Searching…' : `${n}${search.hasMore ? '+' : ''} ${n === 1 ? 'match' : 'matches'}`}
        </span>
      </div>
      {search.status === 'error' && (
        <div className={styles.message} role="alert">
          <span className={styles.messageTitle}>Search didn’t work.</span>
          <span>Check your connection and try again.</span>
        </div>
      )}
      {search.status === 'ready' && n === 0 && (
        <div className={styles.message}>
          <span className={styles.messageTitle}>Nothing matches “{query.trim()}”.</span>
          <span>Search looks through titles and note text.</span>
        </div>
      )}
      {n > 0 && (
        <ul className={styles.grid} aria-label="Search results">
          {search.results.map((r) => (
            <ResultTile key={r.id} result={r} />
          ))}
        </ul>
      )}
      {search.hasMore && <LoadMore onVisible={search.loadMore} />}
    </main>
  )
}

function ResultTile({ result }: { result: SearchResult }) {
  const title = result.title.trim()
  const href = `/notes/${result.id}`
  return (
    <li className={[styles.tile, result.excerpt.length > 150 && styles.tall].filter(Boolean).join(' ')}>
      <a href={href} className={styles.link} onClick={(e) => followLink(e, href)}>
        <span className={[styles.title, !title && styles.untitled].filter(Boolean).join(' ')}>
          {title || 'Untitled'}
        </span>
        {/* The server escapes the note text and adds only <mark>: the one place raw HTML is allowed. */}
        <span className={styles.preview} dangerouslySetInnerHTML={{ __html: result.excerpt }} />
        <span className={styles.time}>{relativeTime(result.updatedAt)}</span>
      </a>
    </li>
  )
}

function followLink(e: MouseEvent, href: string) {
  if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return
  e.preventDefault()
  navigate(href)
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
        onClick={(e) => followLink(e, href)}
      >
        {feature && <Kicker>Last edited</Kicker>}
        <span className={[styles.title, !title && styles.untitled].filter(Boolean).join(' ')}>
          {title || 'Untitled'}
        </span>
        <span className={styles.preview}>{note.preview ? previewLine(note.preview) : 'No additional text'}</span>
        <span className={styles.time}>{relativeTime(note.updatedAt)}</span>
      </a>
    </li>
  )
}

/** The preview has one line per block; tiles run them together as in the design. */
function previewLine(preview: string): string {
  return preview.split('\n').join(' · ')
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
