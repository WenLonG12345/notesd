import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, NetworkError, onUnauthenticated, request, setCsrfToken } from './client'

function respond(status: number, body?: unknown, headers: Record<string, string> = {}) {
  const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
    new Response(body === undefined ? null : JSON.stringify(body), { status, headers }),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function sentHeaders(fetchMock: ReturnType<typeof respond>): Record<string, string> {
  return fetchMock.mock.calls[0]?.[1]?.headers as Record<string, string>
}

afterEach(() => {
  vi.unstubAllGlobals()
  setCsrfToken(null)
})

describe('request', () => {
  it('sends the CSRF token on mutations only', async () => {
    setCsrfToken('tok')
    let fetchMock = respond(200, {})
    await request('GET', '/notes')
    expect(sentHeaders(fetchMock)['X-CSRF-Token']).toBeUndefined()

    fetchMock = respond(201, {})
    await request('POST', '/notes', { a: 1 })
    expect(sentHeaders(fetchMock)['X-CSRF-Token']).toBe('tok')
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/notes')
    expect(fetchMock.mock.calls[0]?.[1]?.body).toBe('{"a":1}')
  })

  it('returns undefined for 204', async () => {
    respond(204)
    await expect(request('POST', '/auth/logout')).resolves.toBeUndefined()
  })

  it('turns error bodies into ApiError with conflict details', async () => {
    const current = { id: 'n1', revision: 7 }
    respond(409, { error: 'revision_conflict', current })
    const err = await request('PUT', '/notes/n1', {}).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err).toMatchObject({ status: 409, code: 'revision_conflict', current })
  })

  it('reads Retry-After on 429', async () => {
    respond(429, { error: 'rate_limited' }, { 'Retry-After': '42' })
    await expect(request('POST', '/auth/login', {})).rejects.toMatchObject({ retryAfter: 42 })
  })

  it('survives non-JSON error bodies', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('Bad Gateway', { status: 502 })))
    await expect(request('GET', '/notes')).rejects.toMatchObject({ status: 502, code: 'server_error' })
  })

  it('notifies listeners on unauthenticated but not on wrong credentials', async () => {
    const listener = vi.fn()
    const off = onUnauthenticated(listener)
    respond(401, { error: 'invalid_credentials' })
    await request('POST', '/auth/login', {}).catch(() => undefined)
    expect(listener).not.toHaveBeenCalled()

    respond(401, { error: 'unauthenticated' })
    await request('GET', '/notes').catch(() => undefined)
    expect(listener).toHaveBeenCalledOnce()
    off()
  })

  it('wraps fetch failures in NetworkError but passes aborts through', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await expect(request('GET', '/notes')).rejects.toBeInstanceOf(NetworkError)

    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new DOMException('aborted', 'AbortError')))
    await expect(request('GET', '/notes')).rejects.toMatchObject({ name: 'AbortError' })
  })
})
