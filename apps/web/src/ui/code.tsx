import { useEffect, useState, type ReactNode } from 'react'
import styles from './code.module.css'

/** Dark terminal-style block, e.g. sample server log output. */
export function Terminal({ children, label }: { children: ReactNode; label?: string }) {
  return (
    <pre className={styles.terminal} aria-label={label}>
      {children}
    </pre>
  )
}

/** Dim prefix inside a Terminal line. */
export function Dim({ children }: { children: ReactNode }) {
  return <span className={styles.dim}>{children}</span>
}

/** Highlighted value inside a Terminal line. */
export function Hi({ children }: { children: ReactNode }) {
  return <span className={styles.hi}>{children}</span>
}

/** A shell command to run on the server, with a copy button where the browser allows it. */
export function Command({ command, tone = 'light' }: { command: string; tone?: 'light' | 'dark' }) {
  const [copied, setCopied] = useState(false)
  // The Clipboard API only exists in secure contexts (https or localhost).
  const canCopy = typeof navigator !== 'undefined' && navigator.clipboard !== undefined

  useEffect(() => {
    if (!copied) return
    const t = setTimeout(() => setCopied(false), 1600)
    return () => clearTimeout(t)
  }, [copied])

  return (
    <div className={[styles.command, styles[tone]].join(' ')}>
      <code>
        {tone === 'dark' && <span className={styles.dim}>$ </span>}
        {command}
      </code>
      {canCopy && (
        <button
          type="button"
          className={styles.copy}
          onClick={() => {
            void navigator.clipboard.writeText(command).then(() => setCopied(true))
          }}
        >
          {copied ? 'Copied' : 'Copy'}
          <span className="visually-hidden"> command</span>
        </button>
      )}
    </div>
  )
}
