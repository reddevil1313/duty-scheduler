import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { api, setCsrfToken } from '../api/client'
import type { Session } from '../api/types'
import { SessionContext } from './session-context'

/**
 * Holds the signed-in identity and the CSRF token for the whole app.
 *
 * <p>Every path through this component ends in `adopt`, which is the only place
 * `setCsrfToken` is called. That is deliberate: signing in and signing out both
 * rotate the token server-side, so any code path that changes the session and
 * forgets to store the new token leaves the app able to read but not write —
 * a failure that shows up much later, as a 403 on a button.
 */
export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null)

  const adopt = useCallback((next: Session) => {
    setCsrfToken(next.csrfToken)
    setSession(next)
  }, [])

  useEffect(() => {
    let cancelled = false
    api.session()
      .then((next) => {
        if (!cancelled) adopt(next)
      })
      .catch(() => {
        // The session endpoint is the one call that should never fail. If it
        // does the server is down, and showing the sign-in form is the honest
        // response — the user will find out when they try.
        if (!cancelled) {
          adopt({ authenticated: false, username: null, displayName: null, role: null, csrfToken: '' })
        }
      })
    return () => {
      cancelled = true
    }
  }, [adopt])

  const signIn = useCallback(async (username: string, password: string) => {
    adopt(await api.login(username, password))
  }, [adopt])

  const signOut = useCallback(async () => {
    adopt(await api.logout())
  }, [adopt])

  const recheck = useCallback(async () => {
    adopt(await api.session())
  }, [adopt])

  const value = useMemo(
    () => ({ session, signIn, signOut, recheck }),
    [session, signIn, signOut, recheck],
  )

  return <SessionContext value={value}>{children}</SessionContext>
}
