import { writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

const outDir = resolve(import.meta.dirname, '../../server/internal/web/dist')
const server = 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [
    react(),
    {
      // emptyOutDir wipes the tracked .gitkeep that keeps //go:embed working
      // on a fresh clone; put it back.
      name: 'keep-gitkeep',
      apply: 'build',
      closeBundle() {
        writeFileSync(resolve(outDir, '.gitkeep'), '')
      },
    },
  ],
  build: { outDir, emptyOutDir: true },
  server: {
    proxy: { '/api': server, '/healthz': server, '/readyz': server },
  },
  test: {
    environment: 'jsdom',
    globalSetup: ['test/global-setup.ts'],
    setupFiles: ['test/setup.ts'],
  },
})
