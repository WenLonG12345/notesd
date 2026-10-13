import styles from './password-meter.module.css'

const words = ['', 'Weak', 'Fair', 'Good', 'Strong']

/** Rough 0–4 strength score; guidance only, the server just enforces length. */
export function passwordScore(pw: string): number {
  if (!pw) return 0
  const checks = [
    pw.length >= 8,
    pw.length >= 12,
    /[A-Z]/.test(pw) && /[a-z]/.test(pw),
    /[\d\W]/.test(pw),
  ]
  return checks.filter(Boolean).length
}

export function PasswordMeter({ password }: { password: string }) {
  const score = passwordScore(password)
  const tone = score < 2 ? styles.weak : score < 3 ? styles.fair : styles.strong
  return (
    <div
      className={styles.track}
      role="meter"
      aria-label="Password strength"
      aria-valuemin={0}
      aria-valuemax={4}
      aria-valuenow={score}
      aria-valuetext={words[score] || 'Empty'}
    >
      <div className={[styles.bar, tone].join(' ')} style={{ width: `${score * 25}%` }} />
    </div>
  )
}
