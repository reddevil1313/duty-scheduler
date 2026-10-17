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
  /** Not over yet, running or to come: soonest first, so the next to start leads. */
  current: Absence[]
  /** Over: the most recent first, since the latest is the one anyone asks about. */
  past: Absence[]
}

/**
 * Instants are compared as text to the minute. The server writes them
 * `YYYY-MM-DDTHH:MM[:SS]`, and seconds are always zero, so the first sixteen
 * characters order exactly as the times do.
 */
const minute = (instant: string) => instant.slice(0, 16)

export const isOver = (absence: Absence, now: string) => minute(absence.until) <= now
export const isRunning = (absence: Absence, now: string) =>
  minute(absence.covers) <= now && !isOver(absence, now)

/**
 * The register split into one group per kind, every kind present even when
 * empty so each table keeps its place on the page. Over or not is judged on
 * the resolved interval the server sent, not on the dates as booked: an MC
 * "until the 12th" still holds the 11th's sheet until 2000 on the 12th.
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

/**
 * The leave as it was booked, in the words it was booked in: the dates
 * someone wrote down, not the interval the 2000 rule resolves them to. That
 * interval is shown alongside, and the sheets it reaches in their own column.
 */
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

/**
 * Days booked: calendar days for full days, a half for either half day.
 * Null for an appointment, which is a matter of hours.
 */
export function daysOf(absence: Absence): number | null {
  if (absence.span === 'WINDOW') return null
  if (absence.span !== 'FULL') return 0.5
  // Through UTC on purpose: both ends are dates with no time, so the count
  // cannot be bent by a daylight-saving change between them.
  const utc = (date: string) => {
    const [y, m, d] = date.split('-').map(Number)
    return Date.UTC(y, m - 1, d)
  }
  return (utc(absence.to) - utc(absence.from)) / 86_400_000 + 1
}

export function daysLabel(days: number): string {
  return days === 0.5 ? '½' : Number.isInteger(days) ? String(days) : `${Math.floor(days)}½`
}
