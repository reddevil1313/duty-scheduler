import { ScheduleBoard } from './board/ScheduleBoard'
import { LoginPage } from './LoginPage'
import { SessionProvider } from './auth/SessionProvider'
import { useSession } from './auth/session-context'

function Shell() {
  const { session, signOut } = useSession()

  // Null means the first session read has not come back. Rendering the sign-in
  // form in the meantime would flash it at someone who is already signed in.
  if (!session) {
    return <main className="loading"><p>Loading…</p></main>
  }
  if (!session.authenticated) {
    return <LoginPage />
  }

  return (
    <>
      <header>
        <h1>Duty Scheduler</h1>
        <div className="who">
          <span>{session.displayName ?? session.username}</span>
          <span className="tag">{session.role}</span>
          <button type="button" onClick={() => void signOut()}>Sign out</button>
        </div>
      </header>
      <main>
        <ScheduleBoard />
      </main>
    </>
  )
}

export default function App() {
  return (
    <SessionProvider>
      <Shell />
    </SessionProvider>
  )
}
