export type Note = {
  id: string
  title: string
  content: string
  revision: number
  changeSeq: number
  /** Unix milliseconds, like every timestamp from the API. */
  createdAt: number
  updatedAt: number
  deletedAt: number | null
  conflictOf?: string
}

export type ListItem = Pick<Note, 'id' | 'title' | 'revision' | 'createdAt' | 'updatedAt'> & {
  preview: string
}

export type NoteList = {
  notes: ListItem[]
  nextCursor: string
  hasMore: boolean
  /** Every live note, not just this page. */
  total: number
}

export type SearchResult = {
  id: string
  title: string
  /** HTML-escaped text with matches wrapped in <mark>. */
  excerpt: string
  updatedAt: number
}

export type MutationResult = {
  id: string
  status: 'created' | 'updated' | 'unchanged' | 'deleted' | 'already_deleted'
  revision: number
  changeSeq: number
  updatedAt: number
  deletedAt?: number
}

export type Session = {
  id: string
  kind: 'web' | 'device'
  name: string
  createdAt: number
  lastUsedAt: number
}

export type SessionInfo = { session: Session; username: string; csrfToken: string }

export type ErrorBody = {
  error: string
  message?: string
  field?: string
  current?: Note
}
