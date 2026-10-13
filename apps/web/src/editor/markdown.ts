import { getSchema } from '@tiptap/core'
import type { Node as PMNode, Schema } from '@tiptap/pm/model'
import MarkdownIt from 'markdown-it'
import type StateBlock from 'markdown-it/lib/rules_block/state_block.mjs'
import type StateCore from 'markdown-it/lib/rules_core/state_core.mjs'
import type StateInline from 'markdown-it/lib/rules_inline/state_inline.mjs'
import type Token from 'markdown-it/lib/token.mjs'
import {
  defaultMarkdownSerializer,
  MarkdownParser,
  MarkdownSerializer,
  type MarkdownSerializerState,
} from 'prosemirror-markdown'
import { schemaExtensions } from './extensions'

// Markdown ⇄ ProseMirror for the restricted dialect (spec §7.1, §7.4).
// Parse once when a note opens; serialize only when saving.

export const schema: Schema = getSchema(schemaExtensions)

// --- parsing -----------------------------------------------------------------

type BlockRule = (state: StateBlock, startLine: number, endLine: number, silent: boolean) => boolean
type InlineRule = (state: StateInline, silent: boolean) => boolean

// markdown-it doesn't export its built-in rules; the ruler's list is the only handle.
function builtin<T>(ruler: unknown, name: string): T | undefined {
  const rules = (ruler as Record<string, { name: string; fn: T }[]>)['__rules__'] ?? []
  return rules.find((r) => r.name === name)?.fn
}

function createTokenizer(): MarkdownIt {
  // 'default' preset: CommonMark + GFM tables and strikethrough. No raw HTML,
  // no linkify (plain URLs stay text unless written as links), no typography.
  const md = new MarkdownIt('default', { html: false, linkify: false, breaks: false, typographer: false })

  // Blocks outside the dialect become raw_block tokens with their exact source.
  // getLines() strips container prefixes ("> ", list indent), so it re-nests.
  for (const name of ['fence', 'code', 'hr', 'table']) {
    const original = builtin<BlockRule>(md.block.ruler, name)
    if (!original) continue
    md.block.ruler.at(name, (state, startLine, endLine, silent) => {
      if (silent) return original(state, startLine, endLine, true)
      const before = state.tokens.length
      if (!original(state, startLine, endLine, false)) return false
      state.tokens.length = before
      const token = state.push('raw_block', '', 0)
      token.content = state.getLines(startLine, state.line, state.blkIndent, false).replace(/\n$/, '')
      token.map = [startLine, state.line]
      token.block = true
      return true
    })
  }

  // Same for inline constructs: code spans and images.
  for (const name of ['backticks', 'image']) {
    const original = builtin<InlineRule>(md.inline.ruler, name)
    if (!original) continue
    md.inline.ruler.at(name, (state, silent) => {
      if (silent) return original(state, true)
      const start = state.pos
      // Flush preceding text first, or truncating below would drop it.
      if (state.pending) state.pushPending()
      const before = state.tokens.length
      if (!original(state, false)) return false
      state.tokens.length = before
      const token = state.push('raw_inline', '', 0)
      token.content = state.src.slice(start, state.pos)
      return true
    })
  }

  md.core.ruler.after('inline', 'task_lists', taskLists)
  return md
}

const taskPrefix = /^\[([ xX])\](?: |$)/

/** GFM task lists. A list mixing tasks and plain items stays a bullet list (no node for that). */
function taskLists(state: StateCore) {
  const tokens = state.tokens
  for (let i = 0; i < tokens.length; i++) {
    const open = tokens[i]
    if (open?.type !== 'bullet_list_open') continue
    const close = matchingClose(tokens, i)
    const items = directChildren(tokens, i, close, 'list_item_open')
    const firstInline = items.map((at) => tokens[at + 2])
    const allTasks =
      items.length > 0 &&
      items.every((at) => {
        const inline = tokens[at + 2]
        return tokens[at + 1]?.type === 'paragraph_open' && inline?.type === 'inline' && taskPrefix.test(inline.content)
      })
    if (!allTasks) continue

    open.type = 'task_list_open'
    const closeToken = tokens[close]
    if (closeToken) closeToken.type = 'task_list_close'
    items.forEach((at, n) => {
      const itemOpen = tokens[at]
      const inline = firstInline[n]
      if (!itemOpen || !inline) return
      const itemClose = tokens[matchingClose(tokens, at)]
      const match = taskPrefix.exec(inline.content)
      itemOpen.type = 'task_item_open'
      itemOpen.attrSet('checked', match?.[1] === ' ' ? 'false' : 'true')
      if (itemClose) itemClose.type = 'task_item_close'
      const strip = match?.[0].length ?? 0
      inline.content = inline.content.slice(strip)
      const first = inline.children?.[0]
      if (first?.type === 'text') first.content = first.content.slice(strip)
    })
  }
}

function matchingClose(tokens: Token[], openAt: number): number {
  let depth = 0
  for (let i = openAt; i < tokens.length; i++) {
    depth += tokens[i]?.nesting ?? 0
    if (depth === 0) return i
  }
  return tokens.length - 1
}

function directChildren(tokens: Token[], openAt: number, closeAt: number, type: string): number[] {
  const out: number[] = []
  let depth = 0
  for (let i = openAt + 1; i < closeAt; i++) {
    const t = tokens[i]
    if (!t) continue
    if (depth === 0 && t.type === type) out.push(i)
    depth += t.nesting
  }
  return out
}

const parser = new MarkdownParser(schema, createTokenizer(), {
  paragraph: { block: 'paragraph' },
  heading: {
    block: 'heading',
    // Levels 4–6 aren't in the dialect; they read as the smallest heading.
    getAttrs: (tok) => ({ level: Math.min(Number(tok.tag.slice(1)), 3) }),
  },
  blockquote: { block: 'blockquote' },
  bullet_list: { block: 'bulletList' },
  ordered_list: { block: 'orderedList', getAttrs: (tok) => ({ start: Number(tok.attrGet('start') ?? 1) }) },
  list_item: { block: 'listItem' },
  task_list: { block: 'taskList' },
  task_item: { block: 'taskItem', getAttrs: (tok) => ({ checked: tok.attrGet('checked') === 'true' }) },
  hardbreak: { node: 'hardBreak' },
  raw_block: { node: 'rawBlock', noCloseToken: true, getAttrs: (tok) => ({ source: tok.content }) },
  raw_inline: { node: 'rawInline', noCloseToken: true, getAttrs: (tok) => ({ source: tok.content }) },
  em: { mark: 'italic' },
  strong: { mark: 'bold' },
  s: { mark: 'strike' },
  link: {
    mark: 'link',
    getAttrs: (tok) => ({ href: tok.attrGet('href'), title: tok.attrGet('title') || null }),
  },
})

/** Parses stored Markdown into an editor document. Never throws on odd input. */
export function parse(markdown: string): PMNode {
  return parser.parse(markdown.replace(/\r\n?/g, '\n'))
}

// --- serializing -------------------------------------------------------------

const base = defaultMarkdownSerializer

function required<T>(value: T | undefined, what: string): T {
  if (value === undefined) throw new Error(`prosemirror-markdown has no default ${what}`)
  return value
}

type ListArgs = { state: MarkdownSerializerState; node: PMNode; parent: PMNode; index: number }

function renderList({ state, node, parent, index }: ListArgs, indent: number, marker: (i: number) => string) {
  // renderList() separates a list from a same-type predecessor with an extra
  // blank line; alternate() already keeps them apart, so close normally first.
  if (index > 0 && parent.child(index - 1).type === node.type) {
    ;(state as unknown as { flushClose: (size: number) => void }).flushClose(2)
  }
  state.renderList(node, ' '.repeat(indent), marker)
}

/**
 * Markdown merges adjacent lists that use the same marker, so a list right
 * after a sibling list of its kind switches marker ("-" ↔ "*", "." ↔ ")").
 */
// Bullet and task lists share the "-" marker, so they count as one kind.
const listKind = (n: PMNode) => (n.type.name === 'taskList' ? 'bulletList' : n.type.name)

function alternate(parent: PMNode, index: number, node: PMNode): boolean {
  let flip = false
  for (let i = index - 1; i >= 0 && listKind(parent.child(i)) === listKind(node); i--) flip = !flip
  return flip
}

const serializer = new MarkdownSerializer(
  {
    doc: (state, node) => state.renderContent(node),
    // The editor's first line is the note's title, stored in its own field.
    title: () => undefined,
    paragraph: required(base.nodes.paragraph, 'paragraph'),
    text: required(base.nodes.text, 'text'),
    blockquote: required(base.nodes.blockquote, 'blockquote'),
    heading: required(base.nodes.heading, 'heading'),
    hardBreak: required(base.nodes.hard_break, 'hard_break'),
    bulletList: (state, node, parent, index) => {
      const bullet = alternate(parent, index, node) ? '* ' : '- '
      renderList({ state, node, parent, index }, 2, () => bullet)
    },
    orderedList: (state, node, parent, index) => {
      const start = Number(node.attrs.start ?? 1)
      const delim = alternate(parent, index, node) ? ') ' : '. '
      // Continuation lines indent to the content column of the widest number.
      const width = String(start + node.childCount - 1).length + 2
      renderList({ state, node, parent, index }, width, (i) => `${start + i}${delim}`)
    },
    listItem: (state, node) => state.renderContent(node),
    taskList: (state, node, parent, index) => {
      const bullet = alternate(parent, index, node) ? '*' : '-'
      // Nested content aligns after the bullet: "[ ]" is part of the item's text.
      renderList({ state, node, parent, index }, 2, (i) => (node.child(i).attrs.checked ? `${bullet} [x] ` : `${bullet} [ ] `))
    },
    taskItem: (state, node) => state.renderContent(node),
    rawBlock: (state, node) => {
      state.text(String(node.attrs.source), false)
      state.closeBlock(node)
    },
    rawInline: (state, node) => {
      state.text(String(node.attrs.source), false)
    },
  },
  {
    italic: { open: '*', close: '*', mixable: true, expelEnclosingWhitespace: true },
    bold: { open: '**', close: '**', mixable: true, expelEnclosingWhitespace: true },
    strike: { open: '~~', close: '~~', mixable: true, expelEnclosingWhitespace: true },
    link: required(base.marks.link, 'link'),
  },
  // tightLists is a real option, missing from the published types.
  { tightLists: true, hardBreakNodeName: 'hardBreak' } as ConstructorParameters<typeof MarkdownSerializer>[2],
)

/** Deterministic output (spec §7.4): no trailing whitespace, one final newline, "" when empty. */
export function serialize(doc: PMNode): string {
  const out = serializer
    .serialize(doc)
    .split('\n')
    .map((line) => line.replace(/[ \t]+$/, ''))
    .join('\n')
    // Not trim(): an indented code block at the very start keeps its indent.
    .replace(/^\n+/, '')
    .replace(/\s+$/, '')
  return out ? `${out}\n` : ''
}

/** The canonical form of stored Markdown: what this editor would save for it. */
export function canonical(markdown: string): string {
  return serialize(parse(markdown))
}
