import styles from './backdrop.module.css'

/** A faint warm wash behind the window. Decorative. */
export function Backdrop() {
  return (
    <div className={styles.backdrop} aria-hidden="true">
      <div className={styles.wash} />
    </div>
  )
}
