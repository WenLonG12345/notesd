import { createHmac, randomBytes } from 'node:crypto'
import { onTestFinished } from 'vitest'

type Options = {
  /** The authenticator supports the PRF extension. */
  prf?: boolean
}

const b64 = (b: Uint8Array) => Buffer.from(b).toString('base64url')
const view = (b: BufferSource) =>
  b instanceof ArrayBuffer ? new Uint8Array(b) : new Uint8Array(b.buffer, b.byteOffset, b.byteLength)

/**
 * A platform authenticator in place of the browser's WebAuthn, which jsdom
 * lacks. Its PRF is HMAC-SHA256 under a secret per credential, so the same
 * passkey and salt always give the same secret, as a real one does. Like
 * some real authenticators, it evaluates PRF only on sign-in.
 */
export function fakeAuthenticator({ prf = true }: Options = {}) {
  const secrets = new Map<string, Buffer>()
  const state = { dismissNext: false }

  class FakeCredential {
    constructor(
      readonly rawId: ArrayBuffer,
      private readonly ext: unknown,
    ) {}
    getClientExtensionResults() {
      return this.ext
    }
  }

  const dismissed = () => {
    if (!state.dismissNext) return false
    state.dismissNext = false
    return true
  }

  const credentials = {
    async create() {
      if (dismissed()) throw new DOMException('The operation either timed out or was not allowed.', 'NotAllowedError')
      const id = randomBytes(16)
      secrets.set(b64(id), randomBytes(32))
      return new FakeCredential(new Uint8Array(id).buffer, prf ? { prf: { enabled: true } } : {})
    },
    async get({ publicKey }: { publicKey: PublicKeyCredentialRequestOptions & { extensions?: { prf?: { evalByCredential?: Record<string, { first: BufferSource }> } } } }) {
      if (dismissed()) throw new DOMException('The operation either timed out or was not allowed.', 'NotAllowedError')
      const allowed = (publicKey.allowCredentials ?? []).map((c) => b64(view(c.id)))
      const id = allowed.find((a) => secrets.has(a))
      if (!id) throw new DOMException('No passkey for this site.', 'NotAllowedError')
      const salt = publicKey.extensions?.prf?.evalByCredential?.[id]?.first
      const secret = secrets.get(id) ?? Buffer.alloc(32)
      const results =
        prf && salt ? { prf: { results: { first: new Uint8Array(createHmac('sha256', secret).update(view(salt)).digest()).buffer } } } : {}
      return new FakeCredential(new Uint8Array(Buffer.from(id, 'base64url')).buffer, results)
    },
  }

  const hadPkc = 'PublicKeyCredential' in window
  Object.defineProperty(window, 'PublicKeyCredential', { value: FakeCredential, configurable: true, writable: true })
  Object.defineProperty(navigator, 'credentials', { value: credentials, configurable: true })
  onTestFinished(() => {
    if (!hadPkc) delete (window as { PublicKeyCredential?: unknown }).PublicKeyCredential
    delete (navigator as { credentials?: unknown }).credentials
  })
  return state
}
