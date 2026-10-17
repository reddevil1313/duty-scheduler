/** Dates as the API sends them: `YYYY-MM-DD`, and `YYYY-MM-DDTHH:MM[:SS]` for instants. */

export function today(): string {
  // Not toISOString(), which is UTC.
  const now = new Date()
  const offset = now.getTimezoneOffset() * 60_000
  return new Date(now.getTime() - offset).toISOString().slice(0, 10)
}

/** Local `YYYY-MM-DD` of a zoned instant such as an invite's expiry. */
export function localDay(instant: string): string {
  const d = new Date(instant)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** Local now as `YYYY-MM-DDTHH:MM`. */
export function nowMinute(): string {
  const now = new Date()
  const offset = now.getTimezoneOffset() * 60_000
  return new Date(now.getTime() - offset).toISOString().slice(0, 16)
}

/** The next day, from local parts (toISOString would use UTC). */
export function nextDay(date: string): string {
  const [y, m, d] = date.split('-').map(Number)
  const next = new Date(y, m - 1, d + 1)
  return `${next.getFullYear()}-${String(next.getMonth() + 1).padStart(2, '0')}-${String(next.getDate()).padStart(2, '0')}`
}

const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

/**
 * `2026-09-10` -> `Thu 10 Sep`. Not toLocaleDateString, which gives "Sept"
 * in some browsers. Not `new Date(string)`, which parses as UTC.
 */
export function shortDate(date: string): string {
  const [y, m, d] = date.split('-').map(Number)
  const day = new Date(y, m - 1, d).getDay()
  return `${DAYS[day]} ${d} ${MONTHS[m - 1]}`
}

/** `2026-09-10T20:00:00` -> `Thu 10 Sep 2000`. */
export function shortInstant(instant: string): string {
  const [date, time] = instant.split('T')
  return `${shortDate(date)} ${time.slice(0, 2)}${time.slice(3, 5)}`
}
