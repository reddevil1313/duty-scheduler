import type { Post, Schedule, ScheduleRow, SheetCheck } from '../api/types'
import { SLOT_COUNT, isSilent } from './slots'

// Hand edits. Each returns a new sheet. Hours are recounted here; span comes
// from the server's check.

function withHours(row: ScheduleRow): ScheduleRow {
  return { ...row, hours: row.posts.filter((p) => p !== null).length }
}

function totalled(sheet: Schedule, rows: ScheduleRow[]): Schedule {
  return { ...sheet, rows, totalHours: rows.reduce((sum, r) => sum + r.hours, 0) }
}

/** Set or clear one hour. Adds a row if the Trooper has none. */
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

/** Manned vs needed per hour, using the server's demand. */
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

/** Unmanned posts per hour, for the MP row. */
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

type Placed = Pick<ScheduleRow, 'trooper' | 'stayOut' | 'posts'>

/** Night, day, stay-outs, then away: 0 to 3. */
function groupOf(row: Placed, away: SheetCheck['away'] | undefined): number {
  if (away?.[row.trooper]?.every((k) => k !== null)) return 3
  if (row.posts.some((post, slot) => post !== null && isSilent(slot))) return 0
  return row.stayOut ? 2 : 1
}

/**
 * Night (any silent hour), day, stay-outs, then away, keeping order within
 * each. Must match ScheduleResponse.inBoardOrder. `basis` is the sheet to
 * group by, so rows stay put while editing.
 */
export function boardOrder<R extends Placed>(rows: R[], away: SheetCheck['away'] | undefined, basis?: Placed[]): R[] {
  const from = new Map((basis ?? []).map((r) => [r.trooper, r]))
  return rows
    .map((row, i) => ({ row, i, group: groupOf(from.get(row.trooper) ?? row, away) }))
    .sort((a, b) => a.group - b.group || a.i - b.i)
    .map((x) => x.row)
}
