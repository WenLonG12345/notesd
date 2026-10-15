import { describe, expect, it } from 'vitest'
import fixtures from '../../../../shared/crypto-fixtures/locked.json'
import {
  LockedError,
  decryptBody,
  encryptBody,
  fromBase64Url,
  importKey,
  isLocked,
  newRecoveryCode,
  normalizeRecoveryCode,
  passkeyKek,
  passwordKek,
  toBase64Url,
  unwrapRaw,
  verifier,
  wrap,
  type WrapKind,
} from './crypto'

const exportable = async (raw: string) => importKey(fromBase64Url(raw), true)
/** The locked-notes key every wrap in the fixtures holds. */
const lnk = fixtures.wraps[0]?.key ?? ''

describe('locked notes crypto (shared fixtures)', () => {
  // 600k iterations is slow under jsdom; the Kotlin suite checks that vector.
  for (const v of fixtures.kdf.filter((k) => k.iterations < 100_000)) {
    it(`kdf: ${v.name}`, async () => {
      const kek = await passwordKek(v.password, fromBase64Url(v.salt), v.iterations)
      // The kek isn't extractable: prove it equals the vector by unwrapping with both.
      const wrapped = await wrap(kek, fromBase64Url(lnk), 'password')
      const viaVector = await unwrapRaw(await importKey(fromBase64Url(v.key)), wrapped, 'password')
      expect(toBase64Url(viaVector)).toBe(lnk)
    })
  }

  for (const v of fixtures.hkdf) {
    it(`hkdf: ${v.name}`, async () => {
      const kek = await passkeyKek(fromBase64Url(v.prf), fromBase64Url(v.salt))
      const wrapped = await wrap(kek, fromBase64Url(lnk), 'passkey')
      const viaVector = await unwrapRaw(await importKey(fromBase64Url(v.key)), wrapped, 'passkey')
      expect(toBase64Url(viaVector)).toBe(lnk)
    })
  }

  for (const v of fixtures.wraps) {
    it(`unwrap: ${v.name}`, async () => {
      const kek = await importKey(fromBase64Url(v.kek))
      const raw = await unwrapRaw(kek, v.wrapped, v.kind as WrapKind)
      expect(toBase64Url(raw)).toBe(v.key)
      const other: WrapKind = v.kind === 'password' ? 'recovery' : 'password'
      await expect(unwrapRaw(kek, v.wrapped, other)).rejects.toBeInstanceOf(LockedError)
    })
  }

  for (const v of fixtures.envelopes) {
    it(`envelope: ${v.name}`, async () => {
      const key = await exportable(v.key)
      expect(isLocked(v.envelope)).toBe(true)
      expect(await decryptBody(key, v.envelope)).toBe(v.plaintext)
      const again = await encryptBody(key, v.plaintext)
      expect(again).not.toBe(v.envelope) // fresh nonce every time
      expect(await decryptBody(key, again)).toBe(v.plaintext)
    })
  }

  for (const v of fixtures.invalid) {
    it(`refuses: ${v.name}`, async () => {
      await expect(decryptBody(await exportable(v.key), v.envelope)).rejects.toBeInstanceOf(LockedError)
    })
  }
})

describe('verifier (shared fixtures)', () => {
  for (const v of fixtures.verifiers) {
    it(v.name, async () => {
      expect(await verifier(fromBase64Url(v.key))).toBe(v.verifier)
    })
  }
})

describe('recovery codes', () => {
  it('are 24 unambiguous characters in groups of three', () => {
    const code = newRecoveryCode()
    expect(code).toMatch(/^([2-9A-HJ-NP-Z]{3}-){7}[2-9A-HJ-NP-Z]{3}$/)
    expect(normalizeRecoveryCode(code.toLowerCase().replace(/-/g, ' '))).toBe(code.replace(/-/g, ''))
  })
})

describe('bodies', () => {
  it('a leading byte order mark survives the round trip', async () => {
    const key = await importKey(fromBase64Url(lnk))
    expect(await decryptBody(key, await encryptBody(key, '﻿secret\n'))).toBe('﻿secret\n')
  })
})
