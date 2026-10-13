import { useLayoutEffect, useRef, type ReactNode } from 'react'
import styles from './dialog.module.css'

type Props = {
  labelledBy: string
  /** Omit to make the dialog impossible to dismiss with Escape. */
  onClose?: () => void
  children: ReactNode
}

/** Modal built on <dialog>: the browser traps focus and restores it on close. */
export function Dialog({ labelledBy, onClose, children }: Props) {
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
      className={styles.dialog}
      aria-labelledby={labelledBy}
      onCancel={(e) => {
        e.preventDefault()
        onClose?.()
      }}
    >
      {children}
    </dialog>
  )
}
