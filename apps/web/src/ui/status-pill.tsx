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

/** The live save indicator: the dot breathes while saving and settles when saved. */
export function StatusPill({ state }: { state: SaveState }) {
  return (
    <div className={[styles.pill, styles[state]].join(' ')} title={title[state]} role="status">
      {/* Keyed so the settle animation replays on every transition into "saved". */}
      <span key={state} className={styles.dot} aria-hidden="true" />
      <span className={styles.label}>{label[state]}</span>
    </div>
  )
}
