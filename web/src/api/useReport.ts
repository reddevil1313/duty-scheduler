import { useCallback } from 'react'
import { useSession } from '../auth/session-context'
import { ApiError } from './client'

/**
 * One place where a failed call becomes something on screen.
 *
 * <p>The 401 branch is the reason this exists rather than a try/catch at each
 * call site. A session that has quietly expired makes every button fail, and
 * without the recheck the app would keep drawing a signed-in shell over an API
 * that refuses everything.
 */
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
