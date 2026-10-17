// `/#invite=TOKEN`: the fragment is never sent to a server, so the token
// stays out of logs and Referer headers.
const PATTERN = /^#invite=([A-Za-z0-9_-]+)$/

export function inviteToken(): string | null {
  return PATTERN.exec(window.location.hash)?.[1] ?? null
}

/** Remove the token from the address bar. */
export function clearInvite() {
  window.history.replaceState(null, '', window.location.pathname + window.location.search)
}

export function inviteLink(token: string): string {
  return `${window.location.origin}${window.location.pathname}#invite=${token}`
}
