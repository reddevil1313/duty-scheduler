import { createContext, useContext } from 'react'
import type { Session } from '../api/types'

export interface SessionState {
  /** Null only before the first read finishes. */
  session: Session | null
  signIn: (username: string, password: string) => Promise<void>
  signOut: () => Promise<void>
  /**
   * Re-read the session after a call came back 401.
   *
   * <p>A session can end while the tab is still open — it times out, or someone
   * signs out in another tab. Without this the board would keep showing a
   * signed-in shell over an API that refuses everything.
   */
  recheck: () => Promise<void>
}

export const SessionContext = createContext<SessionState | null>(null)

export function useSession(): SessionState {
  const state = useContext(SessionContext)
  if (!state) {
    // A missing provider is a wiring mistake, and the default-value trick hides
    // it until something reads a field and gets undefined three screens later.
    throw new Error('useSession must be used inside <SessionProvider>')
  }
  return state
}
