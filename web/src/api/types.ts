// Mirrors the Java DTOs. Keep them in step by hand.

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
  /** null or absent for the free split. */
  turnout?: number | null
  /** Date of the reused logged sheet, if any. */
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

export interface SheetCheck {
  issues: SheetIssue[]
  /** Per slot, how many Troopers each post needs. An hour with no posts is `{}`. */
  demand: Partial<Record<Post, number>>[]
  /** With hours and span recomputed. */
  sheet: Schedule
  /** 24 entries per Trooper: leave kind, `SO` (weekend stay-out) or null. */
  away: Record<string, (AbsenceKind | 'SO' | null)[]>
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

  issues: number
}

export interface Records {
  troopers: TrooperRecord[]
  days: DayRecord[]
}

export type AbsenceKind = 'MC' | 'OL' | 'AL' | 'MA'

export type TickKind = 'MC' | 'OL' | 'AL'

/** Roster ticks by name. Browser only, never saved. */
export type Ticks = Record<string, TickKind>

export interface GenerateOptions {
  nightGroup: string[]
  ticks?: Ticks

  bus?: string | null
  /** `bus` is only the roster default, so the solver may drop it. */
  busDefault?: boolean
  /** 5 to 7; absent for the free split. */
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

  covers: string
  until: string
  /** Duty dates whose sheets this reaches. */
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

export interface AbsenceReach {
  covers: string
  until: string
  sheets: string[]
}

export type Role = 'OWNER' | 'ADMIN' | 'USER'

export interface Session {
  authenticated: boolean
  username: string | null
  displayName: string | null

  role: Role | null
  /** null when signed out, and for the owner. */
  team: string | null
  csrfToken: string
}

export interface Team {
  id: number
  name: string
  createdAt: string
  admins: number
  users: number
}

/** `token` is only present when the invite is created. */
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

  you: boolean
}

/** RFC 9457. */
export interface ProblemDetail {
  title?: string
  detail?: string
  status?: number
}

export interface Trooper {

  id: number
  name: string
  rank: string | null
  stayOut: boolean
  permPac: boolean
  onNight: boolean
  /** The board's starting BUS pick. At most one. */
  defaultBus: boolean
}

export interface TrooperParticulars {
  name: string
  rank: string | null
  stayOut: boolean
  onNight: boolean
  /** Absent leaves it unchanged. */
  defaultBus?: boolean
}
