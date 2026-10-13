import styles from './toast.module.css'

/** A short-lived message at the bottom of the screen, optionally with Undo. */
export function Toast({ text, onUndo }: { text: string; onUndo?: (() => void) | undefined }) {
  return (
    <div className={styles.toast} role="status">
      <span>{text}</span>
      {onUndo && (
        <button type="button" className={styles.undo} onClick={onUndo}>
          Undo
        </button>
      )}
    </div>
  )
}
