import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { LockKey } from '../src/api/types'
import { LOCKED_PREFIX, decryptBody, fromBase64Url, importKey, passwordKek, unwrapRaw } from '../src/locks/crypto'
import { lockTiming } from '../src/locks/session'
import { bodyText, createNote, getNote, list, listNotes, noteTitle, saved, signedIn, typeAtEnd } from './helpers'
import type { TestServer } from './server'

const LOCK_PASSWORD = 'lock pass 123'
const moreMenu = () => screen.getByRole('menu', { name: 'More' })

/** Decrypts a note's stored body the way another device would: keyring from the server, then the password. */
async function readLocked(server: TestServer, id: string, password = LOCK_PASSWORD): Promise<string> {
  const { keys } = (await server.api<{ keys: LockKey[] }>('GET', '/locks')).body
  const entry = keys.find((k) => k.kind === 'password')
  if (!entry) throw new Error('no password entry')
  const kek = await passwordKek(password, fromBase64Url(entry.salt), entry.iterations)
  const key = await importKey(await unwrapRaw(kek, entry.wrapped, 'password'))
  return decryptBody(key, (await getNote(server, id)).content)
}

type Session = Awaited<ReturnType<typeof signedIn>>

/** Locks the open note from its menu, creating the lock password on first use. */
async function lockOpenNote({ user }: Session) {
  await user.click(await screen.findByRole('button', { name: 'More' }))
  await user.click(within(moreMenu()).getByRole('menuitem', { name: 'Lock note' }))
  const dialog = await screen.findByRole('dialog', { name: 'Create a lock password' })
  await user.type(within(dialog).getByLabelText('Lock password'), LOCK_PASSWORD)
  await user.type(within(dialog).getByLabelText('Confirm'), LOCK_PASSWORD)
  await user.click(within(dialog).getByRole('button', { name: 'Create and lock' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  await saved()
}

async function seeded() {
  let id = ''
  const session = await signedIn({ seed: async (s) => void (id = await createNote(s, 'Bank', 'PIN 1234 #money')) })
  await waitFor(() => expect(noteTitle()).toBe('Bank'))
  return { ...session, id }
}

describe('locked notes', () => {
  it('locking stores only ciphertext; the list and search show the title alone', async () => {
    const s = await seeded()
    await lockOpenNote(s)

    const stored = await getNote(s.server, s.id)
    expect(stored.content.startsWith(LOCKED_PREFIX)).toBe(true)
    expect(stored.content).not.toContain('1234')
    expect(await readLocked(s.server, s.id)).toBe('PIN 1234 #money\n')

    const [item] = await listNotes(s.server)
    expect(item).toMatchObject({ title: 'Bank', locked: true, preview: '' })
    expect(within(list()).getByRole('link', { name: /^Bank/ }).textContent).toContain('Locked')
    // The body's tag went with the body.
    expect((await s.server.api<{ tags: unknown[] }>('GET', '/tags')).body.tags).toEqual([])
    expect((await s.server.api<{ results: unknown[] }>('GET', '/search?q=1234')).body.results).toEqual([])
  })

  it('locks again on request; opens with the lock password; edits stay encrypted', async () => {
    const s = await seeded()
    await lockOpenNote(s)

    await s.user.click(screen.getByRole('button', { name: 'Lock now' }))
    expect(await screen.findByText('This note is locked.')).toBeTruthy()
    expect(screen.queryByRole('textbox', { name: 'Note' })).toBeNull()
    expect(screen.getByRole('heading', { name: 'Bank' })).toBeTruthy()

    await s.user.type(screen.getByLabelText('Lock password'), 'nope{Enter}')
    expect(await screen.findByText('Wrong password.')).toBeTruthy()
    await s.user.clear(screen.getByLabelText('Lock password'))
    await s.user.type(screen.getByLabelText('Lock password'), `${LOCK_PASSWORD}{Enter}`)
    await waitFor(() => expect(bodyText()).toBe('PIN 1234 #money'))

    await typeAtEnd(s.user, ' and 5678')
    await saved()
    const stored = await getNote(s.server, s.id)
    expect(stored.content.startsWith(LOCKED_PREFIX)).toBe(true)
    expect(await readLocked(s.server, s.id)).toBe('PIN 1234 #money and 5678\n')
  })

  it('locks by itself after a while idle', async () => {
    const s = await seeded()
    lockTiming.idleMs = 150
    await lockOpenNote(s)
    expect(await screen.findByText('This note is locked.', {}, { timeout: 2000 })).toBeTruthy()
  })

  it('removing the lock stores plain text again', async () => {
    const s = await seeded()
    await lockOpenNote(s)
    await s.user.click(screen.getByRole('button', { name: 'More' }))
    await s.user.click(within(moreMenu()).getByRole('menuitem', { name: 'Remove lock' }))
    await waitFor(async () => expect((await getNote(s.server, s.id)).content).toBe('PIN 1234 #money\n'))
    expect(screen.queryByRole('button', { name: 'Lock now' })).toBeNull()
  })

  it('changing the lock password re-wraps the same key: notes still open', async () => {
    const s = await seeded()
    await lockOpenNote(s)
    await s.user.click(screen.getByRole('button', { name: 'Settings' }))
    const card = await screen.findByRole('region', { name: 'Locked notes' })
    await s.user.type(within(card).getByLabelText('Current lock password'), LOCK_PASSWORD)
    await s.user.type(within(card).getByLabelText('New lock password'), 'brand new pass')
    await s.user.type(within(card).getByLabelText('Confirm new lock password'), 'brand new pass')
    await s.user.click(within(card).getByRole('button', { name: 'Change lock password' }))
    expect(await within(card).findByText(/^Lock password changed\./)).toBeTruthy()
    expect(await readLocked(s.server, s.id, 'brand new pass')).toBe('PIN 1234 #money\n')
  })

  it('a locked note can’t be emailed', async () => {
    const s = await seeded()
    await s.server.api('PUT', '/settings/email', { apiKey: 're_test_123456789', from: 'Me <notes@example.com>' })
    await lockOpenNote(s)
    await s.user.click(screen.getByRole('button', { name: 'More' }))
    expect(within(moreMenu()).queryByRole('menuitem', { name: 'Email…' })).toBeNull()
    expect(within(moreMenu()).getByRole('menuitem', { name: 'Remove lock' })).toBeTruthy()
  })
})
