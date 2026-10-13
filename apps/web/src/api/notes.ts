import { request } from './client'
import type { MutationResult, Note, NoteList } from './types'

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
}
