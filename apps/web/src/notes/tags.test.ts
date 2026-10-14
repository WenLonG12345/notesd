import { describe, expect, it } from 'vitest'
import fixtures from '../../../../shared/markdown-fixtures/tags.json'
import { hasTag, scanTags, tagKey, tagText, tags } from './tags'

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

describe('tagText', () => {
  it('closes tags that need it', () => {
    expect(tagText('home/repairs')).toBe('#home/repairs')
    expect(tagText('summer trip')).toBe('#summer trip#')
    expect(tagText('2024')).toBe('#2024#')
    expect(tagText('v1.2')).toBe('#v1.2#')
    for (const name of ['home/repairs', 'summer trip', '2024', 'v1.2']) {
      expect(tags('', `${tagText(name)} after`).map((t) => t.name)).toEqual([name])
    }
  })
})

describe('hasTag', () => {
  it('matches the tag and nested tags only', () => {
    const t = tags('', '#home/repairs #homework')
    expect(hasTag(t, 'home')).toBe(true)
    expect(hasTag(t, 'home/repairs')).toBe(true)
    expect(hasTag(t, 'hom')).toBe(false)
    expect(hasTag(t, 'work')).toBe(false)
  })
})
