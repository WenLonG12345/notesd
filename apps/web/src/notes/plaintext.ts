// Markdown → plain text, ported from the server (internal/search/extract.go)
// and pinned by shared/markdown-fixtures/plaintext.json. Keep the two in step.

export function plainText(markdown: string): string {
  const md = markdown.replace(/\r\n?/g, '\n')
  const out: string[] = []
  let fence = '' // the opening fence while inside a fenced code block
  for (const line of md.split('\n')) {
    const trimmed = line.replace(/^[ \t]+/, '')
    if (fence) {
      if (trimmed.startsWith(fence) && trimAll(trimmed, fence.charAt(0)) === '') {
        fence = ''
        continue
      }
      out.push(line.replace(/[ \t]+$/, ''))
      continue
    }
    const opener = fenceOpener(trimmed)
    if (opener) {
      fence = opener
      continue
    }
    if (isRule(trimmed)) {
      out.push('')
      continue
    }
    out.push(inline(stripBlockMarkers(trimmed)).trim())
  }
  return collapseBlankLines(out)
}

const PREVIEW_CHARS = 160

/** The list preview the server sends (notes.preview): one line per block, ≤160 code points. */
export function preview(markdown: string): string {
  return previewOf(plainText(markdown))
}

/** preview() for text already extracted: one block per line. */
export function previewOf(text: string): string {
  const lines = text
    .split('\n')
    .map((l) => l.split(/\s+/u).filter(Boolean).join(' '))
    .filter(Boolean)
  const chars = Array.from(lines.join('\n'))
  if (chars.length <= PREVIEW_CHARS) return chars.join('')
  return `${chars.slice(0, PREVIEW_CHARS).join('').trim()}…`
}

function trimAll(s: string, c: string): string {
  let start = 0
  let end = s.length
  while (start < end && s[start] === c) start++
  while (end > start && s[end - 1] === c) end--
  return s.slice(start, end)
}

function fenceOpener(s: string): string {
  for (const c of ['```', '~~~']) {
    if (s.startsWith(c)) return c.charAt(0).repeat(runLen(s, 0, c.charAt(0)))
  }
  return ''
}

/** Thematic breaks and setext underlines: only -, *, _ or = (≥3, or ≥2 for =), ignoring spaces. */
function isRule(s: string): boolean {
  const compact = s.replaceAll(' ', '')
  if (compact.length < 2) return false
  const c = compact.charAt(0)
  if (c !== '-' && c !== '*' && c !== '_' && c !== '=') return false
  if (trimAll(compact, c) !== '') return false
  return c === '=' || compact.length >= 3
}

function stripBlockMarkers(input: string): string {
  let s = input
  for (;;) {
    const before = s
    if (s.startsWith('>')) {
      s = s.slice(1).replace(/^[ \t]+/, '')
    } else if (s.length >= 2 && '-*+'.includes(s.charAt(0)) && (s[1] === ' ' || s[1] === '\t')) {
      s = s.slice(2).replace(/^[ \t]+/, '')
    } else if (s === '-' || s === '*' || s === '+') {
      s = ''
    } else {
      let n = orderedMarker(s)
      if (n === 0) n = taskBox(s)
      if (n > 0) {
        s = s.slice(n).replace(/^[ \t]+/, '')
      } else if ((n = headingMarker(s)) > 0) {
        s = trimClosingHashes(s.slice(n).replace(/^[ \t]+/, ''))
      }
    }
    if (s === before) return s
  }
}

function orderedMarker(s: string): number {
  let i = 0
  while (i < s.length && i < 9 && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++
  if (i === 0 || i >= s.length || (s[i] !== '.' && s[i] !== ')')) return 0
  if (i + 1 === s.length) return i + 1
  if (s[i + 1] === ' ' || s[i + 1] === '\t') return i + 2
  return 0
}

function taskBox(s: string): number {
  if (s.length >= 3 && s[0] === '[' && s[2] === ']' && ' xX'.includes(s.charAt(1))) {
    if (s.length === 3 || s[3] === ' ' || s[3] === '\t') return 3
  }
  return 0
}

function headingMarker(s: string): number {
  let n = 0
  while (n < s.length && s[n] === '#') n++
  if (n === 0 || n > 6) return 0
  if (n === s.length || s[n] === ' ' || s[n] === '\t') return n
  return 0
}

function trimClosingHashes(s: string): string {
  const t = s.replace(/[ \t]+$/, '')
  const u = t.replace(/#+$/, '')
  if (u === t) return s
  if (u === '' || u.endsWith(' ') || u.endsWith('\t')) return u.replace(/[ \t]+$/, '')
  return s
}

function inline(s: string): string {
  let out = ''
  let i = 0
  while (i < s.length) {
    const c = s.charAt(i)
    if (c === '\\' && i + 1 < s.length && isAsciiPunct(s.charAt(i + 1))) {
      out += s.charAt(i + 1)
      i += 2
    } else if (c === '`') {
      const n = runLen(s, i, '`')
      const ticks = s.slice(i, i + n)
      const end = s.indexOf(ticks, i + n)
      if (end >= 0) {
        out += s.slice(i + n, end).trim()
        i = end + n
      } else {
        out += ticks
        i += n
      }
    } else if (c === '!' && s[i + 1] === '[') {
      const l = link(s.slice(i + 1))
      if (l) {
        out += inline(l.text)
        i += 1 + l.n
      } else {
        out += c
        i++
      }
    } else if (c === '[') {
      const l = link(s.slice(i))
      if (l) {
        out += inline(l.text)
        i += l.n
      } else {
        out += c
        i++
      }
    } else if (c === '<') {
      const end = s.indexOf('>', i)
      if (end - i > 1 && isAutolink(s.slice(i + 1, end))) {
        out += s.slice(i + 1, end)
        i = end + 1
      } else {
        out += c
        i++
      }
    } else if (c === '*' || c === '_' || c === '~') {
      const n = runLen(s, i, c)
      if ((c === '~' && n < 2) || !isDelimiter(s, i, n, c)) out += s.slice(i, i + n)
      i += n
    } else {
      out += c
      i++
    }
  }
  return out
}

/** "[text](destination)" at the start of s: the text and how many chars it spans. */
function link(s: string): { text: string; n: number } | null {
  let depth = 0
  for (let i = 0; i < s.length; i++) {
    const c = s[i]
    if (c === '\\') {
      i++
    } else if (c === '[') {
      depth++
    } else if (c === ']') {
      depth--
      if (depth !== 0) continue
      if (s[i + 1] !== '(') return null
      let pd = 0
      for (let j = i + 1; j < s.length; j++) {
        const d = s[j]
        if (d === '\\') j++
        else if (d === '(') pd++
        else if (d === ')' && --pd === 0) return { text: s.slice(1, i), n: j + 1 }
      }
      return null
    }
  }
  return null
}

function isAutolink(s: string): boolean {
  if (/[ \t<]/.test(s)) return false
  const colon = s.indexOf(':')
  if (colon >= 2) return /^[A-Za-z0-9+.-]+$/.test(s.slice(0, colon))
  const at = s.indexOf('@')
  return at > 0 && s.slice(at).includes('.')
}

/** Emphasis run or literal character, approximating CommonMark flanking (snake_case is literal). */
function isDelimiter(s: string, i: number, n: number, c: string): boolean {
  const before = i === 0 ? ' ' : lastCodePoint(s.slice(0, i))
  const after = i + n >= s.length ? ' ' : String.fromCodePoint(s.codePointAt(i + n) ?? 32)
  if (/\s/u.test(before) && /\s/u.test(after)) return false
  if (c === '_' && isAlnum(before) && isAlnum(after)) return false
  return true
}

function lastCodePoint(s: string): string {
  return Array.from(s).at(-1) ?? ' '
}

function isAlnum(ch: string): boolean {
  return /^[\p{L}\p{Nd}]$/u.test(ch)
}

function runLen(s: string, i: number, c: string): number {
  let n = 0
  while (s[i + n] === c) n++
  return n
}

function isAsciiPunct(c: string): boolean {
  return '!"#$%&\'()*+,-./:;<=>?@[\\]^_`{|}~'.includes(c)
}

function collapseBlankLines(lines: string[]): string {
  let out = ''
  let blank = true // suppress leading blank lines
  for (const l of lines) {
    if (l === '') {
      blank = true
      continue
    }
    if (out) out += blank ? '\n\n' : '\n'
    out += l
    blank = false
  }
  return out
}
