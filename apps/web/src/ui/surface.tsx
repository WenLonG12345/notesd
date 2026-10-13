import type { HTMLAttributes } from 'react'
import styles from './surface.module.css'

type Props = HTMLAttributes<HTMLElement> & {
  /** sheet: a raised panel (auth card, editor). tile: a card in a grid. */
  variant?: 'sheet' | 'tile'
  as?: 'div' | 'section' | 'article'
  /** Animate in on mount. */
  enter?: boolean
}

/** Frosted-glass panel over the backdrop blobs. */
export function Surface({ variant = 'tile', as: Tag = 'div', enter = false, className, ...rest }: Props) {
  return (
    <Tag
      className={[styles.surface, styles[variant], enter && styles.enter, className].filter(Boolean).join(' ')}
      {...rest}
    />
  )
}
