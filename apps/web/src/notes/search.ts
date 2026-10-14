import { useCallback, useEffect, useRef, useState } from 'react'
import { notes as api, SEARCH_PAGE } from '../api/notes'
import type { NoteFilter, SearchResult } from '../api/types'

export type SearchState = {
  status: 'idle' | 'loading' | 'ready' | 'error'
  results: SearchResult[]
  hasMore: boolean
}

type Answer = { q: string; scope: string; key: string; ok: boolean; results: SearchResult[]; hasMore: boolean }

const DEBOUNCE_MS = 150

/**
 * Search-as-you-type within filter. Each new query (or a change to the notes,
 * `version`) aborts the request before it, so a slow stale response can't
 * overwrite a newer one. Results carry the key they answer; anything else is
 * loading.
 */
export function useSearch(query: string, filter: NoteFilter, version: number) {
  const q = query.trim()
  const scope = JSON.stringify(filter)
  const key = `${version}\u0000${scope}\u0000${q}`
  const [answer, setAnswer] = useState<Answer | null>(null)
  const more = useRef<AbortController | null>(null)

  useEffect(() => {
    more.current?.abort()
    more.current = null
    if (!q) return
    const controller = new AbortController()
    const timer = setTimeout(() => {
      api.search(q, JSON.parse(scope) as NoteFilter, 0, controller.signal).then(
        ({ results }) => setAnswer({ q, scope, key, ok: true, results, hasMore: results.length === SEARCH_PAGE }),
        () => {
          if (!controller.signal.aborted) setAnswer({ q, scope, key, ok: false, results: [], hasMore: false })
        },
      )
    }, DEBOUNCE_MS)
    return () => {
      clearTimeout(timer)
      controller.abort()
    }
  }, [q, scope, key])

  const loadMore = useCallback(() => {
    if (more.current || !answer) return
    const controller = new AbortController()
    more.current = controller
    const have = answer.results
    api
      .search(answer.q, JSON.parse(answer.scope) as NoteFilter, have.length, controller.signal)
      .then(
        ({ results }) => {
          if (controller.signal.aborted) return
          setAnswer((a) =>
            a && a.key === answer.key
              ? {
                  ...a,
                  results: [...a.results, ...results.filter((r) => !a.results.some((x) => x.id === r.id))],
                  hasMore: results.length === SEARCH_PAGE,
                }
              : a,
          )
        },
        () => undefined,
      )
      .finally(() => {
        if (more.current === controller) more.current = null
      })
  }, [answer])

  let state: SearchState
  if (!q) state = { status: 'idle', results: [], hasMore: false }
  else if (answer?.key === key) {
    state = { status: answer.ok ? 'ready' : 'error', results: answer.results, hasMore: answer.hasMore }
  } else if (answer?.q === q && answer.scope === scope && answer.ok) {
    // Same words, notes changed: keep showing the old results while refreshing.
    state = { status: 'ready', results: answer.results, hasMore: answer.hasMore }
  } else state = { status: 'loading', results: [], hasMore: false }

  return { ...state, loadMore }
}
