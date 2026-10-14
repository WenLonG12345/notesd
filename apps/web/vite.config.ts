import { writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

const outDir = resolve(import.meta.dirname, '../../server/internal/web/dist')
const server = process.env.NOTESD_SERVER ?? 'http://127.0.0.1:8080'

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
    // changeOrigin must stay false (the string shorthand turns it on): the
    // server rejects mutations whose Origin host differs from Host, and the
    // browser's Origin is the Vite dev server.
    proxy: Object.fromEntries(
      ['/api', '/healthz', '/readyz'].map((path) => [path, { target: server, changeOrigin: false }]),
    ),
  },
  test: {
    environment: 'jsdom',
    globalSetup: ['test/global-setup.ts'],
    setupFiles: ['test/setup.ts'],
  },
})
