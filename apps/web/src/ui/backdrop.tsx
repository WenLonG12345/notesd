import styles from './backdrop.module.css'

/** The soft color fields the glass surfaces blur. Decorative. */
export function Backdrop() {
  return (
    <div className={styles.backdrop} aria-hidden="true">
      <div className={styles.b1} />
      <div className={styles.b2} />
      <div className={styles.b3} />
    </div>
  )
}
