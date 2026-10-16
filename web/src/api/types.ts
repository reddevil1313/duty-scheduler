/**
 * The API contract, in TypeScript.
 *
 * <p>These mirror the Java DTOs field for field. Nothing enforces that at build
 * time — they are two hand-kept files — so the rule is that they change in the
 * same commit, and the board below is written so that a mismatch shows up as a
 * type error rather than as `undefined` on a screen.
 */

/** A post is one of four, or null for an hour off duty. */
export type Post = 'AP' | 'GG' | 'PAC' | 'BUS'

export interface ScheduleRow {
  trooper: string
  stayOut: boolean
  /** Always 24 long. A null is an hour off duty. */
  posts: (Post | null)[]
  hours: number
  span: number
}

export interface Schedule {
  date: string
  rows: ScheduleRow[]
  totalHours: number
}

/** One thing a sheet gets wrong. `rule` is a validator rule name, or ON_LEAVE. */
export interface SheetIssue {
  rule: string
  trooper: string | null
  /** The first hour it concerns, when it concerns one. */
  slot: number | null
  message: string
}

/** The server's verdict on a sheet, from the same validator that checks the solver. */
export interface SheetCheck {
  issues: SheetIssue[]
  /** Per slot, how many Troopers each post needs. An hour with no posts is `{}`. */
  demand: Partial<Record<Post, number>>[]
  /** The sheet as checked, with hours and span worked out again by the server. */
  sheet: Schedule
  /**
   * Per Trooper with leave on this sheet, 24 entries: the kind of leave
   * covering that hour, or null where they are free.
   */
  away: Record<string, (AbsenceKind | null)[]>
}

export interface TrooperRecord {
  name: string
  active: boolean
  opening: number
  worked: number
  credited: number
  total: number
  duties: number
  nights: number
  bus: number
  mc: number
  ol: number
  al: number
  ma: number
}

export interface DayRecord {
  date: string
  worked: number
  credited: number
  /** Hours credited that day, by Trooper. */
  credits: Record<string, number>
  night: string[]
  bus: string[]
  /** How many rules the day as logged breaks. Non-zero only for a sheet logged anyway. */
  issues: number
}

export interface Records {
  troopers: TrooperRecord[]
  days: DayRecord[]
}

export type AbsenceKind = 'MC' | 'OL' | 'AL' | 'MA'

/** What a roster tick can say. An appointment is hours, not a sheet, so it is booked instead. */
export type TickKind = 'MC' | 'OL' | 'AL'

/**
 * Troopers ticked out on the roster, by name. Not leave bookings: held in the
 * browser only, sent with each generate and check, and gone on sign-out.
 */
export type Ticks = Record<string, TickKind>
export type AbsenceSpan = 'FULL' | 'DAY_HALF' | 'NIGHT_HALF' | 'WINDOW'

export interface Absence {
  id: number
  trooper: string
  kind: AbsenceKind
  span: AbsenceSpan
  from: string
  to: string
  start: string | null
  end: string | null
  note: string | null
  /** The resolved interval, worked out server-side so the 2000 rule lives in one place. */
  covers: string
  until: string
  /** Duty dates whose sheets this reaches. A sheet is named by the evening it starts. */
  sheets: string[]
}

/** Book leave. `to` is read only for FULL; `start` and `end` only for WINDOW. */
export interface AbsenceRequest {
  trooper: string
  kind: AbsenceKind
  span: AbsenceSpan
  from: string
  to: string | null
  start: string | null
  end: string | null
  note: string | null
}

/** What a booking would take, from the preview endpoint. Nothing is saved. */
export interface AbsenceReach {
  covers: string
  until: string
  sheets: string[]
}

/**
 * Who the caller is, and the token their writes must carry.
 *
 * <p>`authenticated` rather than a 401, because this one response has two jobs.
 * It answers "am I signed in?", and it hands over a CSRF token — which the login
 * POST itself needs, before anybody is signed in at all. A 401 would satisfy the
 * first job and make the second impossible.
 */
export interface Session {
  authenticated: boolean
  username: string | null
  displayName: string | null
  role: 'ADMIN' | 'USER' | null
  csrfToken: string
}

/** RFC 9457, which is what the API returns for every failure. */
export interface ProblemDetail {
  title?: string
  detail?: string
  status?: number
}

/** A name on the roster, with the flags the board needs to lay itself out. */
export interface Trooper {
  /** What edits and removals address. Not the name, which an edit can change. */
  id: number
  name: string
  rank: string | null
  stayOut: boolean
  permPac: boolean
  onNight: boolean
}

/**
 * The particulars an ADMIN sets when adding or editing a Trooper. `onNight`
 * pre-ticks them for the night group; it is a default, not a rule.
 */
export interface TrooperParticulars {
  name: string
  rank: string | null
  stayOut: boolean
  onNight: boolean
}
