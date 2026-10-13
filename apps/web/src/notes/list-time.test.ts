import { describe, expect, it } from 'vitest'
import { groupByDate, listTime } from './list-time'

const now = new Date(2026, 9, 13, 15, 0).getTime()
const at = (daysAgo: number, hour = 9) => new Date(2026, 9, 13 - daysAgo, hour, 30).getTime()

describe('groupByDate', () => {
  it('sections notes by age, newest first, without empty sections', () => {
    const items = [0, 1, 6, 8, 29, 31, 400].map((d) => ({ updatedAt: at(d) }))
    expect(groupByDate(items, now).map((g) => [g.label, g.items.length])).toEqual([
      ['Today', 1],
      ['Previous 7 days', 2],
      ['Previous 30 days', 2],
      ['Earlier', 2],
    ])
  })

  it('counts calendar days for Today, not 24 hours', () => {
    const lateYesterday = new Date(2026, 9, 12, 23, 50).getTime()
    expect(groupByDate([{ updatedAt: lateYesterday }], now)[0]?.label).toBe('Previous 7 days')
  })
})

describe('listTime', () => {
  it('shows Yesterday, then weekdays, then dates', () => {
    expect(listTime(at(1), now)).toBe('Yesterday')
    expect(listTime(at(3), now)).toBe(new Date(at(3)).toLocaleDateString(undefined, { weekday: 'long' }))
    expect(listTime(at(10), now)).toBe(
      new Date(at(10)).toLocaleDateString(undefined, { day: 'numeric', month: 'numeric', year: '2-digit' }),
    )
  })
})
