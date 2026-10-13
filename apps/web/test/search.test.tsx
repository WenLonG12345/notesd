import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { createNote, signedIn, titleInput } from './helpers'

async function withNotes() {
  return signedIn({
    seed: async (s) => {
      await createNote(s, 'Groceries', 'Oat milk and **bread**\n')
      await createNote(s, 'Trip', 'Book the room\n')
      await createNote(s, 'Milkshake recipe', 'Bananas\n')
    },
  })
}

const search = () => screen.getByRole('searchbox', { name: 'Search notes' })

describe('search', () => {
  it('shows matching notes with highlighted excerpts, prefix matching included', async () => {
    const { user } = await withNotes()
    await screen.findByRole('link', { name: /Trip/ })

    await user.type(search(), 'milk')

    expect(await screen.findByRole('heading', { name: 'Results' })).toBeTruthy()
    const list = await screen.findByRole('list', { name: 'Search results' })
    await waitFor(() => expect(within(list).getAllByRole('link')).toHaveLength(2))
    expect(screen.getByText('2 matches')).toBeTruthy()
    const groceries = within(list).getByRole('link', { name: /Groceries/ })
    expect(groceries.querySelector('mark')?.textContent?.toLowerCase()).toBe('milk')
    expect(within(list).queryByRole('link', { name: /Trip/ })).toBeNull()
  })

  it('opens a result', async () => {
    const { user } = await withNotes()
    await user.type(search(), 'room')
    const list = await screen.findByRole('list', { name: 'Search results' })
    await user.click(await within(list).findByRole('link', { name: /Trip/ }))
    await waitFor(() => expect(titleInput().value).toBe('Trip'))
  })

  it('says when nothing matches, and Escape clears the search', async () => {
    const { user } = await withNotes()
    await user.type(search(), 'zebra')
    expect(await screen.findByText('Nothing matches “zebra”.')).toBeTruthy()

    await user.keyboard('{Escape}')
    expect(search()).toHaveProperty('value', '')
    expect(await screen.findByRole('heading', { name: 'All notes' })).toBeTruthy()
  })

  it('treats search syntax in the input as plain words', async () => {
    const { user } = await withNotes()
    await user.type(search(), '"milk OR ( NEAR')
    expect(await screen.findByText(/^\d+\+? match(es)?$/)).toBeTruthy()
    expect(screen.queryByText('Search didn’t work.')).toBeNull()
  })
})
