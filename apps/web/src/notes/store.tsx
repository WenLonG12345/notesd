import { createContext, use, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { notes as api } from '../api/notes'
import type { ListItem } from '../api/types'
import { useAuth } from '../auth/auth-context'
import { preview as previewOf } from './plaintext'
import { NoteSaver, type Fields } from './saver'

type ListState = {
  items: ListItem[]
  status: 'loading' | 'ready' | 'error'
  hasMore: boolean
}

type NotesContextValue = ListState & {
  loadMore: () => void
  refresh: () => void
  /** The saver for a note, creating one from server data if needed. */
  saverFor: (init: ConstructorParameters<typeof NoteSaver>[0]) => NoteSaver
  existingSaver: (id: string) => NoteSaver | undefined
  newDraft: () => NoteSaver
  /** Drop a saver that has nothing left to do (closed editor). */
  release: (id: string) => void
  removed: (id: string) => void
}

const NotesContext = createContext<NotesContextValue | null>(null)

/** Other devices may have changed things; refetch on focus after this long. */
const STALE_MS = 30_000

export function NotesProvider({ children }: { children: ReactNode }) {
  const [list, setList] = useState<ListState>({ items: [], status: 'loading', hasMore: false })
  const cursor = useRef('')
  const loadedAt = useRef(0)
  const loading = useRef(false)
  const savers = useRef(new Map<string, NoteSaver>())
  const { state: auth } = useAuth()

  const fetchPage = useCallback(async (reset: boolean) => {
    if (loading.current) return
    loading.current = true
    try {
      const page = await api.list(reset ? undefined : cursor.current)
      cursor.current = page.nextCursor
      loadedAt.current = Date.now()
      setList((prev) => {
        const seen = new Set(page.notes.map((n) => n.id))
        // Keep notes saved locally that the page doesn't include yet.
        const kept = reset ? prev.items.filter((n) => !seen.has(n.id) && savers.current.has(n.id)) : prev.items
        const items = reset ? [...kept, ...page.notes] : [...kept, ...page.notes.filter((n) => !kept.some((k) => k.id === n.id))]
        items.sort((a, b) => b.updatedAt - a.updatedAt)
        return { items, status: 'ready', hasMore: page.hasMore }
      })
    } catch {
      setList((prev) => (prev.status === 'loading' ? { ...prev, status: 'error' } : prev))
    } finally {
      loading.current = false
    }
  }, [])

  useEffect(() => {
    void fetchPage(true)
  }, [fetchPage])

  const upsert = useCallback((id: string, fields: Fields, updatedAt: number, revision: number) => {
    setList((prev) => {
      const old = prev.items.find((n) => n.id === id)
      const item: ListItem = {
        id,
        title: fields.title,
        preview: previewOf(fields.content),
        revision,
        updatedAt,
        createdAt: old?.createdAt ?? updatedAt,
      }
      return { ...prev, items: [item, ...prev.items.filter((n) => n.id !== id)] }
    })
  }, [])

  const hooks = useMemo(
    () => ({
      saved: (id: string, fields: Fields, r: { updatedAt: number; revision: number }) =>
        upsert(id, fields, r.updatedAt, r.revision),
      copied: (copy: NoteSaver) => savers.current.set(copy.id, copy),
    }),
    [upsert],
  )

  const saverFor = useCallback<NotesContextValue['saverFor']>(
    (init) => {
      let s = savers.current.get(init.id)
      if (!s) {
        s = new NoteSaver(init, hooks)
        savers.current.set(init.id, s)
      }
      return s
    },
    [hooks],
  )

  const existingSaver = useCallback((id: string) => savers.current.get(id), [])

  const newDraft = useCallback(() => {
    const s = new NoteSaver({ id: crypto.randomUUID(), fields: { title: '', content: '' } }, hooks)
    savers.current.set(s.id, s)
    return s
  }, [hooks])

  const release = useCallback((id: string) => {
    const s = savers.current.get(id)
    if (!s || s.isAttached()) return
    // Blank drafts vanish; anything still saving (or stuck) stays in memory.
    if (s.isBlankDraft() || !s.hasUnsavedWork()) {
      s.dispose()
      savers.current.delete(id)
    }
  }, [])

  const removed = useCallback((id: string) => {
    savers.current.get(id)?.dispose()
    savers.current.delete(id)
    setList((prev) => ({ ...prev, items: prev.items.filter((n) => n.id !== id) }))
  }, [])

  // Signed in again after the session ended: send what was waiting.
  const authStatus = auth.status
  useEffect(() => {
    if (authStatus !== 'signed-in') return
    for (const s of savers.current.values()) s.resume()
  }, [authStatus])

  useEffect(() => {
    const all = () => [...savers.current.values()]
    const onFocus = () => {
      for (const s of all()) s.resume()
      if (Date.now() - loadedAt.current > STALE_MS) void fetchPage(true)
    }
    const onOnline = () => {
      for (const s of all()) s.resume()
    }
    const onHidden = () => {
      if (document.visibilityState === 'hidden') for (const s of all()) void s.flush()
    }
    const onUnload = (e: BeforeUnloadEvent) => {
      const pending = all().filter((s) => s.hasUnsavedWork())
      for (const s of pending) s.flushOnUnload()
      if (pending.length > 0) e.preventDefault()
    }
    window.addEventListener('focus', onFocus)
    window.addEventListener('online', onOnline)
    document.addEventListener('visibilitychange', onHidden)
    window.addEventListener('beforeunload', onUnload)
    return () => {
      window.removeEventListener('focus', onFocus)
      window.removeEventListener('online', onOnline)
      document.removeEventListener('visibilitychange', onHidden)
      window.removeEventListener('beforeunload', onUnload)
    }
  }, [fetchPage])

  const value = useMemo<NotesContextValue>(
    () => ({
      ...list,
      loadMore: () => void fetchPage(false),
      refresh: () => void fetchPage(true),
      saverFor,
      existingSaver,
      newDraft,
      release,
      removed,
    }),
    [list, fetchPage, saverFor, existingSaver, newDraft, release, removed],
  )
  return <NotesContext value={value}>{children}</NotesContext>
}

export function useNotes(): NotesContextValue {
  const ctx = use(NotesContext)
  if (!ctx) throw new Error('useNotes outside NotesProvider')
  return ctx
}
