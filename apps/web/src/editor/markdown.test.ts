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
