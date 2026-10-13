import { Node, type Extensions } from '@tiptap/core'
import { Blockquote } from '@tiptap/extension-blockquote'
import { Bold } from '@tiptap/extension-bold'
import { Document } from '@tiptap/extension-document'
import { HardBreak } from '@tiptap/extension-hard-break'
import { Heading } from '@tiptap/extension-heading'
import { Italic } from '@tiptap/extension-italic'
import { Link } from '@tiptap/extension-link'
import { BulletList, ListItem, ListKeymap, OrderedList, TaskItem, TaskList } from '@tiptap/extension-list'
import { Paragraph } from '@tiptap/extension-paragraph'
import { Strike } from '@tiptap/extension-strike'
import { Text } from '@tiptap/extension-text'
import { Placeholder, UndoRedo } from '@tiptap/extensions'

const linkProtocols = /^(https?:|mailto:)/i

export function isAllowedLink(url: string): boolean {
  return linkProtocols.test(url.trim())
}

/**
 * Markdown the editor can't represent (code, tables, images, rules…), kept
 * as its exact source: shown read-only and written back unchanged, so a
 * save never corrupts a note written on another device (spec §7.1).
 */
export const RawBlock = Node.create({
  name: 'rawBlock',
  group: 'block',
  atom: true,
  selectable: true,
  draggable: false,
  addAttributes() {
    return { source: { default: '' } }
  },
  parseHTML() {
    return [{ tag: 'pre[data-raw]', getAttrs: (el) => ({ source: el.textContent ?? '' }) }]
  },
  renderHTML({ node }) {
    return [
      'pre',
      { 'data-raw': '', contenteditable: 'false', title: 'Not editable here; kept exactly as written' },
      node.attrs.source as string,
    ]
  },
})

export const RawInline = Node.create({
  name: 'rawInline',
  group: 'inline',
  inline: true,
  atom: true,
  selectable: true,
  addAttributes() {
    return { source: { default: '' } }
  },
  parseHTML() {
    return [{ tag: 'code[data-raw]', getAttrs: (el) => ({ source: el.textContent ?? '' }) }]
  },
  renderHTML({ node }) {
    return ['code', { 'data-raw': '', contenteditable: 'false' }, node.attrs.source as string]
  },
})

/**
 * The note's first line, stored as its own plain-text title field, not in
 * the Markdown. Marks aren't allowed: a title has no formatting.
 */
const Title = Node.create({
  name: 'title',
  content: 'text*',
  marks: '',
  defining: true,
  parseHTML() {
    return [{ tag: 'h1[data-title]', priority: 60 }]
  },
  renderHTML() {
    return ['h1', { 'data-title': '' }, 0]
  },
})

const blockExtensions: Extensions = [
  Paragraph,
  Text,
  HardBreak,
  Bold,
  Italic,
  Strike,
  Heading.configure({ levels: [1, 2, 3] }),
  BulletList,
  OrderedList,
  ListItem,
  TaskList,
  TaskItem.configure({ nested: true }),
  Blockquote,
  Link.configure({
    openOnClick: false,
    autolink: true,
    linkOnPaste: true,
    protocols: ['mailto'],
    defaultProtocol: 'https',
    isAllowedUri: (url) => isAllowedLink(url),
  }),
  RawBlock,
  RawInline,
]

/** Document model only: what Markdown parses into. */
export const schemaExtensions: Extensions = [Document, ...blockExtensions]

/** The editor: a title line, then the body, plus editing behaviour. Nothing outside spec §7.1. */
export const editorExtensions: Extensions = [
  Document.extend({ content: 'title block+' }),
  Title,
  ...blockExtensions.map((e) =>
    e.name === 'taskList'
      ? TaskList.extend({
          addKeyboardShortcuts() {
            return { ...this.parent?.(), 'Mod-Shift-l': () => this.editor.commands.toggleTaskList() }
          },
        })
      : e,
  ),
  ListKeymap,
  UndoRedo,
  Placeholder.configure({
    showOnlyCurrent: false,
    placeholder: ({ node }) => (node.type.name === 'title' ? 'New note' : ''),
  }),
]
