/**
 * Invite links are `…/#invite=TOKEN`. In the fragment, which the browser
 * never sends to a server: the token stays out of access logs, proxies and
 * Referer headers, and only this page's script ever reads it.
 */
const PATTERN = /^#invite=([A-Za-z0-9_-]+)$/

export function inviteToken(): string | null {
  return PATTERN.exec(window.location.hash)?.[1] ?? null
}

/** Drop the token from the address bar, so a reload or a shared screen does not carry it. */
export function clearInvite() {
  window.history.replaceState(null, '', window.location.pathname + window.location.search)
}

export function inviteLink(token: string): string {
  return `${window.location.origin}${window.location.pathname}#invite=${token}`
}
