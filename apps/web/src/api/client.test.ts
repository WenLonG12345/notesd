import { afterEach, describe, expect, it, vi } from 'vitest'
import { NetworkError, request } from './client'

// Everything else about the client is covered by the integration tests in
// test/, against the real server. These are failures that server can't produce.

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('request', () => {
  it('survives non-JSON error bodies from proxies', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('Bad Gateway', { status: 502 })))
    await expect(request('GET', '/notes')).rejects.toMatchObject({ status: 502, code: 'server_error' })
  })

  it('passes aborts through instead of reporting them as connection loss', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new DOMException('aborted', 'AbortError')))
    const err = await request('GET', '/notes').catch((e: unknown) => e)
    expect(err).not.toBeInstanceOf(NetworkError)
    expect(err).toMatchObject({ name: 'AbortError' })
  })
})
