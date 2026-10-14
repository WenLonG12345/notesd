import { spawn, type ChildProcess } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { inject, onTestFinished } from 'vitest'

export type TestServer = {
  url: string
  setupToken: string
  /** Browser requests fail as if the network were down. */
  setOffline: (offline: boolean) => void
  /** Browser requests claim this Origin, e.g. a misconfigured proxy's. */
  setOrigin: (origin: string) => void
  /** The next matching browser request reaches the server, but its response is lost. */
  loseNextResponse: (match: (method: string, path: string) => boolean) => void
  /** Calls the API as a separate client (its own cookie jar). */
  api: <T = unknown>(method: string, path: string, body?: unknown) => Promise<{ status: number; body: T }>
  stop: () => Promise<void>
}

const nodeFetch = globalThis.fetch

/**
 * Starts a real notesd on a free port with an empty data dir and points
 * the app's fetch at it, with a cookie jar standing in for the browser's.
 */
export async function startServer(): Promise<TestServer> {
  const dataDir = mkdtempSync(join(tmpdir(), 'notes-data-'))
  const proc = spawn(inject('serverBin'), ['--data-dir', dataDir, '--listen', '127.0.0.1:0'])
  const { url, setupToken } = await readStartup(proc)

  const browser = cookieFetch(url)
  globalThis.fetch = browser

  const other = cookieFetch(url)
  const server: TestServer = {
    url,
    setupToken,
    setOffline: (offline) => {
      browser.offline = offline
    },
    setOrigin: (origin) => {
      browser.origin = origin
    },
    loseNextResponse: (match) => {
      browser.lose = match
    },
    async api(method, path, body) {
      const res = await other(`/api/v1${path}`, {
        method,
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': other.csrf ?? '' },
        ...(body !== undefined && { body: JSON.stringify(body) }),
      })
      const text = await res.text()
      const parsed: unknown = text ? JSON.parse(text) : undefined
      if (parsed && typeof parsed === 'object' && 'csrfToken' in parsed) {
        other.csrf = String(parsed.csrfToken)
      }
      return { status: res.status, body: parsed as never }
    },
    async stop() {
      if (proc.exitCode === null) {
        const exited = new Promise((r) => proc.once('exit', r))
        proc.kill()
        await exited
      }
      rmSync(dataDir, { recursive: true, force: true })
    },
  }
  onTestFinished(async () => {
    globalThis.fetch = nodeFetch
    await server.stop()
  })
  return server
}

/** Creates the owner account through the API, as another device would. */
export async function createOwner(server: TestServer, username = 'me', password = 'correct horse') {
  const res = await server.api('POST', '/auth/setup', { setupToken: server.setupToken, username, password })
  if (res.status !== 201) throw new Error(`setup failed: ${res.status}`)
  return { username, password }
}

function readStartup(proc: ChildProcess): Promise<{ url: string; setupToken: string }> {
  return new Promise((resolve, reject) => {
    let log = ''
    let setupToken = ''
    const onData = (chunk: Buffer) => {
      log += chunk.toString()
      setupToken ||= /setup_token=(\S+)/.exec(log)?.[1] ?? ''
      const listen = /listen=(\S+)/.exec(log)?.[1]
      if (listen && setupToken) {
        proc.stdout?.off('data', onData)
        proc.stderr?.off('data', onData)
        resolve({ url: `http://${listen}`, setupToken })
      }
    }
    proc.stdout?.on('data', onData)
    proc.stderr?.on('data', onData)
    proc.once('exit', (code) => reject(new Error(`notesd exited (${code}):\n${log}`)))
  })
}

type CookieFetch = typeof fetch & {
  csrf?: string
  offline?: boolean
  origin?: string
  lose?: ((method: string, path: string) => boolean) | undefined
}

function cookieFetch(base: string): CookieFetch {
  const jar = new Map<string, string>()
  const fn: CookieFetch = async (input, init = {}) => {
    const url = new URL(String(input), base)
    if (fn.offline) throw new TypeError('Failed to fetch')
    const headers = new Headers(init.headers)
    // Same-origin browser requests carry Origin on mutations.
    headers.set('Origin', fn.origin ?? base)
    if (jar.size > 0) headers.set('Cookie', [...jar].map(([k, v]) => `${k}=${v}`).join('; '))

    // jsdom's AbortSignal isn't Node's; bridge it so aborts still reach fetch.
    const controller = new AbortController()
    init.signal?.addEventListener('abort', () => controller.abort(init.signal?.reason))
    if (init.signal?.aborted) controller.abort(init.signal.reason)

    const res = await nodeFetch(url, { ...init, headers, signal: controller.signal })
    if (fn.lose?.(init.method ?? 'GET', url.pathname)) {
      fn.lose = undefined
      throw new TypeError('Failed to fetch')
    }
    for (const cookie of res.headers.getSetCookie()) {
      const [pair = '', ...attrs] = cookie.split(';')
      const eq = pair.indexOf('=')
      const name = pair.slice(0, eq).trim()
      const expired = attrs.some((a) => /^\s*max-age=(0|-)/i.test(a))
      if (expired) jar.delete(name)
      else jar.set(name, pair.slice(eq + 1).trim())
    }
    return res
  }
  return fn
}
