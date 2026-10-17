import type { Absence, AbsenceKind } from '../api/types'
import { shortDate } from '../dates'

/** The register's tables, in this order, each with the name it is known by. */
export const KIND_TABLES: [AbsenceKind, string][] = [
  ['MC', 'Medical certificate'],
  ['OL', 'Overseas leave'],
  ['AL', 'Annual leave'],
  ['MA', 'Medical appointment'],
]

export interface KindGroup {
  kind: AbsenceKind
  /** Running or upcoming, soonest first. */
  current: Absence[]
  /** Over, most recent first. */
  past: Absence[]
}

/** Compare instants as text, to the minute (seconds are always zero). */
const minute = (instant: string) => instant.slice(0, 16)

export const isOver = (absence: Absence, now: string) => minute(absence.until) <= now
export const isRunning = (absence: Absence, now: string) =>
  minute(absence.covers) <= now && !isOver(absence, now)

/**
 * One group per kind, including empty ones. Past vs current uses the resolved
 * interval, not the booked dates.
 */
export function byKind(register: Absence[], now: string, trooper: string | null): KindGroup[] {
  const shown = trooper === null ? register : register.filter((a) => a.trooper === trooper)
  return KIND_TABLES.map(([kind]) => {
    const of = shown.filter((a) => a.kind === kind)
    return {
      kind,
      current: of.filter((a) => !isOver(a, now))
        .sort((a, b) => a.covers.localeCompare(b.covers) || a.id - b.id),
      past: of.filter((a) => isOver(a, now))
        .sort((a, b) => b.until.localeCompare(a.until) || b.id - a.id),
    }
  })
}

/** `09:30` or `09:30:00` → `0930`, the way times are written on the board. */
const hhmm = (time: string | null) => (time ?? '').slice(0, 5).replace(':', '')

/** The dates as booked. */
export function bookedAs(absence: Absence): string {
  const day = shortDate(absence.from)
  switch (absence.span) {
    case 'FULL':
      return absence.to === absence.from ? day : `${day} – ${shortDate(absence.to)}`
    case 'DAY_HALF':
      return `${day}, 0800–2000`
    case 'NIGHT_HALF':
      return `${day}, 2000–0800`
    case 'WINDOW':
      return `${day}, ${hhmm(absence.start)}–${hhmm(absence.end)}`
  }
}

/** Days booked; 0.5 for a half day, null for an appointment. */
export function daysOf(absence: Absence): number | null {
  if (absence.span === 'WINDOW') return null
  if (absence.span !== 'FULL') return 0.5
  // UTC so daylight saving can't skew the count.
  const utc = (date: string) => {
    const [y, m, d] = date.split('-').map(Number)
    return Date.UTC(y, m - 1, d)
  }
  return (utc(absence.to) - utc(absence.from)) / 86_400_000 + 1
}

export function daysLabel(days: number): string {
  return days === 0.5 ? '½' : Number.isInteger(days) ? String(days) : `${Math.floor(days)}½`
}
