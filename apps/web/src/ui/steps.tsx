import styles from './steps.module.css'

/** Progress pills: the current step is wide, done steps are filled. */
export function Steps({ current, total }: { current: number; total: number }) {
  return (
    <div className={styles.steps} aria-hidden="true">
      {Array.from({ length: total }, (_, i) => (
        <span
          key={i}
          className={[styles.step, i === current && styles.current, i <= current && styles.done]
            .filter(Boolean)
            .join(' ')}
        />
      ))}
    </div>
  )
}
