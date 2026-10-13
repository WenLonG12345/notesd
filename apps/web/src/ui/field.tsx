import { useId, type InputHTMLAttributes, type ReactNode } from 'react'
import styles from './ui.module.css'

type Props = InputHTMLAttributes<HTMLInputElement> & {
  label: string
  error?: string | undefined
  hint?: ReactNode
}

export function Field({ label, error, hint, ...input }: Props) {
  const id = useId()
  const describedBy = [error && `${id}-error`, hint && `${id}-hint`].filter(Boolean).join(' ')
  return (
    <div className={styles.field}>
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy || undefined}
        {...input}
      />
      {error && (
        <p id={`${id}-error`} className={styles.fieldError}>
          {error}
        </p>
      )}
      {hint && (
        <p id={`${id}-hint`} className={styles.fieldHint}>
          {hint}
        </p>
      )}
    </div>
  )
}
