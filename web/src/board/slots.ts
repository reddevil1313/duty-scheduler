import { nextDay } from '../dates'

// Slot 0 is 2000 on the sheet's date, so hour = (20 + slot) % 24. Same as
// DutyDay on the server.
export const SLOT_COUNT = 24

/** 0 -> 20, 4 -> 0, 23 -> 19. */
export function hourAt(slot: number): number {
  return (20 + slot) % 24
}

/** "2000", "0100". */
export function labelAt(slot: number): string {
  return `${String(hourAt(slot)).padStart(2, '0')}00`
}

/** 2200-0559, slots 2 to 9. Same as `Availability.silentSlots`. */
export function isSilent(slot: number): boolean {
  return slot >= 2 && slot <= 9
}

/** The 0600-0800 block a night Trooper may be extended into. */
export function isCrossover(slot: number): boolean {
  return slot >= 10 && slot <= 12
}

/** The slot's calendar date; the next day from slot 4. */
export function dateAt(sheetDate: string, slot: number): string {
  return hourAt(slot) >= 20 ? sheetDate : nextDay(sheetDate)
}
