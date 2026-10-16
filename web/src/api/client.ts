import type {
  Absence, AbsenceReach, AbsenceRequest, ProblemDetail, Schedule, Session, Trooper, TrooperParticulars,
} from './types'

/**
 * Everything that talks to the server, in one file.
 *
 * <p>Components call named functions, never `fetch` directly. That keeps three
 * things — credentials, the CSRF header, and error shape — in a single place
 * rather than repeated at every call site with one of them forgotten.
 */

/**
 * A failed request, carrying the status so callers can branch on it.
 *
 * <p>Written the long way — fields declared, then assigned — rather than as
 * constructor parameter properties. `erasableSyntaxOnly` is on in this template
 * and forbids any TypeScript that does not vanish when the types are stripped;
 * `constructor(readonly status: number)` quietly emits an assignment, so it is
 * not erasable and the compiler rejects it. Node can now run .ts files directly
 * by stripping types, and that only works if nothing in the file compiles to
 * behaviour.
 */
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

/** Held from the last session read. Every write presents it. */
export function setCsrfToken(token: string | null) {
  csrfToken = token
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
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
    // The session is a cookie. Without this the browser sends the request
    // unauthenticated and every call comes back 401 for no visible reason.
    credentials: 'include',
  })

  if (!response.ok) {
    throw await errorFor(response)
  }
  if (response.status === 204 || response.headers.get('Content-Length') === '0') {
    return undefined as T
  }
  return (await response.json()) as T
}

async function errorFor(response: Response): Promise<ApiError> {
  try {
    const problem = (await response.json()) as ProblemDetail
    const detail = problem.detail ?? problem.title
    if (detail) {
      return new ApiError(response.status, detail, problem.title)
    }
  } catch {
    // Fall through: not every failure has a JSON body.
  }
  // A 401 or 403 from the security filter chain never reaches a controller, so
  // it has no problem-detail body to parse and needs wording of its own.
  if (response.status === 401) {
    return new ApiError(401, 'Your session has expired. Sign in again.')
  }
  if (response.status === 403) {
    return new ApiError(403, 'You do not have permission to do that.')
  }
  return new ApiError(response.status, `Request failed (${response.status}).`)
}

export const api = {
  /**
   * Who am I, and what token do writes need?
   *
   * <p>Always 200, signed in or not. Called on load, again after signing in and
   * again after signing out — the last two are not tidiness: Spring rotates the
   * CSRF token on both events, so the token held from before either one is dead.
   */
  session: () => request<Session>('/api/session'),

  /**
   * Sign in.
   *
   * <p>Form-encoded, not JSON. Spring Security's `formLogin` reads `username`
   * and `password` as request parameters; posting JSON to it authenticates
   * nobody. Hence the explicit Content-Type — it still goes through `request`,
   * so it still carries the CSRF token, which a POST to a permitAll URL needs
   * just as much as any other.
   */
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

  generate: (date: string, nightGroup: string[]) =>
    request<Schedule>('/api/schedules/generate', {
      method: 'POST',
      body: JSON.stringify({ date, nightGroup }),
    }),

  schedule: (date: string) => request<Schedule>(`/api/schedules/${date}`),

  log: (date: string, schedule: Schedule) =>
    request<Schedule>(`/api/schedules/${date}`, {
      method: 'PUT',
      body: JSON.stringify(schedule),
    }),

  /** The leave register, latest first. Anyone signed in. */
  absences: () => request<Absence[]>('/api/absences'),

  /** ADMIN only. 400 with the reason when the booking does not make sense. */
  bookAbsence: (absence: AbsenceRequest) =>
    request<Absence>('/api/absences', { method: 'POST', body: JSON.stringify(absence) }),

  /** What a booking would reach, saving nothing. Still a POST, so still needs the CSRF token. */
  previewAbsence: (absence: AbsenceRequest) =>
    request<AbsenceReach>('/api/absences/preview', { method: 'POST', body: JSON.stringify(absence) }),

  /** ADMIN only. */
  cancelAbsence: (id: number) =>
    request<void>(`/api/absences/${id}`, { method: 'DELETE' }),

  troopers: () => request<Trooper[]>('/api/troopers'),

  /**
   * ADMIN only. 409 when the name is taken, 422 for a stay-out on the night
   * group. An archived Trooper's name brings that Trooper back.
   */
  addTrooper: (trooper: TrooperParticulars) =>
    request<Trooper>('/api/troopers', {
      method: 'POST',
      body: JSON.stringify(trooper),
    }),

  /** ADMIN only. Same refusals as adding, plus 404 for someone no longer on the roster. */
  updateTrooper: (id: number, trooper: TrooperParticulars) =>
    request<Trooper>(`/api/troopers/${id}`, {
      method: 'PUT',
      body: JSON.stringify(trooper),
    }),

  /** ADMIN only. Archived if they stand on a logged sheet, deleted if not — 204 either way. */
  removeTrooper: (id: number) =>
    request<void>(`/api/troopers/${id}`, { method: 'DELETE' }),
}
