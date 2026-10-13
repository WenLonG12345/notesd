import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect } from 'vitest'
import type { Note, NoteList } from '../src/api/types'
import { App } from '../src/app'
import { createOwner, startServer, type TestServer } from './server'

export type Session = { id: string }

/**
 * A server with an owner account and the app signed in to it. seed runs
 * before the app loads, so the grid starts with those notes.
 */
export async function signedIn({ path = '/', seed }: { path?: string; seed?: (server: TestServer) => Promise<void> } = {}) {
  const server = await startServer()
  const { username, password } = await createOwner(server)
  // The other client (curl, a phone) signs in too, for edits from "another device".
  await server.api('POST', '/auth/login', { username, password, client: 'web' })
  await seed?.(server)
  window.history.replaceState(null, '', path)
  const user = userEvent.setup()
  render(<App />)
  await user.type(await screen.findByLabelText('Username'), username)
  await user.type(screen.getByLabelText('Password'), `${password}{Enter}`)
  await screen.findByRole('heading', { name: 'All notes' })
  return { server, user, password }
}

let opSeq = 0
export const op = () => `test-op-${++opSeq}`

export async function createNote(server: TestServer, title: string, content = '') {
  const id = crypto.randomUUID()
  const res = await server.api('POST', '/notes', { operationId: op(), id, title, content })
  expect(res.status).toBe(201)
  return id
}

export async function getNote(server: TestServer, id: string) {
  return (await server.api<Note>('GET', `/notes/${id}`)).body
}

export async function listNotes(server: TestServer) {
  return (await server.api<NoteList>('GET', '/notes?limit=100')).body.notes
}

/** Edits a note as another device would. */
export async function editElsewhere(server: TestServer, id: string, patch: Partial<Pick<Note, 'title' | 'content'>>) {
  const note = await getNote(server, id)
  const res = await server.api('PUT', `/notes/${id}`, {
    operationId: op(),
    baseRevision: note.revision,
    title: patch.title ?? note.title,
    content: patch.content ?? note.content,
  })
  expect(res.status).toBe(200)
}

export const titleInput = () => screen.getByLabelText<HTMLInputElement>('Title')
export const body = () => screen.getByRole('textbox', { name: 'Note text' })
export const bodyText = () => body().textContent

/** Types at the end of the note body (jsdom has no layout, so a click can't place the caret). */
export async function typeAtEnd(user: ReturnType<typeof userEvent.setup>, text: string) {
  const el = body()
  el.focus()
  const range = document.createRange()
  range.selectNodeContents(el)
  range.collapse(false)
  const sel = window.getSelection()
  sel?.removeAllRanges()
  sel?.addRange(range)
  document.dispatchEvent(new Event('selectionchange'))
  await user.type(el, text, { skipClick: true })
}
// The header button; the mobile floating one is only hidden by CSS, which jsdom lacks.
export const newNoteButton = () => screen.getAllByRole('button', { name: 'New note' })[0] as HTMLElement
export const saved = () => screen.findByText('Saved', {}, { timeout: 3000 })

export async function openTile(user: ReturnType<typeof userEvent.setup>, title: string) {
  await user.click(await screen.findByRole('link', { name: new RegExp(title) }))
  await screen.findByLabelText('Title')
}

/** Selects the first occurrence of text in the note body. */
export function selectInBody(text: string) {
  const el = body()
  el.focus()
  const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT)
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const at = node.textContent?.indexOf(text) ?? -1
    if (at < 0) continue
    const range = document.createRange()
    range.setStart(node, at)
    range.setEnd(node, at + text.length)
    const sel = window.getSelection()
    sel?.removeAllRanges()
    sel?.addRange(range)
    document.dispatchEvent(new Event('selectionchange'))
    return
  }
  throw new Error(`"${text}" not in the note body`)
}
