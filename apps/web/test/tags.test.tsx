import { screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import {
  bodyText,
  createNote,
  list,
  listNotes,
  newNoteButton,
  noteTitle,
  openNote,
  row,
  signedIn,
  typeAtEnd,
  typeInTitle,
} from './helpers'

beforeEach(() => localStorage.clear())

async function withTags() {
  return signedIn({
    seed: async (s) => {
      await createNote(s, 'Roof', 'Call about #home/repairs today\n')
      await createNote(s, 'Garden', 'Plant bulbs #Home\n')
      await createNote(s, 'Standup', 'Notes #work\n')
      await createNote(s, 'Loose', 'No tags here\n')
    },
  })
}

const tags = () => within(list()).getByRole('list', { name: 'Tags' })
const tagRow = (name: string) => within(tags()).getByRole('button', { name: new RegExp(`^#${name},`, 'i') })
const rowTitles = () =>
  within(list())
    .queryAllByRole('link')
    .map((a) => a.querySelector('span')?.textContent)

describe('tags in the sidebar', () => {
  it('lists tags with counts, nested ones under their parent', async () => {
    const { user } = await withTags()
    await within(list()).findByRole('list', { name: 'Tags' })

    expect(tagRow('home').getAttribute('aria-label')).toBe('#Home, 2 notes')
    expect(tagRow('work').getAttribute('aria-label')).toBe('#work, 1 note')
    expect(within(tags()).queryByRole('button', { name: /^#home\/repairs/ })).toBeNull()

    await user.click(within(tags()).getByRole('button', { name: 'Expand Home' }))
    expect(within(tags()).getByRole('button', { name: /^#home\/repairs, 1 note/ })).toBeTruthy()
  })

  it('filters the list by a tag and its nested tags, and back to all notes', async () => {
    const { user } = await withTags()
    await user.click(await waitFor(() => tagRow('home')))

    expect(await within(list()).findByRole('heading', { name: /#Home/ })).toBeTruthy()
    await waitFor(() => expect(rowTitles().toSorted()).toEqual(['Garden', 'Roof']))
    expect(within(list()).getByText('2 notes')).toBeTruthy()
    expect(screen.getByRole('searchbox', { name: 'Search notes' }).getAttribute('placeholder')).toBe('Search in #Home')

    await user.click(within(list()).getByRole('button', { name: 'Untagged' }))
    await waitFor(() => expect(rowTitles()).toEqual(['Loose']))

    await user.click(within(list()).getByRole('button', { name: 'Show all notes' }))
    await waitFor(() => expect(rowTitles()).toHaveLength(4))
  })

  it('searches within the selected tag', async () => {
    const { user } = await withTags()
    await user.click(await waitFor(() => tagRow('work')))
    await within(list()).findByRole('heading', { name: /#work/ })
    await user.type(screen.getByRole('searchbox', { name: 'Search notes' }), 'notes')
    const results = await screen.findByRole('list', { name: 'Search results' })
    await waitFor(() => expect(within(results).getAllByRole('link')).toHaveLength(1))
  })

  it('updates as notes gain and lose tags', async () => {
    const { user } = await withTags()
    await openNote(user, 'Loose')
    await typeAtEnd(user, ' #errands')
    await waitFor(() => expect(tagRow('errands')).toBeTruthy(), { timeout: 3000 })

    // Filtered, a note that loses the tag leaves the list.
    await user.click(tagRow('errands'))
    await waitFor(() => expect(rowTitles()).toEqual(['Loose']))
    await typeAtEnd(user, '{Backspace}'.repeat(' #errands'.length))
    await waitFor(() => expect(rowTitles()).toEqual([]), { timeout: 3000 })
  })
})

describe('new note in a tag', () => {
  it('starts with the tag, and is dropped if nobody types', async () => {
    const { server, user } = await withTags()
    await user.click(await waitFor(() => tagRow('work')))
    await within(list()).findByRole('heading', { name: /#work/ })

    await user.click(newNoteButton())
    await waitFor(() => expect(bodyText()).toBe('#work'))
    await user.click(row('Standup'))
    await waitFor(() => expect(within(list()).queryByRole('link', { name: /^New note/ })).toBeNull())
    expect(await listNotes(server)).toHaveLength(4)

    await user.click(newNoteButton())
    await typeInTitle(user, 'Retro')
    await waitFor(async () => {
      const retro = (await server.api<{ notes: { title: string }[] }>('GET', '/notes?tag=work')).body.notes
      expect(retro.map((n) => n.title).toSorted()).toEqual(['Retro', 'Standup'])
    })
    expect(noteTitle()).toBe('Retro')
  })
})

describe('typing a tag', () => {
  it('suggests existing tags and inserts the chosen one', async () => {
    const { user } = await withTags()
    await openNote(user, 'Loose')
    await waitFor(() => tagRow('home'))

    await typeAtEnd(user, ' #rep')
    const options = await screen.findByRole('listbox', { name: 'Tags' })
    expect(within(options).getAllByRole('option').map((o) => o.textContent)).toEqual(['#home/repairs'])
    await user.keyboard('{Enter}')
    await waitFor(() => expect(bodyText()).toBe('No tags here #home/repairs '))
  })
})
