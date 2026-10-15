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
  /** The body is encrypted (§10.1): no preview. */
  locked: boolean
}

export type NoteList = {
  notes: ListItem[]
  nextCursor: string
  hasMore: boolean
  /** Every live note matching the filter, not just this page. */
  total: number
}

/** A tag in live notes (architecture §7.5); parents of nested tags included. */
export type TagCount = {
  key: string
  name: string
  /** Notes with this tag or one nested under it. */
  count: number
}

/** Narrows listing and search to a tag key (with its nested tags) or to untagged notes. */
export type NoteFilter = { tag: string } | { untagged: true } | null

export type SearchResult = {
  id: string
  title: string
  /** HTML-escaped text with matches wrapped in <mark>. */
  excerpt: string
  updatedAt: number
  /** Matched on the title only; the body is encrypted. */
  locked: boolean
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

/** The server's Resend settings for emailing notes; the key itself is never sent back. */
export type EmailSettings = { configured: boolean; from: string; keyHint: string }

/** One wrapped copy of the locked-notes key (§10.1). The server can't open it. */
export type LockKey = {
  id: string
  kind: 'password' | 'recovery' | 'passkey'
  credentialId?: string
  salt: string
  iterations: number
  wrapped: string
  createdAt: number
}
