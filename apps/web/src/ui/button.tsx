import type { ButtonHTMLAttributes } from 'react'
import styles from './ui.module.css'

type Props = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary' | 'quiet'
  busy?: boolean
}

export function Button({ variant = 'secondary', busy = false, className, disabled, type = 'button', ...rest }: Props) {
  return (
    <button
      type={type}
      className={[styles.button, styles[variant], className].filter(Boolean).join(' ')}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      {...rest}
    />
  )
}
