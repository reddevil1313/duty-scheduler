import type { Post, Schedule, ScheduleRow, SheetCheck } from '../api/types'
import { SLOT_COUNT } from './slots'

/**
 * Hand edits to a sheet. Each returns a new sheet and leaves the old one
 * alone, which is what makes "undo my edits" a matter of keeping the original.
 *
 * <p>Hours are recounted here because the count is trivial; span is not,
 * because it is rest-aware, and comes back from the server's check instead.
 */

function withHours(row: ScheduleRow): ScheduleRow {
  return { ...row, hours: row.posts.filter((p) => p !== null).length }
}

function totalled(sheet: Schedule, rows: ScheduleRow[]): Schedule {
  return { ...sheet, rows, totalHours: rows.reduce((sum, r) => sum + r.hours, 0) }
}

/**
 * Put a Trooper on a post for one hour, or take them off it. A Trooper with no
 * row yet — someone on the roster but not on a logged sheet — gets one.
 */
export function setCell(sheet: Schedule, trooper: string, stayOut: boolean, slot: number, post: Post | null): Schedule {
  const exists = sheet.rows.some((r) => r.trooper === trooper)
  const rows = exists
    ? sheet.rows
    : [...sheet.rows, { trooper, stayOut, posts: Array(SLOT_COUNT).fill(null), hours: 0, span: 0 }]
  return totalled(sheet, rows.map((r) =>
    r.trooper === trooper
      ? withHours({ ...r, posts: r.posts.map((p, i) => (i === slot ? post : p)) })
      : r))
}

/** Trade two Troopers' whole days. */
export function swapRows(sheet: Schedule, a: string, b: string): Schedule {
  const postsOf = new Map(sheet.rows.map((r) => [r.trooper, r.posts]))
  const pa = postsOf.get(a)
  const pb = postsOf.get(b)
  if (!pa || !pb) return sheet
  return totalled(sheet, sheet.rows.map((r) =>
    r.trooper === a ? withHours({ ...r, posts: pb })
      : r.trooper === b ? withHours({ ...r, posts: pa })
        : r))
}

export type Coverage = 'none' | 'full' | 'short' | 'over'

export interface SlotCoverage {
  manned: number
  needed: number
  state: Coverage
}

/**
 * Bodies against demand, per hour. Only counting: what each hour needs comes
 * from the server's demand, so the rules for which posts stand when are never
 * written twice.
 */
export function coverage(sheet: Schedule, demand: SheetCheck['demand']): SlotCoverage[] {
  return Array.from({ length: SLOT_COUNT }, (_, slot) => {
    const need = demand[slot] ?? {}
    const have: Partial<Record<Post, number>> = {}
    for (const row of sheet.rows) {
      const post = row.posts[slot]
      if (post) have[post] = (have[post] ?? 0) + 1
    }
    const needed = Object.values(need).reduce((a, b) => a + (b ?? 0), 0)
    const manned = Object.values(have).reduce((a, b) => a + (b ?? 0), 0)
    const posts = new Set([...Object.keys(need), ...Object.keys(have)]) as Set<Post>
    let state: Coverage = needed === 0 && manned === 0 ? 'none' : 'full'
    for (const post of posts) {
      const h = have[post] ?? 0
      const n = need[post] ?? 0
      if (h > n) { state = 'over'; break }
      if (h < n) state = 'short'
    }
    return { manned, needed, state }
  })
}

/**
 * Per hour, the posts nobody from the team is on: what the MP team covers on a
 * fixed-turnout sheet. A post short twice at one hour appears twice.
 */
export function mpShortfall(sheet: Schedule, demand: SheetCheck['demand']): Post[][] {
  return Array.from({ length: SLOT_COUNT }, (_, slot) => {
    const need = demand[slot] ?? {}
    const out: Post[] = []
    for (const post of ['AP', 'GG', 'PAC', 'BUS'] as Post[]) {
      const have = sheet.rows.filter((r) => r.posts[slot] === post).length
      for (let k = have; k < (need[post] ?? 0); k++) out.push(post)
    }
    return out
  })
}
