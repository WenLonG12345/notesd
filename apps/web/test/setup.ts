import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'
import { timing } from '../src/notes/saver'

// Same behaviour, shorter waits.
timing.debounceMs = 40
timing.retryBaseMs = 60
timing.retryMaxMs = 200
timing.undoDeleteMs = 300

// jsdom has no media queries. Tests get the side-by-side layout unless they
// ask for a phone (helpers.phoneLayout); nothing re-evaluates mid-render.
declare global {
  var phoneLayout: boolean
}
globalThis.phoneLayout = false
window.matchMedia ??= (query: string) =>
  ({
    matches: query.includes('max-width') && globalThis.phoneLayout,
    media: query,
    onchange: null,
    addEventListener() {},
    removeEventListener() {},
    addListener() {},
    removeListener() {},
    dispatchEvent: () => false,
  }) as MediaQueryList

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

// ProseMirror measures the DOM to scroll the selection into view; jsdom has
// no layout, so these report empty boxes.
const emptyRect = () => DOMRect.fromRect({ x: 0, y: 0, width: 0, height: 0 })
Range.prototype.getBoundingClientRect ??= emptyRect
Range.prototype.getClientRects ??= () => ({ length: 0, item: () => null, [Symbol.iterator]: [][Symbol.iterator] }) as unknown as DOMRectList
document.elementFromPoint ??= () => null

// Nor does it turn Escape into a cancel event on the topmost modal dialog.
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Escape') return
  const open = [...document.querySelectorAll('dialog[open]')]
  open.at(-1)?.dispatchEvent(new Event('cancel', { cancelable: true }))
})

afterEach(() => {
  cleanup()
  globalThis.phoneLayout = false
  window.history.replaceState(null, '', '/')
})
