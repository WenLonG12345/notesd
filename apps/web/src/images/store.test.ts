import { Editor } from '@tiptap/core'
import { afterEach, describe, expect, it } from 'vitest'
import { editorExtensions } from '../editor/extensions'
import { noteContent, noteFields } from '../editor/note-doc'
import { insertImageAt } from './store'

let editor: Editor | undefined
afterEach(() => editor?.destroy())

const a = { id: 'a'.repeat(64), w: 4, h: 3 }
const b = { id: 'b'.repeat(64), w: 4, h: 3 }
const md = (ref: { id: string }) => `![](notesd:image/${ref.id}?w=4&h=3)`

describe('insertImageAt', () => {
  it('goes between blocks at a top-level position, as a drop there resolves', () => {
    editor = new Editor({ extensions: editorExtensions, content: noteContent({ title: 'T', content: 'One\n\nTwo\n' }) })
    // Between the title and the first body line: depth 0.
    const between = editor.state.doc.child(0).nodeSize
    expect(editor.state.doc.resolve(between).depth).toBe(0)
    insertImageAt(editor, between, a)
    expect(noteFields(editor.state.doc).content).toBe(`${md(a)}\n\nOne\n\nTwo\n`)
  })

  it('returns where the next picture goes, so several keep their order', () => {
    editor = new Editor({ extensions: editorExtensions, content: noteContent({ title: 'T', content: 'One\n\nTwo\n' }) })
    let pos = editor.state.doc.child(0).nodeSize + 2
    pos = insertImageAt(editor, pos, a)
    insertImageAt(editor, pos, b)
    expect(noteFields(editor.state.doc).content).toBe(`One\n\n${md(a)}\n\n${md(b)}\n\nTwo\n`)
  })
})
