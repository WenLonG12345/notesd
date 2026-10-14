import { Editor } from '@tiptap/core'
import { afterEach, describe, expect, it } from 'vitest'
import fixtures from '../../../../shared/markdown-fixtures/tags.json'
import { tags as markdownTags } from '../notes/tags'
import { editorExtensions } from './extensions'
import { noteContent, noteFields } from './note-doc'
import { docTags, insertTag, suggestTags, Tags } from './tags'

let editor: Editor | undefined
afterEach(() => editor?.destroy())

function open(title: string, content: string) {
  editor = new Editor({ extensions: [...editorExtensions, Tags], content: noteContent({ title, content }) })
  return editor
}

/** Names as the editor sees them: first spelling per key, like the Markdown extractor. */
function names(e: Editor) {
  const seen = new Set<string>()
  return docTags(e.state.doc)
    .map((t) => t.name)
    .filter((n) => !seen.has(n.toLowerCase()) && seen.add(n.toLowerCase()))
}

// Compared with the note as the editor saves it: the editor joins soft line
// breaks, so "#one\ntwo#" written elsewhere becomes "#one two#" once edited.
describe('editor tags match the shared extractor on what the editor saves', () => {
  for (const c of fixtures.cases) {
    it(c.name, () => {
      const e = open((c as { title?: string }).title ?? '', c.markdown)
      const saved = noteFields(e.state.doc)
      expect(names(e)).toEqual(markdownTags(saved.title, saved.content).map((t) => t.name))
    })
  }
})

describe('tag ranges', () => {
  it('cover the tag text, # marks included', () => {
    const e = open('Trip 🎉 #travel', 'See **#bold** and #summer trip# and [#link](https://x.y)\\\n#after')
    const doc = e.state.doc
    expect(docTags(doc).map((t) => doc.textBetween(t.from, t.to))).toEqual(['#travel', '#bold', '#summer trip#', '#after'])
  })

  it('are styled in the editor', () => {
    const e = open('', 'A #tag here')
    expect([...e.view.dom.querySelectorAll('[data-tag]')].map((el) => el.textContent)).toEqual(['#tag'])
  })
})

describe('suggestions', () => {
  const all = [
    { key: 'home', name: 'Home', count: 3 },
    { key: 'home/repairs', name: 'Home/Repairs', count: 1 },
    { key: 'work', name: 'Work', count: 5 },
    { key: 'homework', name: 'homework', count: 1 },
    { key: 'summer trip', name: 'summer trip', count: 2 },
  ]

  it('ranks whole-name prefixes, then level prefixes, then anything containing the query', () => {
    expect(suggestTags(all, 'ho').map((t) => t.key)).toEqual(['home', 'home/repairs', 'homework'])
    expect(suggestTags(all, 'rep').map((t) => t.key)).toEqual(['home/repairs'])
    expect(suggestTags(all, 'ork').map((t) => t.key)).toEqual(['work', 'homework'])
  })

  it('suggests nothing before a letter is typed, nor the tag already typed', () => {
    expect(suggestTags(all, '')).toEqual([])
    expect(suggestTags(all, 'work').map((t) => t.key)).toEqual(['homework'])
  })

  it('inserts the tag, closed when it has spaces', () => {
    const e = open('', 'Plan #sum')
    const end = e.state.doc.content.size - 1
    insertTag(e, { from: end - 4, to: end }, 'summer trip')
    expect(e.state.doc.lastChild?.textContent).toBe('Plan #summer trip# ')
  })
})

const click = (e: Editor, pos: number, init: MouseEventInit) =>
  e.view.someProp('handleClick', (f) => f(e.view, pos, new MouseEvent('click', init)))

describe('opening a tag', () => {
  it('calls onOpen on a modifier click inside a tag, and leaves plain clicks to editing', () => {
    const e = open('', 'See #home/repairs now')
    const opened: string[] = []
    e.storage.tags.set({ tags: [], onOpen: (name) => opened.push(name) })
    const [t] = docTags(e.state.doc)
    if (!t) throw new Error('no tag')
    const mod = /Mac|iPhone|iPad/.test(navigator.platform) ? { metaKey: true } : { ctrlKey: true }

    expect(click(e, t.from + 2, {})).toBeFalsy()
    expect(click(e, t.to + 2, mod)).toBeFalsy()
    expect(click(e, t.from + 2, mod)).toBe(true)
    expect(opened).toEqual(['home/repairs'])
  })
})
