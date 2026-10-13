import { request } from './client'
import type { MutationResult, Note, NoteList, SearchResult } from './types'

export const SEARCH_PAGE = 30

const path = (id: string) => `/notes/${encodeURIComponent(id)}`

export type CreateNote = { operationId: string; id: string; title: string; content: string; conflictOf?: string }
export type UpdateNote = { operationId: string; baseRevision: number; title: string; content: string }

export const notes = {
  list: (cursor?: string, signal?: AbortSignal) =>
    request<NoteList>('GET', `/notes?limit=50${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, undefined, {
      ...(signal && { signal }),
    }),
  get: (id: string) => request<Note>('GET', path(id)),
  create: (body: CreateNote, keepalive = false) => request<MutationResult>('POST', '/notes', body, { keepalive }),
  update: (id: string, body: UpdateNote, keepalive = false) =>
    request<MutationResult>('PUT', path(id), body, { keepalive }),
  remove: (id: string, body: { operationId: string; baseRevision: number }) =>
    request<MutationResult>('DELETE', path(id), body),
  search: (q: string, offset: number, signal: AbortSignal) =>
    request<{ results: SearchResult[] }>(
      'GET',
      `/search?q=${encodeURIComponent(q)}&limit=${SEARCH_PAGE}&offset=${offset}`,
      undefined,
      { signal },
    ),
}
