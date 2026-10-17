/** Dates as the API sends them: `YYYY-MM-DD`, and `YYYY-MM-DDTHH:MM[:SS]` for instants. */

export function today(): string {
  // Local, not toISOString(). A sheet is named by the evening it starts, and at
  // 2100 at UTC+8 toISOString() already says tomorrow.
  const now = new Date()
  const offset = now.getTimezoneOffset() * 60_000
  return new Date(now.getTime() - offset).toISOString().slice(0, 10)
}

/**
 * The local date of an instant the server sent with a zone (`…Z`), such as
 * an invite's expiry, as `YYYY-MM-DD` for shortDate.
 */
export function localDay(instant: string): string {
  const d = new Date(instant)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** Local now as `YYYY-MM-DDTHH:MM`, the shape the API's instants take to the minute. */
export function nowMinute(): string {
  const now = new Date()
  const offset = now.getTimezoneOffset() * 60_000
  return new Date(now.getTime() - offset).toISOString().slice(0, 16)
}

/**
 * The day after, `YYYY-MM-DD`. Built from local parts: going through
 * toISOString() would read local midnight as UTC, and east of Greenwich hand
 * back the same day.
 */
export function nextDay(date: string): string {
  const [y, m, d] = date.split('-').map(Number)
  const next = new Date(y, m - 1, d + 1)
  return `${next.getFullYear()}-${String(next.getMonth() + 1).padStart(2, '0')}-${String(next.getDate()).padStart(2, '0')}`
}

const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

/**
 * `2026-09-10` → `Thu 10 Sep`.
 *
 * <p>Spelled out by hand rather than with toLocaleDateString: browsers' locale
 * data disagree (current Chrome writes "Sept" for en-GB), and a board should
 * not change its spelling with the browser it is opened in. Parsed as a local
 * date, never through `new Date(string)`, which reads it as UTC.
 */
export function shortDate(date: string): string {
  const [y, m, d] = date.split('-').map(Number)
  const day = new Date(y, m - 1, d).getDay()
  return `${DAYS[day]} ${d} ${MONTHS[m - 1]}`
}

/** `2026-09-10T20:00:00` → `Thu 10 Sep 2000`, the way times are written on the board. */
export function shortInstant(instant: string): string {
  const [date, time] = instant.split('T')
  return `${shortDate(date)} ${time.slice(0, 2)}${time.slice(3, 5)}`
}
