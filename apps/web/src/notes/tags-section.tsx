import { useId, useMemo, useState, type CSSProperties, type KeyboardEvent } from 'react'
import type { TagCount } from '../api/types'
import { ChevronIcon, HashIcon } from '../ui/icons'
import type { ListFilter } from './store'
import { buildTree, type TagNode } from './tag-tree'
import styles from './tags-section.module.css'

type Props = {
  tags: TagCount[]
  filter: ListFilter
  onFilter: (filter: ListFilter) => void
}

const OPEN_KEY = 'notes.tags.open'
const EXPANDED_KEY = 'notes.tags.expanded'

// Per-browser conveniences: storage can be missing or throw (private windows).
function load<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key)
    return raw === null ? fallback : (JSON.parse(raw) as T)
  } catch {
    return fallback
  }
}

function save(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // Not remembered; nothing else depends on it.
  }
}

/** All notes, Untagged and the tag tree, at the top of the note list. */
export function TagsSection({ tags, filter, onFilter }: Props) {
  const id = useId()
  const tree = useMemo(() => buildTree(tags), [tags])
  const [open, setOpen] = useState(() => load(OPEN_KEY, true))
  const [expanded, setExpanded] = useState(() => new Set(load<string[]>(EXPANDED_KEY, [])))

  const toggleOpen = () => {
    setOpen(!open)
    save(OPEN_KEY, !open)
  }

  const setNodeOpen = (key: string, on: boolean) => {
    const next = new Set(expanded)
    if (on) next.add(key)
    else next.delete(key)
    setExpanded(next)
    save(EXPANDED_KEY, [...next])
  }

  const selected = !filter ? 'all' : 'tag' in filter ? `#${filter.tag}` : 'untagged'

  // Up and down move between rows; right and left open and close a parent.
  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const rows = [...e.currentTarget.querySelectorAll<HTMLButtonElement>('[data-row]')]
    const at = rows.indexOf(document.activeElement as HTMLButtonElement)
    if (at < 0) return
    const row = rows[at]
    const key = row?.dataset.key
    const step = e.key === 'ArrowDown' ? 1 : e.key === 'ArrowUp' ? -1 : 0
    if (step) {
      e.preventDefault()
      rows[Math.min(rows.length - 1, Math.max(0, at + step))]?.focus()
    } else if (key && row?.dataset.parent && (e.key === 'ArrowRight' || e.key === 'ArrowLeft')) {
      e.preventDefault()
      setNodeOpen(key, e.key === 'ArrowRight')
    }
  }

  const renderNode = (node: TagNode, depth: number) => {
    const isOpen = expanded.has(node.key)
    const parent = node.children.length > 0
    return (
      <li key={node.key}>
        <div className={styles.item} style={{ '--depth': depth } as CSSProperties}>
          {parent ? (
            <button
              type="button"
              className={styles.expander}
              aria-expanded={isOpen}
              aria-label={`${isOpen ? 'Collapse' : 'Expand'} ${node.name}`}
              tabIndex={-1}
              onClick={() => setNodeOpen(node.key, !isOpen)}
            >
              <ChevronIcon />
            </button>
          ) : (
            <span className={styles.expander} aria-hidden="true" />
          )}
          <button
            type="button"
            className={styles.row}
            data-row=""
            data-key={node.key}
            data-parent={parent ? '' : undefined}
            aria-current={selected === `#${node.key}` ? 'true' : undefined}
            aria-label={`#${node.name}, ${node.count} ${node.count === 1 ? 'note' : 'notes'}`}
            onClick={() => onFilter({ tag: node.key, name: node.name })}
          >
            <HashIcon />
            <span className={styles.label}>{node.label}</span>
            <span className={styles.count} aria-hidden="true">
              {node.count}
            </span>
          </button>
        </div>
        {parent && isOpen && <ul className={styles.list}>{node.children.map((c) => renderNode(c, depth + 1))}</ul>}
      </li>
    )
  }

  return (
    <div className={styles.section} onKeyDown={onKeyDown}>
      <button type="button" className={styles.header} aria-expanded={open} aria-controls={id} onClick={toggleOpen}>
        <span className={styles.headerChevron}>
          <ChevronIcon size={10} />
        </span>
        Tags
      </button>
      {open && (
        <div id={id}>
          <ul className={styles.list} aria-label="Show">
            <li>
              <div className={styles.item}>
                <span className={styles.expander} aria-hidden="true" />
                <button
                  type="button"
                  className={styles.row}
                  data-row=""
                  aria-current={selected === 'all' ? 'true' : undefined}
                  onClick={() => onFilter(null)}
                >
                  <span className={styles.label}>All notes</span>
                </button>
              </div>
            </li>
            <li>
              <div className={styles.item}>
                <span className={styles.expander} aria-hidden="true" />
                <button
                  type="button"
                  className={styles.row}
                  data-row=""
                  aria-current={selected === 'untagged' ? 'true' : undefined}
                  onClick={() => onFilter({ untagged: true })}
                >
                  <span className={styles.label}>Untagged</span>
                </button>
              </div>
            </li>
          </ul>
          {tree.length > 0 && (
            <ul className={styles.list} aria-label="Tags">
              {tree.map((n) => renderNode(n, 0))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
