// Markdown → tokens for Markdown.swift. This is the tokenizer half of
// apps/web/src/editor/markdown.ts (createTokenizer and taskLists), run on the
// same markdown-it build, so iOS reads a note exactly as the web does. Keep
// the two in step. Markdown.swift does what prosemirror-markdown does on the
// web: it turns these tokens into the document tree.
'use strict'

// markdown-it doesn't export its built-in rules; the ruler's list is the only handle.
function builtin(ruler, name) {
  const rule = (ruler.__rules__ || []).find((r) => r.name === name)
  return rule && rule.fn
}

// The rules a built-in rule may interrupt (a fence ends a paragraph, a list,
// a quote). ruler.at() drops them unless they're passed back in.
function alt(ruler, name) {
  const rule = (ruler.__rules__ || []).find((r) => r.name === name)
  return (rule && rule.alt) || []
}

function createTokenizer() {
  // 'default' preset: CommonMark + GFM tables and strikethrough. No raw HTML,
  // no linkify (plain URLs stay text unless written as links), no typography.
  const md = markdownit('default', { html: false, linkify: false, breaks: false, typographer: false })

  // Blocks outside the dialect become raw_block tokens with their exact source.
  // getLines() strips container prefixes ("> ", list indent), so it re-nests.
  for (const name of ['fence', 'code', 'hr', 'table']) {
    const original = builtin(md.block.ruler, name)
    if (!original) continue
    md.block.ruler.at(
      name,
      (state, startLine, endLine, silent) => {
        if (silent) return original(state, startLine, endLine, true)
        const before = state.tokens.length
        if (!original(state, startLine, endLine, false)) return false
        state.tokens.length = before
        const token = state.push('raw_block', '', 0)
        token.content = state.getLines(startLine, state.line, state.blkIndent, false).replace(/\n$/, '')
        token.map = [startLine, state.line]
        token.block = true
        return true
      },
      { alt: alt(md.block.ruler, name) },
    )
  }

  // Same for inline constructs: code spans and images, kept as their exact
  // source. (The web makes an app image its own node; here both are raw runs,
  // and the editor recognises an image by its source.)
  for (const name of ['backticks', 'image']) {
    const original = builtin(md.inline.ruler, name)
    if (!original) continue
    md.inline.ruler.at(name, (state, silent) => {
      if (silent) return original(state, true)
      const start = state.pos
      // Flush preceding text first, or truncating below would drop it.
      if (state.pending) state.pushPending()
      const before = state.tokens.length
      if (!original(state, false)) return false
      // No closing run: markdown-it kept the backticks as pending text, not a code span.
      if (state.tokens.length === before && state.pending) return true
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
function taskLists(state) {
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

function matchingClose(tokens, openAt) {
  let depth = 0
  for (let i = openAt; i < tokens.length; i++) {
    depth += tokens[i]?.nesting ?? 0
    if (depth === 0) return i
  }
  return tokens.length - 1
}

function directChildren(tokens, openAt, closeAt, type) {
  const out = []
  let depth = 0
  for (let i = openAt + 1; i < closeAt; i++) {
    const t = tokens[i]
    if (!t) continue
    if (depth === 0 && t.type === type) out.push(i)
    depth += t.nesting
  }
  return out
}

// Only what Markdown.swift reads: y type, l heading level, s list start,
// k checked, h/i link href and title, c text or source, ch inline children.
function simplify(tokens) {
  return tokens.map((t) => {
    const o = { y: t.type }
    switch (t.type) {
      case 'heading_open':
        o.l = Number(t.tag.slice(1))
        break
      case 'ordered_list_open':
        o.s = Number(t.attrGet('start') ?? 1)
        break
      case 'task_item_open':
        o.k = t.attrGet('checked') === 'true'
        break
      case 'link_open':
        o.h = t.attrGet('href') ?? ''
        o.i = t.attrGet('title') || null
        break
      case 'inline':
        o.ch = simplify(t.children ?? [])
        break
      default:
        if (t.content) o.c = t.content
    }
    return o
  })
}

const md = createTokenizer()

globalThis.notesdTokens = (src) => JSON.stringify(simplify(md.parse(src, {})))
