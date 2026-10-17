import { useEffect, useState, type FormEvent } from 'react'
import { ApiError, api } from '../api/client'
import type { InviteView } from '../api/types'
import { useSession } from '../auth/session-context'
import { localDay, shortDate } from '../dates'

type State = { kind: 'loading' } | { kind: 'invalid'; message: string } | { kind: 'open'; view: InviteView }

interface Props {
  token: string
  /** Leave the invite: clears the link from the address bar. */
  onDone: () => void
}

/**
 * Where an invite link lands. Says which team and role it is for, then makes
 * the account and signs it in, so the next thing the person sees is their
 * team's board.
 */
export function AcceptInvitePage({ token, onDone }: Props) {
  const { session, signIn, signOut } = useSession()
  const [state, setState] = useState<State>({ kind: 'loading' })
  const [username, setUsername] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let stale = false
    api.inviteView(token)
      .then((view) => { if (!stale) setState({ kind: 'open', view }) })
      .catch((e: unknown) => {
        if (stale) return
        setState({
          kind: 'invalid',
          message: e instanceof ApiError && e.status === 404 ? e.message : 'The invite could not be read. Try the link again.',
        })
      })
    return () => { stale = true }
  }, [token])

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    // The one check made here: two copies of a password are the user's own to
    // compare. Everything about what a username or password may be is the
    // server's to say.
    if (password !== confirm) {
      setError('The two passwords are not the same.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      await api.acceptInvite(token, { username, displayName, password })
      await signIn(username, password)
      onDone()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'The account could not be made.')
      setBusy(false)
    }
  }

  return (
    <main className="login">
      <form onSubmit={(e) => void submit(e)}>
        <div>
          <div className="rule" />
          <h1>Join a team</h1>
        </div>
        {state.kind === 'loading' && <p className="note">Reading the invite…</p>}
        {state.kind === 'invalid' && (
          <>
            <p className="error" role="alert">{state.message}</p>
            <p className="note">Ask whoever sent it for a new link.</p>
            <button type="button" onClick={onDone}>Go to sign-in</button>
          </>
        )}
        {state.kind === 'open' && session?.authenticated && (
          <>
            <p>
              This link adds a new {state.view.role} to <b>{state.view.team}</b>. You are signed in
              as {session.username}; sign out to make the new account.
            </p>
            <button type="button" className="primary" onClick={() => void signOut()}>Sign out</button>
            <button type="button" onClick={onDone}>Keep my account</button>
          </>
        )}
        {state.kind === 'open' && !session?.authenticated && (
          <>
            <p>
              You are invited to <b>{state.view.team}</b> as {state.view.role === 'ADMIN' ? 'an' : 'a'}{' '}
              {state.view.role}. The link works until {shortDate(localDay(state.view.expiresAt))}.
            </p>
            <label>
              Username
              <input
                value={username}
                onChange={(e) => setUsername(e.target.value.toLowerCase())}
                autoComplete="username"
                autoFocus
                required
              />
            </label>
            <label>
              Name to show
              <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} autoComplete="name" required />
            </label>
            <label>
              Password
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="new-password"
                required
              />
            </label>
            <label>
              Password again
              <input
                type="password"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
                autoComplete="new-password"
                required
              />
            </label>
            <p className="note">A username is lower case. A password is at least 10 characters.</p>
            {error && <p className="error" role="alert">{error}</p>}
            <button type="submit" className="primary" disabled={busy}>{busy ? 'Joining…' : 'Join and sign in'}</button>
            <button type="button" onClick={onDone} disabled={busy}>I already have an account</button>
          </>
        )}
      </form>
    </main>
  )
}
