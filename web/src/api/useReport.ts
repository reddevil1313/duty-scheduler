import { useCallback } from 'react'
import { useSession } from '../auth/session-context'
import { ApiError } from './client'

/** Turns a failed call into a message. A 401 re-checks the session. */
export function useReport(setError: (message: string | null) => void) {
  const { recheck } = useSession()
  return useCallback(async (e: unknown) => {
    if (e instanceof ApiError && e.status === 401) {
      await recheck()
      return
    }
    setError(e instanceof Error ? e.message : 'Something went wrong.')
  }, [recheck, setError])
}
