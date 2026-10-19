import { useEffect, useState } from 'react'
import styles from './code.module.css'

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
