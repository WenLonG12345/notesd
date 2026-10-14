import { Extension, type Editor, type Range } from '@tiptap/core'
import type { Node as PMNode } from '@tiptap/pm/model'
import { Plugin, PluginKey } from '@tiptap/pm/state'
import { Decoration, DecorationSet } from '@tiptap/pm/view'
import { Suggestion, type SuggestionKeyDownProps, type SuggestionProps } from '@tiptap/suggestion'
import type { TagCount } from '../api/types'
import { OBJECT } from '../notes/plaintext'
import { scanTags, tagText } from '../notes/tags'

export type TagRange = { from: number; to: number; name: string }

/**
 * The tags in one textblock at pos, found as the shared extractor finds them
 * in Markdown: link text and inline atoms (code) can't hold tags, and hard
 * breaks end lines. Each node keeps its size in the scanned text, so offsets
 * map straight back to document positions.
 */
export function blockTags(block: PMNode, pos: number): TagRange[] {
  let text = ''
  block.forEach((child) => {
    if (child.isText) {
      const t = child.text ?? ''
      text += child.marks.some((m) => m.type.name === 'link') ? OBJECT.repeat(t.length) : t
    } else {
      text += child.type.name === 'hardBreak' ? '\n' : OBJECT.repeat(child.nodeSize)
    }
  })
  const out: TagRange[] = []
  let offset = pos + 1
  for (const line of text.split('\n')) {
    for (const m of scanTags(line)) out.push({ from: offset + m.from, to: offset + m.to, name: m.name })
    offset += line.length + 1
  }
  return out
}

export function docTags(doc: PMNode): TagRange[] {
  const out: TagRange[] = []
  doc.descendants((node, pos) => {
    if (!node.isTextblock) return true
    out.push(...blockTags(node, pos))
    return false
  })
  return out
}

const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)

function decorate(doc: PMNode): DecorationSet {
  const hint = isMac ? '⌘-click' : 'Ctrl+click'
  return DecorationSet.create(
    doc,
    docTags(doc).map((t) =>
      Decoration.inline(t.from, t.to, { 'data-tag': '', title: `${hint} to show notes tagged #${t.name}` }, { name: t.name }),
    ),
  )
}

/** At most this many suggestions while typing a tag. */
const SUGGESTIONS = 8

/** Existing tags for what's typed after #: whole-name prefix first, then any level's prefix, then anywhere. */
export function suggestTags(tags: TagCount[], query: string): TagCount[] {
  const q = query.toLowerCase()
  if (!q) return []
  const rank = (t: TagCount) =>
    t.key.startsWith(q) ? 0 : t.key.split('/').some((level) => level.startsWith(q)) ? 1 : t.key.includes(q) ? 2 : 3
  return tags
    .map((t) => ({ t, r: rank(t) }))
    .filter(({ t, r }) => r < 3 && t.key !== q)
    .toSorted((a, b) => a.r - b.r || b.t.count - a.t.count || (a.t.key < b.t.key ? -1 : 1))
    .slice(0, SUGGESTIONS)
    .map(({ t }) => t)
}

/** Replaces the typed #query with the chosen tag, closed if it needs it, and a space. */
export function insertTag(editor: Editor, range: Range, name: string) {
  const after = editor.state.doc.textBetween(range.to, Math.min(range.to + 1, editor.state.doc.content.size - 1), '\n', OBJECT)
  editor
    .chain()
    .focus()
    .insertContentAt(range, `${tagText(name)}${after === ' ' ? '' : ' '}`)
    .run()
}

type TagsContext = {
  /** Tags to suggest. */
  tags: TagCount[]
  /** ⌘/Ctrl-click on a tag. */
  onOpen: (name: string) => void
}

export type TagsStorage = TagsContext & {
  /** Called by the editor's owner as tags and handlers change. */
  set: (context: TagsContext) => void
}

declare module '@tiptap/core' {
  interface Storage {
    tags: TagsStorage
  }
}

const decorations = new PluginKey<DecorationSet>('tags')

/** Styles #tags, opens them on ⌘/Ctrl-click and suggests existing ones while typing. */
export const Tags = Extension.create<object, TagsStorage>({
  name: 'tags',

  addStorage() {
    return {
      tags: [],
      onOpen: () => undefined,
      set(this: TagsStorage, context: TagsContext) {
        Object.assign(this, context)
      },
    }
  },

  addProseMirrorPlugins() {
    // Read at use: it changes with the editor's owner.
    const storage = this.storage
    const style = new Plugin<DecorationSet>({
      key: decorations,
      state: {
        init: (_, state) => decorate(state.doc),
        apply: (tr, set) => (tr.docChanged ? decorate(tr.doc) : set),
      },
      props: {
        decorations: (state) => decorations.getState(state),
        handleClick: (view, pos, event) => {
          if (!(isMac ? event.metaKey : event.ctrlKey)) return false
          const hit = decorations.getState(view.state)?.find(pos, pos)[0]
          if (!hit) return false
          storage.onOpen((hit.spec as { name: string }).name)
          return true
        },
      },
    })

    const suggest = Suggestion<TagCount, TagCount>({
      editor: this.editor,
      pluginKey: new PluginKey('tagSuggestion'),
      char: '#',
      allowedPrefixes: [' ', '(', '[', '{'],
      items: ({ query }) => suggestTags(storage.tags, query),
      command: ({ editor, range, props }) => insertTag(editor, range, props.name),
      render: renderMenu,
    })
    return [style, suggest]
  },
})

/** The suggestion list: a listbox under the caret, driven from the editor's keyboard. */
function renderMenu() {
  let menu: HTMLUListElement | null = null
  let unmount: (() => void) | null = null
  let props: SuggestionProps<TagCount, TagCount> | null = null
  let active = 0

  const draw = () => {
    if (!menu || !props) return
    const p = props
    const dom = p.editor.view.dom
    menu.replaceChildren(
      ...p.items.map((t, i) => {
        const li = document.createElement('li')
        li.id = `tag-option-${i}`
        li.role = 'option'
        li.dataset.tagOption = ''
        li.ariaSelected = String(i === active)
        li.textContent = `#${t.name}`
        // mousedown: keep the editor's focus and selection.
        li.addEventListener('mousedown', (e) => {
          e.preventDefault()
          p.command(t)
        })
        return li
      }),
    )
    const open = p.items.length > 0
    menu.hidden = !open
    if (open) {
      dom.setAttribute('aria-controls', menu.id)
      dom.setAttribute('aria-activedescendant', `tag-option-${active}`)
    } else {
      dom.removeAttribute('aria-activedescendant')
    }
  }

  const close = () => {
    props?.editor.view.dom.removeAttribute('aria-activedescendant')
    props?.editor.view.dom.removeAttribute('aria-controls')
    unmount?.()
    menu = null
    unmount = null
    props = null
  }

  return {
    onStart: (p: SuggestionProps<TagCount, TagCount>) => {
      props = p
      active = 0
      menu = document.createElement('ul')
      menu.id = 'tag-suggestions'
      menu.role = 'listbox'
      menu.ariaLabel = 'Tags'
      menu.dataset.tagSuggestions = ''
      unmount = p.mount(menu)
      draw()
    },
    onUpdate: (p: SuggestionProps<TagCount, TagCount>) => {
      props = p
      active = Math.min(active, Math.max(0, p.items.length - 1))
      draw()
    },
    onKeyDown: ({ event }: SuggestionKeyDownProps) => {
      const items = props?.items ?? []
      if (!props || items.length === 0) return false
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        active = (active + (event.key === 'ArrowDown' ? 1 : items.length - 1)) % items.length
        draw()
        return true
      }
      if (event.key === 'Enter' || event.key === 'Tab') {
        const t = items[active]
        if (t) props.command(t)
        return true
      }
      return false
    },
    onExit: close,
  }
}
