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

/** Document model only: what Markdown parses into. Shared by the editor and the converter. */
export const schemaExtensions: Extensions = [
  Document,
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

/** The full editor: the model plus editing behaviour. Nothing outside spec §7.1. */
export const editorExtensions: Extensions = [
  ...schemaExtensions,
  ListKeymap,
  UndoRedo,
  Placeholder.configure({ placeholder: 'Start writing…' }),
]
