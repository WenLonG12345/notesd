import { request } from './client'
import type { MutationResult, Note, NoteFilter, NoteList, SearchResult, TagCount } from './types'

export const SEARCH_PAGE = 30

const path = (id: string) => `/notes/${encodeURIComponent(id)}`

const filterParams = (filter: NoteFilter) =>
  !filter ? '' : 'tag' in filter ? `&tag=${encodeURIComponent(filter.tag)}` : 'locked' in filter ? '&locked=true' : '&untagged=true'

/** imageIds lists a locked note's images (§7.6); the server reads a plain note's from its content. */
export type CreateNote = {
  operationId: string
  id: string
  title: string
  content: string
  conflictOf?: string
  imageIds?: string[]
}
export type UpdateNote = { operationId: string; baseRevision: number; title: string; content: string; imageIds?: string[] }

export const notes = {
  list: (filter: NoteFilter, cursor?: string, signal?: AbortSignal) =>
    request<NoteList>(
      'GET',
      `/notes?limit=50${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}${filterParams(filter)}`,
      undefined,
      { ...(signal && { signal }) },
    ),
  get: (id: string) => request<Note>('GET', path(id)),
  create: (body: CreateNote, keepalive = false) => request<MutationResult>('POST', '/notes', body, { keepalive }),
  update: (id: string, body: UpdateNote, keepalive = false) =>
    request<MutationResult>('PUT', path(id), body, { keepalive }),
  remove: (id: string, body: { operationId: string; baseRevision: number }, keepalive = false) =>
    request<MutationResult>('DELETE', path(id), body, { keepalive }),
  search: (q: string, filter: NoteFilter, offset: number, signal: AbortSignal) =>
    request<{ results: SearchResult[] }>(
      'GET',
      `/search?q=${encodeURIComponent(q)}&limit=${SEARCH_PAGE}&offset=${offset}${filterParams(filter)}`,
      undefined,
      { signal },
    ),
  /** Emails the server's copy, which must be at revision (409 stale_revision otherwise). */
  email: (id: string, to: string[], revision: number) =>
    request<{ id: string }>('POST', `${path(id)}/email`, { to, revision }),
  filters: (signal?: AbortSignal) => request<{ tags: TagCount[]; locked: number }>('GET', '/filters', undefined, { ...(signal && { signal }) }),
}
