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
  /** The fixed turnout it was made with; null or absent for the free split. Logged with the sheet. */
  turnout?: number | null
  /** On a generated sheet, the logged sheet it reuses; null when it was solved. */
  reusedFrom?: string | null
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

/** What a generate is asked for, beyond the date. */
export interface GenerateOptions {
  nightGroup: string[]
  ticks?: Ticks
  /** Who takes the BUS run; left out, the solver chooses. */
  bus?: string | null
  /** True when `bus` is the roster's default rather than a pick: it then gives way instead of being refused. */
  busDefault?: boolean
  /** Everyone's hours, 5 to 7, the rest to the MP team; left out, the free split. */
  turnout?: number | null
}
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
export type Role = 'OWNER' | 'ADMIN' | 'USER'

export interface Session {
  authenticated: boolean
  username: string | null
  displayName: string | null
  /** OWNER runs the server and belongs to no team; ADMIN and USER belong to one. */
  role: Role | null
  /** The caller's team by name; null when signed out, and for the owner. */
  team: string | null
  csrfToken: string
}

/** A team as the owner sees it: counts of accounts, never its data. */
export interface Team {
  id: number
  name: string
  createdAt: string
  admins: number
  users: number
}

/**
 * An invite to join a team. `token` comes back only from the call that makes
 * it: the server keeps a hash, so the link cannot be shown again later.
 */
export interface Invite {
  id: number
  role: 'ADMIN' | 'USER'
  createdBy: string
  createdAt: string
  expiresAt: string
  token: string | null
}

export interface TeamCreated {
  team: Team
  invite: Invite
}

/** What an invite link is for, shown to whoever opens it. */
export interface InviteView {
  team: string
  role: 'ADMIN' | 'USER'
  expiresAt: string
}

export interface NewAccount {
  username: string
  displayName: string
  password: string
}

/** An account in the caller's team. */
export interface Member {
  id: number
  username: string
  displayName: string
  role: 'ADMIN' | 'USER'
  /** The caller's own row, which cannot be removed. */
  you: boolean
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
  /** Who the board's BUS pick starts on. At most one Trooper, never a stay-out. */
  defaultBus: boolean
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
  /** Left out, the server keeps whatever it was; true moves the default here. */
  defaultBus?: boolean
}
