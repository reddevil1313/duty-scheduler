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

export type AbsenceKind = 'MC' | 'OL' | 'AL' | 'MA'
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
  /** The resolved interval, worked out server-side so the 2000 rule lives in one place. */
  covers: string
  until: string
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
  name: string
  rank: string | null
  stayOut: boolean
  permPac: boolean
  onNight: boolean
}
