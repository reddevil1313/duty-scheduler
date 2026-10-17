import type {
  Absence, AbsenceReach, AbsenceRequest, GenerateOptions, Invite, InviteView, Member, NewAccount, ProblemDetail,
  Records, Schedule, Session, SheetCheck, Team, TeamCreated, Ticks, Trooper,
  TrooperParticulars,
} from './types'

// All server calls. Components use these, never fetch directly, so
// credentials, the CSRF header and error handling live in one place.

// No parameter properties: erasableSyntaxOnly forbids them.
export class ApiError extends Error {
  readonly status: number
  readonly title?: string

  constructor(status: number, message: string, title?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.title = title
  }
}

let csrfToken: string | null = null

/** From the last session read; sent with every write. */
export function setCsrfToken(token: string | null) {
  csrfToken = token
}

async function send(path: string, init: RequestInit = {}): Promise<Response> {
  const method = init.method ?? 'GET'
  const headers = new Headers(init.headers)

  if (method !== 'GET' && method !== 'HEAD') {
    if (init.body !== undefined && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json')
    }
    if (csrfToken) {
      headers.set('X-XSRF-TOKEN', csrfToken)
    }
  }

  const response = await fetch(path, {
    ...init,
    headers,
    credentials: 'include',
  })

  if (!response.ok) {
    throw await errorFor(response)
  }
  return response
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await send(path, init)
  if (response.status === 204 || response.headers.get('Content-Length') === '0') {
    return undefined as T
  }
  return (await response.json()) as T
}

/** For binary responses like the sheet image. */
async function requestBlob(path: string, init: RequestInit = {}): Promise<Blob> {
  return (await send(path, init)).blob()
}

async function errorFor(response: Response): Promise<ApiError> {
  try {
    const problem = (await response.json()) as ProblemDetail
    const detail = problem.detail ?? problem.title
    if (detail) {
      return new ApiError(response.status, detail, problem.title)
    }
  } catch {
    // Not every failure has a JSON body.
  }
  // 401/403 from the security filters have no problem body.
  if (response.status === 401) {
    return new ApiError(401, 'Your session has expired. Sign in again.')
  }
  if (response.status === 403) {
    return new ApiError(403, 'You do not have permission to do that.')
  }
  return new ApiError(response.status, `Request failed (${response.status}).`)
}

export const api = {
  /** Call again after sign-in and sign-out: the CSRF token rotates. */
  session: () => request<Session>('/api/session'),

  /** Form-encoded, because formLogin reads request parameters. */
  async login(username: string, password: string): Promise<Session> {
    await request<void>('/api/session/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ username, password }),
    })
    return api.session()
  },

  async logout(): Promise<Session> {
    await request<void>('/api/session/logout', { method: 'POST' })
    return api.session()
  },

  generate: (date: string, options: GenerateOptions) =>
    request<Schedule>('/api/schedules/generate', {
      method: 'POST',
      body: JSON.stringify({
        date,
        nightGroup: options.nightGroup,
        out: Object.entries(options.ticks ?? {}).map(([trooper, kind]) => ({ trooper, kind })),
        bus: options.bus ?? null,
        busDefault: options.busDefault ?? false,
        turnout: options.turnout ?? null,
      }),
    }),

  schedule: (date: string) => request<Schedule>(`/api/schedules/${date}`),

  /** Logs the sheet and returns its image. */
  logAndImage: (date: string, schedule: Schedule) =>
    requestBlob(`/api/schedules/${date}/image`, { method: 'POST', body: JSON.stringify(schedule) }),

  image: (date: string) => requestBlob(`/api/schedules/${date}/image`),

  check: (date: string, schedule: Schedule, ticks: Ticks = {}) =>
    // Ticks go as out=NAME:KIND since the body is the sheet.
    request<SheetCheck>(`/api/schedules/${date}/check?${new URLSearchParams(
      Object.entries(ticks).map(([name, kind]) => ['out', `${name}:${kind}`]))}`, {
      method: 'POST',
      body: JSON.stringify(schedule),
    }),

  credit: (date: string, hours: Record<string, number>) =>
    request<void>(`/api/schedules/${date}/credits`, {
      method: 'PUT',
      body: JSON.stringify(hours),
    }),

  records: () => request<Records>('/api/records'),

  setOpening: (hours: Record<string, number>) =>
    request<Records>('/api/records/opening', {
      method: 'PUT',
      body: JSON.stringify(hours),
    }),

  log: (date: string, schedule: Schedule) =>
    request<Schedule>(`/api/schedules/${date}`, {
      method: 'PUT',
      body: JSON.stringify(schedule),
    }),

  absences: () => request<Absence[]>('/api/absences'),

  bookAbsence: (absence: AbsenceRequest) =>
    request<Absence>('/api/absences', { method: 'POST', body: JSON.stringify(absence) }),

  previewAbsence: (absence: AbsenceRequest) =>
    request<AbsenceReach>('/api/absences/preview', { method: 'POST', body: JSON.stringify(absence) }),

  /** ADMIN only. */
  cancelAbsence: (id: number) =>
    request<void>(`/api/absences/${id}`, { method: 'DELETE' }),

  troopers: () => request<Trooper[]>('/api/troopers'),

  addTrooper: (trooper: TrooperParticulars) =>
    request<Trooper>('/api/troopers', {
      method: 'POST',
      body: JSON.stringify(trooper),
    }),

  updateTrooper: (id: number, trooper: TrooperParticulars) =>
    request<Trooper>(`/api/troopers/${id}`, {
      method: 'PUT',
      body: JSON.stringify(trooper),
    }),

  removeTrooper: (id: number) =>
    request<void>(`/api/troopers/${id}`, { method: 'DELETE' }),

  /** OWNER only. */
  teams: () => request<Team[]>('/api/teams'),

  createTeam: (name: string) =>
    request<TeamCreated>('/api/teams', { method: 'POST', body: JSON.stringify({ name }) }),

  inviteToTeam: (teamId: number, role: Invite['role']) =>
    request<Invite>(`/api/teams/${teamId}/invites`, { method: 'POST', body: JSON.stringify({ role }) }),

  invites: () => request<Invite[]>('/api/invites'),

  /** The only response with the token. */
  createInvite: (role: Invite['role']) =>
    request<Invite>('/api/invites', { method: 'POST', body: JSON.stringify({ role }) }),

  revokeInvite: (id: number) => request<void>(`/api/invites/${id}`, { method: 'DELETE' }),

  inviteView: (token: string) => request<InviteView>(`/api/invites/${encodeURIComponent(token)}`),

  /** Doesn't sign in; that's a separate call. */
  acceptInvite: (token: string, account: NewAccount) =>
    request<InviteView>(`/api/invites/${encodeURIComponent(token)}/accept`, {
      method: 'POST',
      body: JSON.stringify(account),
    }),

  /** ADMIN only. */
  members: () => request<Member[]>('/api/members'),

  setMemberRole: (id: number, role: Member['role']) =>
    request<Member>(`/api/members/${id}`, { method: 'PUT', body: JSON.stringify({ role }) }),

  removeMember: (id: number) => request<void>(`/api/members/${id}`, { method: 'DELETE' }),
}
