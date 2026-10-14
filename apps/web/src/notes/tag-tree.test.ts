import { describe, expect, it } from 'vitest'
import { buildTree } from './tag-tree'

describe('buildTree', () => {
  it('nests by key and labels each level', () => {
    const tree = buildTree([
      { key: 'work', name: 'Work', count: 1 },
      { key: 'home/repairs', name: 'Home/Repairs', count: 1 },
      { key: 'home', name: 'Home', count: 2 },
      { key: 'home/repairs/roof', name: 'Home/Repairs/Roof', count: 1 },
    ])
    expect(tree.map((n) => n.label)).toEqual(['Home', 'Work'])
    const repairs = tree[0]?.children[0]
    expect(repairs?.label).toBe('Repairs')
    expect(repairs?.children.map((n) => n.name)).toEqual(['Home/Repairs/Roof'])
  })

  it('keeps a tag whose parent is missing at the top, by its full name', () => {
    expect(buildTree([{ key: 'a/b', name: 'a/b', count: 1 }]).map((n) => n.label)).toEqual(['a/b'])
  })
})
