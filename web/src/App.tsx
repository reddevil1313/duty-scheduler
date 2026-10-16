import { useState } from 'react'
import { ScheduleBoard } from './board/ScheduleBoard'
import { LeaveRegister } from './leave/LeaveRegister'
import { LoginPage } from './LoginPage'
import { SessionProvider } from './auth/SessionProvider'
import { useSession } from './auth/session-context'

type View = 'board' | 'leave'

function Shell() {
  const { session, signOut } = useSession()
  const [view, setView] = useState<View>('board')

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
        <nav className="views">
          <button type="button" aria-current={view === 'board' ? 'page' : undefined} onClick={() => setView('board')}>
            Board
          </button>
          <button type="button" aria-current={view === 'leave' ? 'page' : undefined} onClick={() => setView('leave')}>
            Leave register
          </button>
        </nav>
        <div className="who">
          <span>{session.displayName ?? session.username}</span>
          <span className="tag">{session.role}</span>
          <button type="button" onClick={() => void signOut()}>Sign out</button>
        </div>
      </header>
      {/* Both stay mounted and are hidden rather than swapped, so a generated
          sheet that has not been logged yet survives a look at the register. */}
      <main hidden={view !== 'board'}>
        <ScheduleBoard />
      </main>
      <main hidden={view !== 'leave'}>
        <LeaveRegister active={view === 'leave'} />
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
