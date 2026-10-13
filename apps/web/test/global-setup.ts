import { execFileSync } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import type { TestProject } from 'vitest/node'

declare module 'vitest' {
  export interface ProvidedContext {
    serverBin: string
  }
}

// Builds the real server once per run; integration tests start a fresh
// instance of it per test.
export default function setup(project: TestProject) {
  const dir = mkdtempSync(join(tmpdir(), 'notes-web-test-'))
  const bin = join(dir, 'notes-server')
  execFileSync('go', ['build', '-o', bin, './cmd/notes-server'], {
    cwd: resolve(import.meta.dirname, '../../../server'),
    stdio: 'inherit',
  })
  project.provide('serverBin', bin)
  return () => rmSync(dir, { recursive: true, force: true })
}
