import styles from './brand.module.css'

export function Brand({ size = 'md' }: { size?: 'md' | 'lg' }) {
  return (
    <span className={[styles.brand, styles[size]].join(' ')}>
      <span className={styles.mark} aria-hidden="true" />
      <span className={styles.word}>notesd</span>
    </span>
  )
}
