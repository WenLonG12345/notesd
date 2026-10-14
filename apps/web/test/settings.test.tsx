import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { createNote, noteTitle, signedIn } from './helpers'

async function openSettings() {
  const ctx = await signedIn()
  await ctx.user.click(screen.getByRole('button', { name: 'Settings' }))
  await screen.findByRole('heading', { name: 'Settings' })
  return ctx
}

describe('settings', () => {
  it('switches the theme and remembers it', async () => {
    // index.html's; jsdom doesn't load it.
    const metas = (['light', 'dark'] as const).map((scheme) => {
      const meta = document.createElement('meta')
      meta.name = 'theme-color'
      meta.media = `(prefers-color-scheme: ${scheme})`
      document.head.append(meta)
      return meta
    })
    const colors = () => metas.map((m) => m.content)
    const { user } = await openSettings()
    const group = screen.getByRole('radiogroup', { name: 'Appearance' })
    expect(within(group).getByRole('radio', { name: 'System' }).getAttribute('aria-checked')).toBe('true')

    await user.click(within(group).getByRole('radio', { name: 'Dark' }))
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(localStorage.getItem('notes.theme')).toBe('dark')
    // The title bar follows the picked theme, whatever the system's.
    expect(colors()).toEqual(['#121416', '#121416'])

    await user.click(within(group).getByRole('radio', { name: 'System' }))
    expect(document.documentElement.dataset.theme).toBeUndefined()
    expect(localStorage.getItem('notes.theme')).toBeNull()
    expect(colors()).toEqual(['#F6F3EE', '#121416'])
    for (const m of metas) m.remove()
  })

  it('changes the password and signs out every other session', async () => {
    const { server, user, password } = await openSettings()

    await user.type(screen.getByLabelText('Current password'), 'not it')
    await user.type(screen.getByLabelText('New password'), 'a brand new one')
    await user.type(screen.getByLabelText('Confirm new password'), 'a brand new one')
    await user.click(screen.getByRole('button', { name: 'Change password' }))
    expect(await screen.findByText('That isn’t your current password.')).toBeTruthy()

    await user.clear(screen.getByLabelText('Current password'))
    await user.type(screen.getByLabelText('Current password'), password)
    await user.click(screen.getByRole('button', { name: 'Change password' }))
    expect(await screen.findByText(/Password changed\. Every other browser and device was signed out/)).toBeTruthy()

    // The other client was signed out; the new password works.
    expect((await server.api('GET', '/auth/session')).status).toBe(401)
    const login = await server.api('POST', '/auth/login', { username: 'me', password: 'a brand new one', client: 'web' })
    expect(login.status).toBe(200)
    // This browser stays signed in.
    expect(screen.getByRole('heading', { name: 'Settings' })).toBeTruthy()
  })

  it('lists signed-in devices and signs one out', async () => {
    const { server, user } = await openSettings()
    const devices = screen.getByRole('region', { name: 'Signed-in devices' })
    await within(devices).findByText('2 devices')
    expect(within(devices).getByText('This browser')).toBeTruthy()

    const other = within(devices).getByRole('button', { name: /^Sign out / })
    await user.click(other)
    await within(devices).findByText('1 device')
    expect((await server.api('GET', '/auth/session')).status).toBe(401)
  })
})

describe('closing settings', () => {
  it('returns to the note that was open behind it', async () => {
    const { user } = await signedIn({
      seed: async (s) => {
        await createNote(s, 'Behind')
      },
    })
    await waitFor(() => expect(noteTitle()).toBe('Behind'))
    const path = window.location.pathname
    await user.click(screen.getByRole('button', { name: 'Settings' }))
    expect(noteTitle()).toBe('Behind')

    await user.click(await screen.findByRole('button', { name: 'Done' }))
    expect(window.location.pathname).toBe(path)
    expect(screen.queryByRole('heading', { name: 'Settings' })).toBeNull()
  })
})

describe('connection', () => {
  it('shows a quiet banner while the server is unreachable', async () => {
    const { server, user } = await signedIn()
    server.setOffline(true)
    await user.type(screen.getByRole('searchbox', { name: 'Search notes' }), 'x')
    expect(await screen.findByText(/No connection to the server/)).toBeTruthy()

    server.setOffline(false)
    window.dispatchEvent(new Event('online'))
    await waitFor(() => expect(screen.queryByText(/No connection to the server/)).toBeNull())
  })
})
