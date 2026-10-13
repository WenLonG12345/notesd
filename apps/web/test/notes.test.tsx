import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { App } from '../src/app'
import {
  bodyText,
  createNote,
  editElsewhere,
  getNote,
  listNotes,
  newNoteButton,
  op,
  openTile,
  saved,
  signedIn,
  titleInput,
  typeAtEnd,
  type Session,
} from './helpers'
import { createOwner, startServer } from './server'


describe('creating', () => {
  it('saves a new note on its first edit and lists it', async () => {
    const { server, user } = await signedIn()

    await user.click(newNoteButton())
    expect(document.activeElement).toBe(titleInput())
    expect(screen.getByText('New note', { selector: 'div' })).toBeTruthy()
    // Not in the URL until the server has it.
    expect(window.location.pathname).toBe('/')

    // Enter moves focus into the body; ProseMirror's focus() doesn't take in
    // jsdom, so that part is checked in a real browser, not here.
    await user.type(titleInput(), 'Groceries{Enter}')
    await typeAtEnd(user, 'Milk and eggs')
    await waitFor(() => expect(window.location.pathname).toMatch(/^\/notes\//))
    await waitFor(async () => {
      const [note] = await listNotes(server)
      expect(note?.title).toBe('Groceries')
      expect(note?.preview).toBe('Milk and eggs')
    })

    await user.keyboard('{Escape}')
    const tile = await screen.findByRole('link', { name: /Groceries/ })
    expect(within(tile).getByText('Milk and eggs')).toBeTruthy()
    expect(window.location.pathname).toBe('/')
  })

  it('drops a new note nobody typed into', async () => {
    const { server, user } = await signedIn()

    await user.click(newNoteButton())
    await user.click(screen.getByRole('button', { name: /All notes/ }))

    expect(await screen.findByText('Your first note starts here.')).toBeTruthy()
    expect(await listNotes(server)).toEqual([])
  })

  it('opens a note from its URL on reload', async () => {
    const server = await startServer()
    const { username, password } = await createOwner(server)
    await server.api('POST', '/auth/login', { username, password, client: 'web' })
    const id = await createNote(server, 'Deep link', 'Hello')
    window.history.replaceState(null, '', `/notes/${id}`)
    const user = userEvent.setup()
    render(<App />)
    await user.type(await screen.findByLabelText('Username'), username)
    await user.type(screen.getByLabelText('Password'), `${password}{Enter}`)

    await waitFor(() => expect(titleInput().value).toBe('Deep link'))
    expect(bodyText()).toBe('Hello')
  })

  it('says so when the linked note is gone', async () => {
    const { user } = await signedIn({ path: '/notes/does-not-exist' })
    expect(await screen.findByRole('heading', { name: 'This note doesn’t exist.' })).toBeTruthy()
    await user.keyboard('{Escape}')
    expect(window.location.pathname).toBe('/')
  })
})

describe('editing', () => {
  it('autosaves edits to an existing note', async () => {
    let id = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        id = await createNote(s, 'Plan', 'Step one')
      },
    })
    await openTile(user, 'Plan')

    await waitFor(() => expect(bodyText()).toBe('Step one'))
    await typeAtEnd(user, ', step two')
    await saved()
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('Step one, step two\n'))
    expect((await getNote(server, id)).revision).toBeGreaterThan(1)
  })

  it('merges a title changed elsewhere with content changed here', async () => {
    let id = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        id = await createNote(s, 'Shopping', 'Buy milk')
      },
    })
    await openTile(user, 'Shopping')
    await waitFor(() => expect(bodyText()).toBe('Buy milk'))

    await editElsewhere(server, id, { title: 'Groceries' })
    await typeAtEnd(user, ' and bread')

    await waitFor(() => expect(titleInput().value).toBe('Groceries'))
    await saved()
    await waitFor(async () => {
      const note = await getNote(server, id)
      expect(note).toMatchObject({ title: 'Groceries', content: 'Buy milk and bread\n' })
    })
    expect(screen.queryByText(/Changed on another device/)).toBeNull()
  })

  it('keeps both versions when the content conflicts', async () => {
    let id = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        id = await createNote(s, 'Trip', 'Pack light')
      },
    })
    await openTile(user, 'Trip')
    await waitFor(() => expect(bodyText()).toBe('Pack light'))

    await editElsewhere(server, id, { content: 'Pack light, bring a charger' })
    await typeAtEnd(user, ', no laptop')

    expect(await screen.findByText(/Changed on another device/)).toBeTruthy()
    expect(screen.getByText('Conflict')).toBeTruthy()
    // Nothing is overwritten while the user decides.
    expect((await getNote(server, id)).content).toBe('Pack light, bring a charger')

    await user.click(screen.getByRole('button', { name: 'Keep mine' }))
    await waitFor(() => expect(bodyText()).toBe('Pack light, bring a charger'))
    await waitFor(async () => {
      const copy = (await listNotes(server)).find((n) => n.title === 'Trip (Conflicted copy)')
      expect(copy?.preview).toBe('Pack light, no laptop')
      expect((await getNote(server, copy?.id ?? '')).conflictOf).toBe(id)
    })
    expect((await getNote(server, id)).content).toBe('Pack light, bring a charger')
  })

  it('can take the other version instead', async () => {
    let id = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        id = await createNote(s, 'Trip', 'Pack light')
      },
    })
    await openTile(user, 'Trip')
    await waitFor(() => expect(bodyText()).toBe('Pack light'))

    await editElsewhere(server, id, { content: 'Theirs' })
    await typeAtEnd(user, ' mine')
    await user.click(await screen.findByRole('button', { name: 'Use theirs' }))

    expect(bodyText()).toBe('Theirs')
    expect(screen.getByText('Saved')).toBeTruthy()
    // Editing continues against their revision.
    await typeAtEnd(user, ' and more')
    await saved()
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('Theirs and more\n'))
    expect((await listNotes(server)).map((n) => n.title)).toEqual(['Trip'])
  })

  it('recovers edits to a note deleted elsewhere', async () => {
    let id = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        id = await createNote(s, 'Ideas', 'One')
      },
    })
    await openTile(user, 'Ideas')
    await waitFor(() => expect(bodyText()).toBe('One'))

    const note = await getNote(server, id)
    await server.api('DELETE', `/notes/${id}`, { operationId: op(), baseRevision: note.revision })
    await typeAtEnd(user, ', two')

    expect(await screen.findByText('This note was deleted on another device.')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Keep as new note' }))

    await waitFor(() => expect(titleInput().value).toBe('Ideas (Recovered copy)'))
    expect(bodyText()).toBe('One, two')
    await waitFor(async () => {
      const notes = await listNotes(server)
      expect(notes.map((n) => [n.title, n.preview])).toEqual([['Ideas (Recovered copy)', 'One, two']])
    })
    await waitFor(() => expect(window.location.pathname).not.toBe(`/notes/${id}`))
  })
})

describe('failures', () => {
  it('keeps edits through a lost connection and saves them once it is back', async () => {
    let id = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        id = await createNote(s, 'Offline', 'Start')
      },
    })
    await openTile(user, 'Offline')
    await waitFor(() => expect(bodyText()).toBe('Start'))

    server.setOffline(true)
    await typeAtEnd(user, ' typed offline')
    expect(await screen.findByText(/Couldn’t reach the server/)).toBeTruthy()
    expect(screen.getByText('Couldn’t save')).toBeTruthy()

    server.setOffline(false)
    await saved()
    expect(screen.queryByText(/Couldn’t reach the server/)).toBeNull()
    expect((await getNote(server, id)).content).toBe('Start typed offline\n')
  })

  it('replays a save whose response was lost instead of applying it twice', async () => {
    let id = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        id = await createNote(s, 'Replay', 'A')
      },
    })
    await openTile(user, 'Replay')
    await waitFor(() => expect(bodyText()).toBe('A'))

    server.loseNextResponse((method) => method === 'PUT')
    await typeAtEnd(user, 'B')
    await saved()

    const note = await getNote(server, id)
    expect(note.content).toBe('AB\n')
    // One update: the retry was answered from the idempotency record, not as a conflict.
    expect(note.revision).toBe(2)
    expect(screen.queryByText(/Changed on another device/)).toBeNull()
  })

  it('reports a save the server refuses instead of retrying it as offline', async () => {
    let id = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        id = await createNote(s, 'Refused', 'A')
      },
    })
    await openTile(user, 'Refused')

    // What a dev proxy that rewrites Host looks like to the server.
    server.setOrigin('http://localhost:5173')
    await typeAtEnd(user, 'B')

    expect(await screen.findByText(/The server didn’t accept this save: cross-origin request rejected/)).toBeTruthy()
    expect(screen.getByText('Couldn’t save')).toBeTruthy()
    expect(screen.queryByText(/Couldn’t reach the server/)).toBeNull()
    expect((await getNote(server, id)).content).toBe('A')

    server.setOrigin(server.url)
    await user.click(screen.getByRole('button', { name: 'Retry' }))
    await saved()
    expect((await getNote(server, id)).content).toBe('AB\n')
  })

  it('asks to sign in again when the session ends mid-edit, then saves', async () => {
    let id = ''
    const { server, user, password } = await signedIn({
      seed: async (s) => {
        id = await createNote(s, 'Session', 'Before')
      },
    })
    await openTile(user, 'Session')
    await waitFor(() => expect(bodyText()).toBe('Before'))

    // Revoke the browser's session from the other client.
    const { body: list } = await server.api<{ sessions: Session[]; currentId: string }>('GET', '/auth/sessions')
    const browserSession = list.sessions.find((s) => s.id !== list.currentId)
    await server.api('DELETE', `/auth/sessions/${browserSession?.id}`)

    await typeAtEnd(user, ' after')
    const dialog = await screen.findByRole('dialog', { name: 'Sign in again' })
    // The editor is still there underneath with the unsaved text.
    expect(bodyText()).toBe('Before after')

    await user.type(within(dialog).getByLabelText('Password'), `${password}{Enter}`)
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Sign in again' })).toBeNull())
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('Before after\n'))
    await saved()
  })
})

describe('deleting', () => {
  it('deletes after confirmation', async () => {
    const { server, user } = await signedIn({
      seed: async (s) => {
        await createNote(s, 'Keep')
        await createNote(s, 'Remove me')
      },
    })

    await openTile(user, 'Remove me')
    await user.click(screen.getByRole('button', { name: 'Delete' }))
    const dialog = await screen.findByRole('dialog', { name: 'Delete this note?' })
    await user.click(within(dialog).getByRole('button', { name: 'Delete note' }))

    await waitFor(() => expect(screen.queryByRole('link', { name: /Remove me/ })).toBeNull())
    expect((await listNotes(server)).map((n) => n.title)).toEqual(['Keep'])
  })
})

describe('the grid', () => {
  it('pages through more than 50 notes', async () => {
    const server = await startServer()
    const { username, password } = await createOwner(server)
    await server.api('POST', '/auth/login', { username, password, client: 'web' })
    for (let i = 0; i < 55; i++) await createNote(server, `Note ${i}`)
    const user = userEvent.setup()
    render(<App />)
    await user.type(await screen.findByLabelText('Username'), username)
    await user.type(screen.getByLabelText('Password'), `${password}{Enter}`)

    await waitFor(() => expect(screen.getAllByRole('link')).toHaveLength(50))
    await user.click(screen.getByRole('button', { name: 'Load more' }))
    await waitFor(() => expect(screen.getAllByRole('link')).toHaveLength(55))
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull()
    expect(screen.getAllByRole('link')[0]?.textContent).toMatch(/^Last edited/)
  })
})
