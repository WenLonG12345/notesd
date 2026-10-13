import { describe, expect, it } from 'vitest'
import fixtures from '../../../../shared/markdown-fixtures/tags.json'
import { scanTags, tagKey, tags } from './tags'

describe('tags (shared fixtures)', () => {
  for (const c of fixtures.cases) {
    it(c.name, () => {
      const got = tags((c as { title?: string }).title ?? '', c.markdown)
      expect(got.map((t) => t.name)).toEqual(c.tags)
      for (const t of got) expect(t.key).toBe(t.name.toLowerCase())
    })
  }
})

describe('tagKey', () => {
  it('normalizes names as typed', () => {
    expect(tagKey('#Home/Repairs')).toBe('home/repairs')
    expect(tagKey('#school / biology#')).toBe('school/biology')
    expect(tagKey('#')).toBe('')
  })
})

describe('scanTags', () => {
  it('reports UTF-16 ranges including the # marks', () => {
    const line = '🎉 #a and #b c# end'
    expect(scanTags(line).map((m) => line.slice(m.from, m.to))).toEqual(['#a', '#b c#'])
  })
})
