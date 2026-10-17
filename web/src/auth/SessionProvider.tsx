import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { api, setCsrfToken } from '../api/client'
import type { Session } from '../api/types'
import { SessionContext } from './session-context'

/**
 * The signed-in identity and CSRF token. Every path goes through `adopt`, the
 * only caller of `setCsrfToken`, since the token rotates on sign-in and out.
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
        // Server is down: show the sign-in form.
        if (!cancelled) {
          adopt({ authenticated: false, username: null, displayName: null, role: null, team: null, csrfToken: '' })
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
