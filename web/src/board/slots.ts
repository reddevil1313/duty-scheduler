/**
 * The clock, in one place.
 *
 * <p>A duty sheet is named by the evening it starts: slot 0 is 2000 on that
 * date, and the sheet runs to 1959 the following morning. So the hour a slot
 * stands for is `(20 + slot) % 24`, and the same expression appears in
 * `DutyDay` on the server. It is short enough that the temptation is to inline
 * it, and inlining it is how the board and the solver end up disagreeing about
 * which hours are night.
 */
export const SLOT_COUNT = 24

/** 0 -> 20, 4 -> 0, 23 -> 19. */
export function hourAt(slot: number): number {
  return (20 + slot) % 24
}

/** "2000", "0100". Four digits, because a duty sheet is not written "1 AM". */
export function labelAt(slot: number): string {
  return `${String(hourAt(slot)).padStart(2, '0')}00`
}

/**
 * Silent hours: 2200 to 0559, which is slots 2 through 9 — fixed, because the
 * sheet always starts at 2000. The server's `Availability.silentSlots`; keep the
 * two in step. Used to tell an MP hour, which must keep someone from the team
 * on, from a silent hour, which must be fully manned.
 */
export function isSilent(slot: number): boolean {
  return slot >= 2 && slot <= 9
}

/** The 0600-0800 block a night Trooper may be extended into. */
export function isCrossover(slot: number): boolean {
  return slot >= 10 && slot <= 12
}

/** The date the slot actually falls on — the next day from slot 4 onwards. */
export function dateAt(sheetDate: string, slot: number): string {
  if (hourAt(slot) >= 20) return sheetDate
  const next = new Date(`${sheetDate}T00:00:00`)
  next.setDate(next.getDate() + 1)
  return next.toISOString().slice(0, 10)
}
