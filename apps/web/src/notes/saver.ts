import { ApiError, NetworkError } from '../api/client'
import { notes } from '../api/notes'
import type { MutationResult, Note } from '../api/types'
import type { SaveState } from '../ui/status-pill'

export type Fields = { title: string; content: string }

export type Problem =
  /** Both sides changed the content (or the title) differently. */
  | { kind: 'conflict'; current: Note }
  /** The note was deleted elsewhere; our edits have nowhere to go. */
  | { kind: 'deleted' }
  /** Network or server failure; retrying with backoff. */
  | { kind: 'offline' }
  /** The server rejected the save for good (e.g. note too large). */
  | { kind: 'rejected'; message: string }

export type Snapshot = {
  state: SaveState
  problem: Problem | null
  /** False until the server has the note (new notes POST on first save). */
  created: boolean
  updatedAt: number | null
}

type Pending = { operationId: string; fields: Fields; create: boolean; baseRevision: number }

type Hooks = {
  /** A save landed: update the grid. */
  saved?: (id: string, fields: Fields, result: MutationResult) => void
  /** A new note was created by resolving a conflict. */
  copied?: (saver: NoteSaver) => void
}

// Tests shorten these; nothing else should touch them.
export const timing = { debounceMs: 1500, retryBaseMs: 2000, retryMaxMs: 30_000 }

/** How many bytes a keepalive request may carry (browsers cap the total at 64 KB). */
const KEEPALIVE_LIMIT = 60_000

/**
 * Owns saving for one note, independent of React so saves outlive the editor
 * (closing the sheet, the session-expired dialog). Saves are serialized: one
 * request in flight, and edits made meanwhile go out afterwards against the
 * revision it returned.
 */
export class NoteSaver {
  readonly id: string
  private conflictOf: string | undefined
  private revision: number
  private created: boolean
  /** Last values the server acknowledged: the base for §6.1 merges. */
  private base: Fields
  /** The editor's current values; read lazily so serialization happens per save, not per keystroke. */
  private read: () => Fields
  private dirty = false
  private attached = 0
  private state: SaveState = 'saved'
  private problem: Problem | null = null
  private updatedAt: number | null
  private inflight: Promise<void> | null = null
  /** Kept across retries so a lost response replays instead of applying twice. */
  private pending: Pending | null = null
  private debounce: ReturnType<typeof setTimeout> | undefined
  private retryTimer: ReturnType<typeof setTimeout> | undefined
  private attempts = 0
  private waitingForAuth = false
  private listeners = new Set<() => void>()
  private remoteListeners = new Set<(fields: Fields) => void>()
  private snapshot: Snapshot

  constructor(
    init: { id: string; fields: Fields; revision?: number; updatedAt?: number; conflictOf?: string },
    private hooks: Hooks = {},
  ) {
    this.id = init.id
    this.conflictOf = init.conflictOf
    this.revision = init.revision ?? 0
    this.created = init.revision !== undefined
    this.updatedAt = init.updatedAt ?? null
    // A note the server doesn't have yet has nothing acknowledged: empty base.
    this.base = this.created ? { ...init.fields } : { title: '', content: '' }
    const fields = { ...init.fields }
    this.read = () => fields
    this.snapshot = this.makeSnapshot()
  }

  // --- for the editor -------------------------------------------------------

  getSnapshot = (): Snapshot => this.snapshot

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  /** Called when the note's text changes underneath the editor (merge, "Use theirs"). */
  onRemoteChange(listener: (fields: Fields) => void): () => void {
    this.remoteListeners.add(listener)
    return () => this.remoteListeners.delete(listener)
  }

  /** The values the editor should start from. */
  fields(): Fields {
    return this.read()
  }

  /** Lets the editor own the live values. Returns a detach function that freezes them. */
  attach(read: () => Fields): () => void {
    this.read = read
    this.attached++
    return () => {
      this.attached--
      if (this.read !== read) return
      const frozen = read()
      this.read = () => frozen
    }
  }

  /** An editor has this note open; it must not be released. */
  isAttached(): boolean {
    return this.attached > 0
  }

  /** The editor changed something. */
  edit() {
    this.dirty = true
    if (this.problem?.kind === 'conflict' || this.problem?.kind === 'deleted') {
      // Edits pile up locally until the user resolves; the choice uses the latest text.
      return
    }
    if (this.state !== 'saving' && this.problem?.kind !== 'offline') this.set('unsaved')
    clearTimeout(this.debounce)
    this.debounce = setTimeout(() => void this.flush(), timing.debounceMs)
  }

  /** Whether closing the page now could lose something. */
  hasUnsavedWork(): boolean {
    return this.dirty || this.pending !== null || this.inflight !== null
  }

  /** True for a new note nobody typed into: it can be dropped without a trace. */
  isBlankDraft(): boolean {
    const f = this.read()
    return !this.created && !f.title.trim() && !f.content.trim()
  }

  // --- saving ---------------------------------------------------------------

  /** Saves now (skipping the debounce) and resolves when this note is idle. */
  async flush(): Promise<void> {
    clearTimeout(this.debounce)
    if (this.inflight) {
      await this.inflight
      // The in-flight save may have left new edits behind.
      if (this.dirty && !this.blocked()) await this.flush()
      return
    }
    if (this.blocked()) return
    this.inflight = this.run().finally(() => {
      this.inflight = null
    })
    await this.inflight
  }

  /** Retry after a failure, now. */
  retryNow() {
    clearTimeout(this.retryTimer)
    this.waitingForAuth = false
    if (this.problem?.kind === 'rejected') this.problem = null
    this.attempts = 0
    void this.flush()
  }

  /** Signed in again after a 401. */
  resume() {
    if (this.waitingForAuth || this.problem?.kind === 'offline') this.retryNow()
  }

  /** Last-chance save while the page unloads. Fire and forget. */
  flushOnUnload() {
    clearTimeout(this.debounce)
    if (this.blocked() || this.inflight) return
    const p = this.pending ?? this.nextPending()
    if (!p) return
    if (JSON.stringify(p.fields).length > KEEPALIVE_LIMIT) return
    this.pending = p
    void this.send(p, true).catch(() => undefined)
  }

  private blocked(): boolean {
    const k = this.problem?.kind
    return k === 'conflict' || k === 'deleted' || k === 'rejected' || this.waitingForAuth
  }

  private nextPending(): Pending | null {
    const fields = this.read()
    this.dirty = false
    if (this.created && fields.title === this.base.title && fields.content === this.base.content) return null
    if (!this.created && !fields.title.trim() && !fields.content.trim()) return null
    return { operationId: crypto.randomUUID(), fields, create: !this.created, baseRevision: this.revision }
  }

  private send(p: Pending, keepalive = false): Promise<MutationResult> {
    return p.create
      ? notes.create(
          {
            operationId: p.operationId,
            id: this.id,
            ...p.fields,
            ...(this.conflictOf !== undefined && { conflictOf: this.conflictOf }),
          },
          keepalive,
        )
      : notes.update(this.id, { operationId: p.operationId, baseRevision: p.baseRevision, ...p.fields }, keepalive)
  }

  private async run(): Promise<void> {
    for (;;) {
      const p = this.pending ?? this.nextPending()
      if (!p) {
        if (!this.dirty) this.set('saved')
        return
      }
      this.pending = p
      this.set('saving')
      let result: MutationResult
      try {
        // Sequential on purpose: one request per note in flight.
        // oxlint-disable-next-line no-await-in-loop
        result = await this.send(p)
      } catch (err) {
        this.fail(err)
        return
      }
      this.pending = null
      this.attempts = 0
      this.created = true
      this.revision = result.revision
      this.base = p.fields
      this.updatedAt = result.updatedAt
      this.problem = null
      this.hooks.saved?.(this.id, p.fields, result)
      if (!this.dirty) {
        this.set('saved')
        return
      }
    }
  }

  private fail(err: unknown) {
    if (err instanceof ApiError && err.status === 409 && err.code === 'revision_conflict' && err.current) {
      this.pending = null
      // The unsent snapshot is the local side of the merge; edits since then are still in read().
      this.dirty = true
      this.merge(err.current)
      return
    }
    if (err instanceof ApiError && err.status === 409 && err.code === 'note_deleted') {
      this.pending = null
      this.dirty = true
      this.problem = { kind: 'deleted' }
      this.set('conflict')
      return
    }
    if (err instanceof ApiError && err.status === 401) {
      // Same request again after sign-in, so a save that did land replays.
      this.waitingForAuth = true
      this.set('failed')
      return
    }
    // Only transport failures and server errors are worth retrying. A 403
    // (CSRF/origin check) won't fix itself: an ended session fails with 401
    // before that check runs.
    if (err instanceof NetworkError || (err instanceof ApiError && err.status >= 500)) {
      this.problem = { kind: 'offline' }
      this.set('failed')
      const delay = Math.min(timing.retryBaseMs * 2 ** this.attempts, timing.retryMaxMs)
      this.attempts++
      clearTimeout(this.retryTimer)
      this.retryTimer = setTimeout(() => void this.flush(), delay)
      return
    }
    this.pending = null
    this.dirty = true
    this.problem = {
      kind: 'rejected',
      message: err instanceof ApiError ? err.message : 'The server rejected this save.',
    }
    this.set('failed')
  }

  /** §6.1: take each field from whichever side changed it; give up only when both changed it differently. */
  private merge(current: Note) {
    // The latest local text, including edits typed after the rejected request.
    const local = this.read()
    const base = this.base
    const pick = (key: keyof Fields): string | null => {
      const mine = local[key]
      const theirs = current[key]
      if (mine === base[key]) return theirs
      if (theirs === base[key] || theirs === mine) return mine
      return null
    }
    const title = pick('title')
    const content = pick('content')
    if (current.deletedAt !== null) {
      this.problem = { kind: 'deleted' }
      this.set('conflict')
      return
    }
    if (title === null || content === null) {
      this.problem = { kind: 'conflict', current }
      this.set('conflict')
      return
    }
    this.adopt(current)
    const merged = { title, content }
    if (merged.title !== local.title || merged.content !== local.content) this.pushRemote(merged)
    this.dirty = true
    void Promise.resolve().then(() => this.flush())
  }

  /** Make the server's version our base. */
  private adopt(current: Note) {
    this.revision = current.revision
    this.base = { title: current.title, content: current.content }
    this.updatedAt = current.updatedAt
    this.created = true
    this.problem = null
  }

  private pushRemote(fields: Fields) {
    const frozen = { ...fields }
    // An attached editor updates what its reader returns synchronously in
    // the listener; with no editor, the pushed values become the local ones.
    if (this.remoteListeners.size === 0) this.read = () => frozen
    for (const l of this.remoteListeners) l(frozen)
  }

  // --- resolving conflicts --------------------------------------------------

  /** "Keep mine": the local text becomes a new note; this one shows the server's version. */
  keepMine(): NoteSaver | null {
    const problem = this.problem
    if (problem?.kind !== 'conflict') return null
    const copy = this.copy(' (Conflicted copy)', this.id)
    this.takeTheirs()
    return copy
  }

  /** "Use theirs": drop local edits. */
  takeTheirs() {
    const problem = this.problem
    if (problem?.kind !== 'conflict') return
    this.adopt(problem.current)
    this.dirty = false
    this.pushRemote({ title: problem.current.title, content: problem.current.content })
    this.set('saved')
  }

  /** "Keep as new note" after the note was deleted elsewhere. */
  recover(): NoteSaver | null {
    if (this.problem?.kind !== 'deleted') return null
    return this.copy(' (Recovered copy)')
  }

  private copy(suffix: string, conflictOf?: string): NoteSaver {
    const local = this.read()
    const copy = new NoteSaver(
      {
        id: crypto.randomUUID(),
        fields: { title: `${local.title.trim() || 'Untitled'}${suffix}`, content: local.content },
        ...(conflictOf !== undefined && { conflictOf }),
      },
      this.hooks,
    )
    this.hooks.copied?.(copy)
    copy.dirty = true
    void copy.flush()
    return copy
  }

  // --- deleting ---------------------------------------------------------------

  /**
   * Deletes the note once any in-flight save has landed. Resolves to 'changed'
   * when someone else edited it first (the editor now shows their version).
   */
  async delete(): Promise<'deleted' | 'changed'> {
    clearTimeout(this.debounce)
    clearTimeout(this.retryTimer)
    if (this.inflight) await this.inflight.catch(() => undefined)
    this.dirty = false
    this.pending = null
    if (!this.created) return 'deleted'
    try {
      await notes.remove(this.id, { operationId: crypto.randomUUID(), baseRevision: this.revision })
      return 'deleted'
    } catch (err) {
      if (err instanceof ApiError && err.code === 'note_deleted') return 'deleted'
      if (err instanceof ApiError && err.code === 'revision_conflict' && err.current) {
        this.adopt(err.current)
        this.pushRemote({ title: err.current.title, content: err.current.content })
        this.set('saved')
        return 'changed'
      }
      throw err
    }
  }

  dispose() {
    clearTimeout(this.debounce)
    clearTimeout(this.retryTimer)
    this.listeners.clear()
    this.remoteListeners.clear()
  }

  private set(state: SaveState) {
    this.state = state
    this.snapshot = this.makeSnapshot()
    for (const l of this.listeners) l()
  }

  private makeSnapshot(): Snapshot {
    return { state: this.state, problem: this.problem, created: this.created, updatedAt: this.updatedAt }
  }
}
