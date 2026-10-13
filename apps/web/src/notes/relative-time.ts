const MIN = 60_000
const HOUR = 60 * MIN
const DAY = 24 * HOUR

export function relativeTime(ts: number, now = Date.now()): string {
  const d = now - ts
  if (d < 45_000) return 'Just now'
  if (d < HOUR) return `${Math.round(d / MIN)} min ago`
  if (d < DAY) return `${Math.round(d / HOUR)} h ago`
  if (d < 2 * DAY) return 'Yesterday'
  if (d < 7 * DAY) return new Date(ts).toLocaleDateString(undefined, { weekday: 'long' })
  return new Date(ts).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}
