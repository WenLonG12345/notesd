import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { App } from '../src/app'
import { createOwner, startServer } from './server'

async function signedInAs(username: string) {
  expect(await screen.findByRole('heading', { name: 'Notes', level: 1 })).toBeTruthy()
  await userEvent.click(screen.getByRole('button', { name: 'Settings' }))
  expect(await screen.findByText(`Signed in as ${username}`)).toBeTruthy()
  await userEvent.click(screen.getByRole('button', { name: 'Done' }))
}

describe('first run', () => {
  it('creates the owner account and lands signed in', async () => {
    const server = await startServer()
    const user = userEvent.setup()
    render(<App />)

    expect(await screen.findByText(/^Step 1 of 3/)).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Get started' }))

    expect(screen.getByText(/^Step 2 of 3/)).toBeTruthy()
    await user.type(screen.getByLabelText('Setup token'), `${server.setupToken}{Enter}`)

    expect(screen.getByText(/^Step 3 of 3/)).toBeTruthy()
    await user.type(screen.getByLabelText('Username'), 'cassio')
    await user.type(screen.getByLabelText('Password'), 'correct horse')
    await user.type(screen.getByLabelText('Confirm password'), 'correct horse{Enter}')

    await signedInAs('cassio')
    expect(await screen.findByText('You’re all set, cassio')).toBeTruthy()
    expect((await server.api('GET', '/auth/status')).body).toEqual({ setupRequired: false })
  })

  it('checks the form before calling the server', async () => {
    await startServer()
    const user = userEvent.setup()
    render(<App />)

    await user.click(await screen.findByRole('button', { name: 'Get started' }))
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    expect(screen.getByText('Paste the setup token from the server log.')).toBeTruthy()

    await user.type(screen.getByLabelText('Setup token'), 'anything{Enter}')
    await user.type(screen.getByLabelText('Password'), 'short{Enter}')
    expect(screen.getByText('Choose a username.')).toBeTruthy()
    expect(screen.getByText('Use at least 8 characters.')).toBeTruthy()

    await user.clear(screen.getByLabelText('Password'))
    await user.type(screen.getByLabelText('Password'), 'long enough')
    await user.type(screen.getByLabelText('Confirm password'), 'different!{Enter}')
    expect(screen.getByText("The passwords don't match.")).toBeTruthy()
  })

  it('sends a wrong token back to the token step, keeping what was typed', async () => {
    const server = await startServer()
    const user = userEvent.setup()
    render(<App />)

    await user.click(await screen.findByRole('button', { name: 'Get started' }))
    await user.type(screen.getByLabelText('Setup token'), 'not-the-token{Enter}')
    await user.type(screen.getByLabelText('Username'), 'cassio')
    await user.type(screen.getByLabelText('Password'), 'correct horse')
    await user.type(screen.getByLabelText('Confirm password'), 'correct horse{Enter}')

    expect(await screen.findByText("That token didn't match. Copy it from the server log again.")).toBeTruthy()
    const token = screen.getByLabelText('Setup token')
    await user.clear(token)
    await user.type(token, `${server.setupToken}{Enter}`)
    expect(screen.getByLabelText<HTMLInputElement>('Username').value).toBe('cassio')
    await user.click(screen.getByRole('button', { name: 'Create account' }))
    await signedInAs('cassio')
  })

  it("shows the server's field errors inline", async () => {
    const server = await startServer()
    const user = userEvent.setup()
    render(<App />)

    await user.click(await screen.findByRole('button', { name: 'Get started' }))
    await user.type(screen.getByLabelText('Setup token'), `${server.setupToken}{Enter}`)
    await user.type(screen.getByLabelText('Username'), 'cassio')
    const tooLong = 'x'.repeat(1025)
    await user.click(screen.getByLabelText('Password'))
    await user.paste(tooLong)
    await user.click(screen.getByLabelText('Confirm password'))
    await user.paste(tooLong)
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    const password = screen.getByLabelText('Password')
    await waitFor(() => expect(password.getAttribute('aria-invalid')).toBe('true'))
    expect(screen.getByText(/^Must be at least 8 characters/)).toBeTruthy()
  })

  it('switches to sign-in when another client finished setup first', async () => {
    const server = await startServer()
    const user = userEvent.setup()
    render(<App />)

    await user.click(await screen.findByRole('button', { name: 'Get started' }))
    await user.type(screen.getByLabelText('Setup token'), `${server.setupToken}{Enter}`)
    await createOwner(server)
    await user.type(screen.getByLabelText('Username'), 'cassio')
    await user.type(screen.getByLabelText('Password'), 'correct horse')
    await user.type(screen.getByLabelText('Confirm password'), 'correct horse{Enter}')

    expect(await screen.findByRole('heading', { name: 'Welcome back.' })).toBeTruthy()
  })

  it('goes back through the steps', async () => {
    await startServer()
    const user = userEvent.setup()
    render(<App />)

    await user.click(await screen.findByRole('button', { name: 'Get started' }))
    await user.type(screen.getByLabelText('Setup token'), 'tok{Enter}')
    await user.click(screen.getByRole('button', { name: 'Back' }))
    expect(screen.getByLabelText<HTMLInputElement>('Setup token').value).toBe('tok')
    expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Paste your setup token' }))
    await user.click(screen.getByRole('button', { name: 'Back' }))
    expect(screen.getByRole('heading', { name: 'A quiet place for everything you write.' })).toBeTruthy()
  })
})

describe('sign in', () => {
  it('rejects a wrong password, then signs in and out', async () => {
    const server = await startServer()
    const { username, password } = await createOwner(server)
    const user = userEvent.setup()
    const first = render(<App />)

    await user.type(await screen.findByLabelText('Username'), username)
    await user.type(screen.getByLabelText('Password'), 'wrong password{Enter}')
    expect(await screen.findByText('Wrong username or password.')).toBeTruthy()

    await user.clear(screen.getByLabelText('Password'))
    await user.type(screen.getByLabelText('Password'), `${password}{Enter}`)
    await signedInAs(username)

    await user.click(screen.getByRole('button', { name: 'Settings' }))
    await user.click(await screen.findByRole('button', { name: 'Sign out' }))
    expect(await screen.findByRole('heading', { name: 'Welcome back.' })).toBeTruthy()
    // The session is gone: a reload lands on sign-in again.
    first.unmount()
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Welcome back.' })).toBeTruthy()
  })

  it('keeps the session across a reload', async () => {
    const server = await startServer()
    const { username, password } = await createOwner(server)
    const user = userEvent.setup()
    const first = render(<App />)

    await user.type(await screen.findByLabelText('Username'), username)
    await user.type(screen.getByLabelText('Password'), `${password}{Enter}`)
    await signedInAs(username)

    first.unmount()
    render(<App />)
    await signedInAs(username)
  })

  it('reports rate limiting with the wait time', async () => {
    const server = await startServer()
    await createOwner(server)
    const user = userEvent.setup()
    render(<App />)

    await user.type(await screen.findByLabelText('Username'), 'me')
    for (let i = 0; i < 10; i++) {
      await user.clear(screen.getByLabelText('Password'))
      await user.type(screen.getByLabelText('Password'), `wrong ${i}{Enter}`)
      // The label reads "Signing in…" until the server has answered.
      await screen.findByRole('button', { name: 'Sign in' })
    }
    await user.type(screen.getByLabelText('Password'), '!{Enter}')
    expect(await screen.findByText(/^Too many attempts\. Try again in \d+ seconds\.$/)).toBeTruthy()
  })
})

describe('server down', () => {
  it('says so and offers a retry', async () => {
    const server = await startServer()
    await server.stop()
    const user = userEvent.setup()
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Can’t reach the server' })).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('heading', { name: 'Can’t reach the server' })).toBeTruthy()
  })
})
