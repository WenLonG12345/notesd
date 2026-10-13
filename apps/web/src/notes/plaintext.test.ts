import { describe, expect, it } from 'vitest'
import fixtures from '../../../../shared/markdown-fixtures/plaintext.json'
import { plainText, preview } from './plaintext'

describe('plain text (shared fixtures)', () => {
  for (const c of fixtures.cases) {
    it(c.name, () => {
      expect(plainText(c.markdown)).toBe(c.text)
    })
  }
})

describe('preview', () => {
  it('keeps one line per block', () => {
    expect(preview('# Trip\n\nLanding **Friday**.\n\n- [x] Book room\n- [ ] Dinner\n')).toBe(
      'Trip\nLanding Friday.\nBook room\nDinner',
    )
  })

  it('cuts at 160 code points', () => {
    const long = '🎉 '.repeat(100)
    const got = preview(long)
    expect(got.endsWith('…')).toBe(true)
    expect(Array.from(got)).toHaveLength(160)
  })
})
