import { useSyncExternalStore } from 'react'
import { locks as api } from '../api/locks'
import type { LockKey } from '../api/types'
import {
  LockedError,
  kdfCost,
  importKey,
  passwordKek,
  randomBytes,
  toBase64Url,
  fromBase64Url,
  unwrapRaw,
  verifier as verifierOf,
  wrap,
} from './crypto'

/**
 * - unknown: the keyring hasn't been fetched
 * - none: no lock password yet (nothing has ever been locked)
 * - locked: locked notes exist but their key isn't in memory
 * - unlocked: the key is in memory; locked notes open without asking
 */
export type LockStatus = 'unknown' | 'none' | 'locked' | 'unlocked'

// Tests shorten these; nothing else should touch them.
export const lockTiming = { idleMs: 5 * 60_000, hiddenMs: 5 * 60_000 }

type BeforeLock = () => Promise<void>

/**
 * The locked-notes key for this page (§10.1). It lives only in memory, as a
 * non-extractable CryptoKey, and is dropped after a while idle or hidden, on
 * sign-out, or when the user locks. Independent of React, like NoteSaver,
 * since savers encrypt with it.
 */
class LockSession {
  private status: LockStatus = 'unknown'
  private cryptoKey: CryptoKey | null = null
  /** Proves to the server that we hold the key, for keyring changes. */
  private proof: string | null = null
  private keyring: LockKey[] = []
  private loading: Promise<void> | null = null
  private listeners = new Set<() => void>()
  private beforeLock = new Set<BeforeLock>()
  private idleTimer: ReturnType<typeof setTimeout> | undefined
  private hiddenTimer: ReturnType<typeof setTimeout> | undefined

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  getStatus = (): LockStatus => this.status

  /** The key, while unlocked. */
  key(): CryptoKey | null {
    return this.cryptoKey
  }

  keys(): readonly LockKey[] {
    return this.keyring
  }

  /** Runs before the key is dropped, e.g. to save edits to locked notes while they can still be encrypted. */
  onBeforeLock(fn: BeforeLock): () => void {
    this.beforeLock.add(fn)
    return () => this.beforeLock.delete(fn)
  }

  /** Fetches the keyring (once, unless forced). */
  load(force = false): Promise<void> {
    if (this.loading && !force) return this.loading
    const p = api.keys().then(
      ({ keys }) => {
        this.keyring = keys
        if (this.status !== 'unlocked') this.set(keys.some((k) => k.kind === 'password') ? 'locked' : 'none')
      },
      (err: unknown) => {
        this.loading = null
        throw err
      },
    )
    this.loading = p
    return p
  }

  /** First use: a new locked-notes key, wrapped under the lock password. */
  async setup(password: string): Promise<void> {
    const raw = randomBytes(32)
    const salt = randomBytes(16)
    const kek = await passwordKek(password, salt, kdfCost.password)
    const proof = await verifierOf(raw)
    const key = await api.put('password', {
      kind: 'password',
      salt: toBase64Url(salt),
      iterations: kdfCost.password,
      wrapped: await wrap(kek, raw, 'password'),
      verifier: proof,
    })
    this.keyring = [key]
    await this.adopt(raw, proof)
  }

  /** Throws LockedError when the password is wrong. */
  async unlock(password: string): Promise<void> {
    await this.load()
    const raw = await this.unwrapWithPassword(password)
    await this.adopt(raw, await verifierOf(raw))
  }

  /** Re-wraps the same key under a new password: no note changes. */
  async changePassword(current: string, next: string): Promise<void> {
    await this.load(true)
    const raw = await this.unwrapWithPassword(current)
    const salt = randomBytes(16)
    const kek = await passwordKek(next, salt, kdfCost.password)
    const proof = await verifierOf(raw)
    const key = await api.put('password', {
      kind: 'password',
      salt: toBase64Url(salt),
      iterations: kdfCost.password,
      wrapped: await wrap(kek, raw, 'password'),
      verifier: proof,
    })
    this.keyring = [key, ...this.keyring.filter((k) => k.id !== 'password')]
    await this.adopt(raw, proof)
  }

  /** Forgets the keyring for good, after a forgotten lock password. */
  async reset(accountPassword: string): Promise<void> {
    await api.reset(accountPassword)
    this.drop()
    this.keyring = []
    this.set('none')
  }

  /** Saves what locked notes have pending, then drops the key. */
  async lock(): Promise<void> {
    if (this.status !== 'unlocked') return
    await Promise.all([...this.beforeLock].map((fn) => fn().catch(() => undefined)))
    this.drop()
    this.set(this.keyring.some((k) => k.kind === 'password') ? 'locked' : 'none')
  }

  /** Signed out: drop everything without saving (the session is gone). */
  forget() {
    this.drop()
    this.keyring = []
    this.loading = null
    this.set('unknown')
  }

  /** The verifier, while unlocked: keyring changes need it. */
  verifier(): string | null {
    return this.proof
  }

  private async unwrapWithPassword(password: string): Promise<Uint8Array<ArrayBuffer>> {
    const entry = this.keyring.find((k) => k.kind === 'password')
    if (!entry) throw new LockedError('No lock password set')
    const kek = await passwordKek(password, fromBase64Url(entry.salt), entry.iterations)
    return unwrapRaw(kek, entry.wrapped, 'password')
  }

  private async adopt(raw: Uint8Array<ArrayBuffer>, proof: string) {
    this.cryptoKey = await importKey(raw)
    raw.fill(0)
    this.proof = proof
    this.armTimers()
    this.set('unlocked')
  }

  private drop() {
    this.cryptoKey = null
    this.proof = null
    this.disarmTimers()
  }

  // --- locking again by itself ------------------------------------------------

  private onActivity = () => {
    clearTimeout(this.idleTimer)
    this.idleTimer = setTimeout(() => void this.lock(), lockTiming.idleMs)
  }

  private onVisibility = () => {
    clearTimeout(this.hiddenTimer)
    if (document.visibilityState === 'hidden') this.hiddenTimer = setTimeout(() => void this.lock(), lockTiming.hiddenMs)
  }

  private armTimers() {
    this.disarmTimers()
    document.addEventListener('keydown', this.onActivity, true)
    document.addEventListener('pointerdown', this.onActivity, true)
    document.addEventListener('visibilitychange', this.onVisibility)
    this.onActivity()
    this.onVisibility()
  }

  private disarmTimers() {
    clearTimeout(this.idleTimer)
    clearTimeout(this.hiddenTimer)
    document.removeEventListener('keydown', this.onActivity, true)
    document.removeEventListener('pointerdown', this.onActivity, true)
    document.removeEventListener('visibilitychange', this.onVisibility)
  }

  private set(status: LockStatus) {
    this.status = status
    for (const l of this.listeners) l()
  }
}

export const lockSession = new LockSession()

export function useLockStatus(): LockStatus {
  return useSyncExternalStore(lockSession.subscribe, lockSession.getStatus)
}
