import type { TagCount } from '../api/types'

export type TagNode = {
  key: string
  /** The full name, e.g. Home/Repairs. */
  name: string
  /** This level only, e.g. Repairs. */
  label: string
  count: number
  children: TagNode[]
}

/** Nests the server's flat list (parents included) by key. */
export function buildTree(tags: TagCount[]): TagNode[] {
  const roots: TagNode[] = []
  const byKey = new Map<string, TagNode>()
  for (const t of tags.toSorted((a, b) => (a.key < b.key ? -1 : a.key > b.key ? 1 : 0))) {
    const slash = t.key.lastIndexOf('/')
    const parent = slash < 0 ? undefined : byKey.get(t.key.slice(0, slash))
    const label = parent ? t.name.slice(t.name.lastIndexOf('/') + 1) : t.name
    const node: TagNode = { key: t.key, name: t.name, label, count: t.count, children: [] }
    byKey.set(t.key, node)
    if (parent) parent.children.push(node)
    else roots.push(node)
  }
  return roots
}
