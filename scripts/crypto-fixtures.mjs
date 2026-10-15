// Generates shared/crypto-fixtures/locked.json: the vectors every client's
// locked-notes crypto must reproduce (architecture §10.1). Inputs are fixed,
// so rerunning it rewrites the same file.
//
//   node scripts/crypto-fixtures.mjs
import { createCipheriv, createHmac, hkdfSync, pbkdf2Sync } from 'node:crypto'
import { writeFileSync } from 'node:fs'

const b64 = (b) => Buffer.from(b).toString('base64url')
const bytes = (n, seed) => Buffer.from(Array.from({ length: n }, (_, i) => (seed + i * 7) & 0xff))

const PREFIX = 'notesd:locked:v1:'
const BODY_AAD = 'notesd:locked:v1'
const wrapAad = (kind) => `notesd:lnk:v1:${kind}`

function seal(key, nonce, plain, aad) {
  const c = createCipheriv('aes-256-gcm', key, nonce)
  c.setAAD(Buffer.from(aad))
  const ct = Buffer.concat([c.update(plain), c.final()])
  return Buffer.concat([nonce, ct, c.getAuthTag()])
}

const lnk = bytes(32, 1)

const kdf = [
  { name: 'ascii password', password: 'correct horse battery staple', salt: bytes(16, 3), iterations: 1000 },
  // Decomposed (NFD: a + U+0308), so only a client that normalizes to NFC
  // derives this key; typed on another device the same word may be precomposed.
  { name: 'unicode password, decomposed', password: 'pa\u0308sswo\u0308rd 🔒', salt: bytes(16, 9), iterations: 1000 },
  { name: 'production iterations', password: 'hunter2', salt: bytes(16, 11), iterations: 600000 }
].map((v) => ({
  ...v,
  salt: b64(v.salt),
  key: b64(pbkdf2Sync(Buffer.from(v.password.normalize('NFC')), v.salt, v.iterations, 32, 'sha256'))
}))

const prf = bytes(32, 5)
const prfSalt = bytes(32, 13) // a passkey entry's salt: the PRF input and the HKDF salt
const hkdf = [
  {
    name: 'passkey prf',
    prf: b64(prf),
    salt: b64(prfSalt),
    info: 'notesd:passkey:v1',
    key: b64(Buffer.from(hkdfSync('sha256', prf, prfSalt, 'notesd:passkey:v1', 32)))
  }
]

const kek = Buffer.from(kdf[0].key, 'base64url')
const wraps = ['password', 'recovery', 'passkey'].map((kind, i) => ({
  name: kind,
  kind,
  kek: kdf[0].key,
  key: b64(lnk),
  wrapped: b64(seal(kek, bytes(12, 20 + i), lnk, wrapAad(kind)))
}))

const envelopes = [
  { name: 'empty body', plaintext: '' },
  { name: 'markdown', plaintext: '- [ ] Milk\n- [x] **Bread**\n\n> PIN 1234 #private' },
  { name: 'unicode', plaintext: 'Senha: çãé 日本語 🔐\\\nnext line' }
].map((v, i) => ({
  ...v,
  key: b64(lnk),
  envelope: PREFIX + b64(seal(lnk, bytes(12, 40 + i), Buffer.from(v.plaintext), BODY_AAD))
}))

const good = envelopes[1].envelope
const raw = Buffer.from(good.slice(PREFIX.length), 'base64url')
const flipped = Buffer.from(raw)
flipped[flipped.length - 1] ^= 1
const invalid = [
  { name: 'tampered tag', envelope: PREFIX + b64(flipped) },
  { name: 'wrong aad', envelope: PREFIX + b64(seal(lnk, bytes(12, 60), Buffer.from('x'), 'other')) },
  { name: 'wrong key', envelope: PREFIX + b64(seal(bytes(32, 99), bytes(12, 61), Buffer.from('x'), BODY_AAD)) },
  { name: 'missing prefix', envelope: good.slice(PREFIX.length) },
  { name: 'unknown version', envelope: 'notesd:locked:v2:' + good.slice(PREFIX.length) },
  { name: 'too short', envelope: PREFIX + b64(bytes(20, 0)) },
  { name: 'not base64url', envelope: PREFIX + 'abc+/=' },
  { name: 'line break', envelope: good.slice(0, 40) + '\r\n' + good.slice(40) }
].map((v) => ({ ...v, key: b64(lnk) }))

// Proves to the server that a client holds the key, without revealing it.
const verifiers = [
  { name: 'verifier', key: b64(lnk), verifier: b64(createHmac('sha256', lnk).update('notesd:verifier:v1').digest()) }
]

const out = {
  description:
    'Locked notes crypto (architecture §10.1). Binary values are unpadded base64url. ' +
    'kdf: PBKDF2-HMAC-SHA256 of the NFC-normalized UTF-8 password, 32-byte key. ' +
    'hkdf: HKDF-SHA256 of a passkey PRF output. ' +
    'wraps: AES-256-GCM of the 32-byte locked-notes key under kek, nonce(12) ‖ ciphertext ‖ tag, AAD "notesd:lnk:v1:<kind>". ' +
    'envelopes: note content "notesd:locked:v1:" + base64url(nonce(12) ‖ ciphertext ‖ tag) of the UTF-8 Markdown body, AAD "notesd:locked:v1". ' +
    'invalid: envelopes every client must refuse to open with key. ' +
    'verifiers: HMAC-SHA256(key, "notesd:verifier:v1"), sent to change the keyring.',
  kdf,
  hkdf,
  wraps,
  envelopes,
  invalid,
  verifiers
}

const path = new URL('../shared/crypto-fixtures/locked.json', import.meta.url)
writeFileSync(path, JSON.stringify(out, null, 2) + '\n')
console.log(`wrote ${path.pathname}`)
