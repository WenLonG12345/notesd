import { useLayoutEffect, useRef, type ReactNode } from 'react'
import styles from './sheet.module.css'

type Props = {
  /** Accessible name: the id of a heading, or a plain label. */
  labelledBy?: string
  label?: string
  onClose: () => void
  /** editor: one tall glass panel. stack: transparent column of cards (settings). */
  variant?: 'editor' | 'stack'
  children: ReactNode
}

/**
 * Full-screen modal layer over the blurred app. Built on <dialog> so focus is
 * trapped and restored; Escape and a click on the backdrop close it.
 */
export function Sheet({ labelledBy, label, onClose, variant = 'editor', children }: Props) {
  const ref = useRef<HTMLDialogElement>(null)

  // Layout effect: open before children's effects run, or showModal()'s
  // initial focus would override focus they set (e.g. a new note's title).
  useLayoutEffect(() => {
    const dialog = ref.current
    dialog?.showModal()
    return () => dialog?.close()
  }, [])

  return (
    <dialog
      ref={ref}
      className={[styles.sheet, styles[variant]].join(' ')}
      aria-labelledby={labelledBy}
      aria-label={label}
      onCancel={(e) => {
        e.preventDefault()
        onClose()
      }}
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
    >
      <div className={styles.panel}>{children}</div>
    </dialog>
  )
}
