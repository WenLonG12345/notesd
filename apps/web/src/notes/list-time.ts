const DAY = 86_400_000

const sameDay = (a: number, b: number) => new Date(a).toDateString() === new Date(b).toDateString()

/** Apple Notes-style row time: a clock time today, then Yesterday, a weekday, a short date. */
export function listTime(ts: number, now = Date.now()): string {
  if (sameDay(ts, now)) return new Date(ts).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
  if (sameDay(ts, now - DAY)) return 'Yesterday'
  if (now - ts < 7 * DAY) return new Date(ts).toLocaleDateString(undefined, { weekday: 'long' })
  return new Date(ts).toLocaleDateString(undefined, { day: 'numeric', month: 'numeric', year: '2-digit' })
}

export type Group<T> = { label: string; items: T[] }

const buckets: [string, (ts: number, now: number) => boolean][] = [
  ['Today', (ts, now) => sameDay(ts, now)],
  ['Previous 7 days', (ts, now) => now - ts < 7 * DAY],
  ['Previous 30 days', (ts, now) => now - ts < 30 * DAY],
  ['Earlier', () => true],
]

/** Splits notes (newest first) into the list's date sections, skipping empty ones. */
export function groupByDate<T extends { updatedAt: number }>(items: T[], now = Date.now()): Group<T>[] {
  const groups: Group<T>[] = []
  for (const item of items) {
    const label = buckets.find(([, test]) => test(item.updatedAt, now))?.[0] ?? 'Earlier'
    const last = groups.at(-1)
    if (last?.label === label) last.items.push(item)
    else groups.push({ label, items: [item] })
  }
  return groups
}
