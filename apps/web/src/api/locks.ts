import { request } from './client'
import type { LockKey } from './types'

export type PutLockKey = Omit<LockKey, 'id' | 'createdAt' | 'credentialId'> & { credentialId?: string; verifier: string }

export const locks = {
  keys: () => request<{ keys: LockKey[] }>('GET', '/locks'),
  /** verifier proves this browser holds the locked-notes key; the first password entry sets it. */
  put: (id: string, key: PutLockKey) => request<LockKey>('PUT', `/locks/keys/${encodeURIComponent(id)}`, key),
  remove: (id: string, verifier: string) =>
    request<undefined>('DELETE', `/locks/keys/${encodeURIComponent(id)}`, { verifier }),
  /** Forgets the keyring: notes locked so far can never be opened again. */
  reset: (accountPassword: string) => request<undefined>('DELETE', '/locks', { accountPassword }),
}
