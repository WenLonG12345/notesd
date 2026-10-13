import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'
import { timing } from '../src/notes/saver'

// Same behaviour, shorter waits.
timing.debounceMs = 40
timing.retryBaseMs = 60
timing.retryMaxMs = 200

// jsdom has no layout, so nothing ever scrolls into view; tests use the
// "Load more" button the sentinel renders.
globalThis.IntersectionObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() {
    return []
  }
  root = null
  rootMargin = ''
  thresholds = []
} as unknown as typeof IntersectionObserver

// jsdom has no modal dialog support.
HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) {
  this.open = true
}
HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) {
  this.open = false
}

// Nor does it turn Escape into a cancel event on the topmost modal dialog.
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Escape') return
  const open = [...document.querySelectorAll('dialog[open]')]
  open.at(-1)?.dispatchEvent(new Event('cancel', { cancelable: true }))
})

afterEach(() => {
  cleanup()
  window.history.replaceState(null, '', '/')
})
