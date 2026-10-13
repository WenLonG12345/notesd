// #tags (architecture §7.5), ported from the server (internal/search/tags.go)
// and pinned by shared/markdown-fixtures/tags.json. Keep the two in step.
import { OBJECT, eachLine, inline } from './plaintext'

export interface Tag {
  /** Identifies the tag: name lowercased. */
  key: string
  name: string
}

/** A note's tags, first spelling of each key, in order of appearance. */
export function tags(title: string, markdown: string): Tag[] {
  const out: Tag[] = []
  const seen = new Set<string>()
  const scan = (line: string) => {
    for (const { name } of scanTags(line)) {
      const key = name.toLowerCase()
      if (!seen.has(key)) {
        seen.add(key)
        out.push({ key, name })
      }
    }
  }
  scan(title)
  eachLine(markdown, (text, code) => {
    if (!code) scan(inline(text, true))
  })
  return out
}

/** The key for a tag name given with or without its # marks, or ''. */
export function tagKey(name: string): string {
  return normalize(name.replace(/^#/, '').replace(/#$/, '')).toLowerCase()
}

export interface TagMatch {
  name: string
  /** UTF-16 offsets in the scanned text, covering the # marks. */
  from: number
  to: number
}

/**
 * The tags in one line of visible text, where OBJECT stands for code, links
 * and anything else tags can't be part of.
 */
export function scanTags(line: string): TagMatch[] {
  const r = Array.from(line)
  const at = (k: number) => r.slice(0, k).join('').length

  const out: TagMatch[] = []
  for (let i = 0; i < r.length; i++) {
    if (r[i] !== '#' || (i > 0 && !opensTag(r[i - 1]))) continue
    const next = r[i + 1]
    if (next === undefined || isSpace(next) || next === '#' || next === OBJECT) continue
    const close = closingHash(r, i)
    if (close > 0) {
      const name = normalize(r.slice(i + 1, close).join(''))
      if (name) out.push({ name, from: at(i), to: at(close + 1) })
      i = close
      continue
    }
    let end = i + 1
    while (end < r.length && inWord(r[end])) end++
    const name = normalize(r.slice(i + 1, end).join(''))
    if (name && !numeric(name)) out.push({ name, from: at(i), to: at(end) })
    i = end - 1
  }
  return out
}

/** The # closing a multi-word tag opened at r[open]: the next # on the line, if it follows a non-space and ends a word. */
function closingHash(r: string[], open: number): number {
  for (let j = open + 2; j < r.length; j++) {
    if (r[j] === OBJECT) return 0
    if (r[j] === '#') {
      if (isSpace(r[j - 1]) || inWord(r[j + 1])) return 0
      return j
    }
  }
  return 0
}

function opensTag(c: string | undefined): boolean {
  return isSpace(c) || c === '(' || c === '[' || c === '{'
}

/** Anything but spaces, OBJECT and punctuation, except - _ and the / between nested tags. */
function inWord(c: string | undefined): boolean {
  if (c === undefined) return false
  if (c === '-' || c === '_' || c === '/') return true
  if (c === OBJECT || isSpace(c)) return false
  if (c.charCodeAt(0) < 0x80) return /^[A-Za-z0-9]$/.test(c)
  return !/^\p{P}$/u.test(c)
}

function isSpace(c: string | undefined): boolean {
  return c !== undefined &&  /^\s$/u.test(c)
}

/** Trims each /-separated level, collapses inner spaces, drops empty levels. */
function normalize(s: string): string {
  return s
    .split('/')
    .map((level) => level.split(/\s+/u).filter(Boolean).join(' '))
    .filter(Boolean)
    .join('/')
}

/** Names like 1 or 2024/10 are tags only when closed with #, so "#1 priority" stays text. */
function numeric(name: string): boolean {
  return /^[0-9\-_/]*$/.test(name)
}
