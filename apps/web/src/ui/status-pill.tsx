import styles from './status-pill.module.css'

export type SaveState = 'saved' | 'unsaved' | 'saving' | 'failed' | 'conflict'

const label: Record<SaveState, string> = {
  saved: 'Saved',
  unsaved: 'Unsaved',
  saving: 'Saving',
  failed: 'Couldn’t save',
  conflict: 'Conflict',
}

const title: Record<SaveState, string> = {
  saved: 'All changes saved to the server',
  unsaved: 'Changes waiting to be saved',
  saving: 'Saving to the server…',
  failed: 'The server couldn’t be reached',
  conflict: 'Edited on another device',
}

/**
 * The save indicator. While not [shown] it's only for screen readers: saving is
 * meant to go unnoticed. [detail] replaces the tooltip when the state alone misleads.
 */
export function StatusPill({ state, shown, detail }: { state: SaveState; shown: boolean; detail?: string }) {
  return (
    <div
      className={shown ? [styles.pill, styles[state]].join(' ') : 'visually-hidden'}
      title={detail ?? title[state]}
      role="status"
      data-shown={shown}
    >
      <span className={styles.dot} aria-hidden="true" />
      <span className={styles.label}>{label[state]}</span>
    </div>
  )
}
