import { describe, expect, it } from 'vitest'
import plaintext from '../../../../shared/markdown-fixtures/plaintext.json'
import roundtrip from '../../../../shared/markdown-fixtures/roundtrip.json'
import { parse, serialize } from './markdown'

describe('round trip of any Markdown (semantic)', () => {
  for (const c of plaintext.cases) {
    it(c.name, () => {
      const doc = parse(c.markdown)
      const again = parse(serialize(doc))
      expect(again.toJSON()).toEqual(doc.toJSON())
    })
  }
})

describe('round trip of canonical Markdown (byte for byte)', () => {
  for (const c of roundtrip.cases) {
    it(c.name, () => {
      expect(serialize(parse(c.markdown))).toBe(c.markdown)
    })
  }
})

describe('canonical form of Markdown written elsewhere', () => {
  const cases: [string, string, string][] = [
    ['unmatched backticks stay literal', 'a `` b', 'a \\`\\` b\n'],
    ['a fence can follow a paragraph line', 'Para\n```\ncode\n```', 'Para\n\n```\ncode\n```\n'],
    ['a rule can follow a paragraph line', 'word\n***', 'word\n\n***\n'],
    ['a fence can end a list item', '- item\n```\ncode\n```', '- item\n\n```\ncode\n```\n'],
  ]
  for (const [name, input, expected] of cases) {
    it(name, () => {
      const once = serialize(parse(input))
      expect(once).toBe(expected)
      expect(serialize(parse(once))).toBe(once)
    })
  }
})
