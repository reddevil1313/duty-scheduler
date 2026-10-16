import { useState } from 'react'
import { useReport } from './api/useReport'
import { ScheduleBoard } from './board/ScheduleBoard'
import { LeaveRegister } from './leave/LeaveRegister'
import { RecordsView } from './records/RecordsView'
import { RosterView } from './roster/RosterView'
import { useRoster } from './roster/useRoster'
import { LoginPage } from './LoginPage'
import { today } from './dates'
import { SessionProvider } from './auth/SessionProvider'
import { useSession } from './auth/session-context'

type View = 'board' | 'roster' | 'leave' | 'records'

const VIEWS: [View, string][] = [
  ['board', 'Board'],
  ['roster', 'Roster'],
  ['leave', 'Leave register'],
  ['records', 'Records'],
]

function Shell() {
  const { session } = useSession()

  // Null means the first session read has not come back. Rendering the sign-in
  // form in the meantime would flash it at someone who is already signed in.
  if (!session) {
    return <main className="loading"><p>Loading…</p></main>
  }
  if (!session.authenticated) {
    return <LoginPage />
  }
  // Keyed on the user, so everything below starts fresh for each sign-in:
  // roster ticks in particular are this user's scratch state and must not
  // survive into the next person's session.
  return <SignedIn key={session.username} />
}

function SignedIn() {
  const { session, signOut } = useSession()
  const [view, setView] = useState<View>('board')
  const [date, setDate] = useState(today)
  // Roster load failures are rare and almost always an expired session, which
  // the report hook turns into a sign-in prompt; anything else is shown here.
  const [rosterError, setRosterError] = useState<string | null>(null)
  const rosterState = useRoster(useReport(setRosterError))

  return (
    <>
      <header>
        <h1>Duty Scheduler</h1>
        <nav className="views">
          {VIEWS.map(([v, label]) => (
            <button key={v} type="button" aria-current={view === v ? 'page' : undefined} onClick={() => setView(v)}>
              {label}
            </button>
          ))}
        </nav>
        <div className="who">
          <span>{session?.displayName ?? session?.username}</span>
          <span className="tag">{session?.role}</span>
          <button type="button" onClick={() => void signOut()}>Sign out</button>
        </div>
      </header>
      {rosterError && <p className="error banner" role="alert">{rosterError}</p>}
      {/* All stay mounted and are hidden rather than swapped, so a generated
          sheet that has not been logged yet survives a look at another tab. */}
      <main hidden={view !== 'board'}>
        <ScheduleBoard date={date} onDateChange={setDate} rosterState={rosterState} />
      </main>
      <main hidden={view !== 'roster'}>
        <RosterView rosterState={rosterState} />
      </main>
      <main hidden={view !== 'leave'}>
        <LeaveRegister active={view === 'leave'} ticks={rosterState.ticks} />
      </main>
      <main hidden={view !== 'records'}>
        <RecordsView
          active={view === 'records'}
          onOpen={(day) => {
            setDate(day)
            setView('board')
          }}
        />
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
