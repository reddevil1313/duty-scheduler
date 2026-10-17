import { createContext, useContext } from 'react'
import type { Session } from '../api/types'

export interface SessionState {
  /** Null only before the first read finishes. */
  session: Session | null
  signIn: (username: string, password: string) => Promise<void>
  signOut: () => Promise<void>
  /** Re-read the session after a 401 (timed out, or signed out elsewhere). */
  recheck: () => Promise<void>
}

export const SessionContext = createContext<SessionState | null>(null)

export function useSession(): SessionState {
  const state = useContext(SessionContext)
  if (!state) {

    throw new Error('useSession must be used inside <SessionProvider>')
  }
  return state
}
