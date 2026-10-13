import { useLayoutEffect, useRef, type ReactNode } from 'react'
import styles from './sheet.module.css'

type Props = {
  /** Accessible name: the id of a heading, or a plain label. */
  labelledBy?: string
  label?: string
  onClose: () => void
  children: ReactNode
}

/**
 * Full-screen modal layer over the blurred app: a column of glass cards. Built on <dialog> so focus is
 * trapped and restored; Escape and a click on the backdrop close it.
 */
export function Sheet({ labelledBy, label, onClose, children }: Props) {
  const ref = useRef<HTMLDialogElement>(null)

  // Layout effect: open before children's effects run, or showModal()'s
  // initial focus would override focus they set.
  useLayoutEffect(() => {
    const dialog = ref.current
    dialog?.showModal()
    return () => dialog?.close()
  }, [])

  return (
    <dialog
      ref={ref}
      className={styles.sheet}
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
