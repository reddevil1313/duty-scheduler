import { useEffect, useState } from 'react'
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
import { useTheme } from './theme'
import { AcceptInvitePage } from './team/AcceptInvitePage'
import { OwnerConsole } from './team/OwnerConsole'
import { TeamView } from './team/TeamView'
import { clearInvite, inviteToken } from './team/invite'
import { HelpView } from './help/HelpView'

type View = 'board' | 'roster' | 'leave' | 'records' | 'team' | 'help'

const VIEWS: [View, string][] = [
  ['board', 'Board'],
  ['roster', 'Roster'],
  ['leave', 'Leave register'],
  ['records', 'Records'],
]

type ThemeState = ReturnType<typeof useTheme>

function Shell() {
  const { session } = useSession()
  const theme = useTheme()
  // Invite token from the URL fragment, also when pasted into an open tab.
  const [invite, setInvite] = useState(inviteToken)
  useEffect(() => {
    const follow = () => setInvite(inviteToken())
    window.addEventListener('hashchange', follow)
    return () => window.removeEventListener('hashchange', follow)
  }, [])

  // null until the first session read, so the sign-in form doesn't flash.
  if (!session) {
    return <main className="loading"><p>Loading…</p></main>
  }
  if (invite) {
    return <AcceptInvitePage token={invite} onDone={() => { clearInvite(); setInvite(null) }} />
  }
  if (!session.authenticated) {
    return <LoginPage />
  }
  if (session.role === 'OWNER') {
    return <Owner theme={theme} />
  }
  // Keyed on the user so roster ticks reset on every sign-in.
  return <SignedIn key={session.username} theme={theme} />
}

/** The team name is the heading and the tab title. */
function Masthead({ title }: { title: string }) {
  useEffect(() => {
    document.title = `${title} · Duty Scheduler`
    return () => { document.title = 'Duty Scheduler' }
  }, [title])
  return (
    <header className="mast">
      <div>
        <div className="rule" />
        <p className="app">Duty Scheduler</p>
        <h1>{title}</h1>
        <p className="sub">A sheet runs 2000 to 1959 the next morning · silent hours 2200–0600</p>
      </div>
    </header>
  )
}

/** Who is signed in, to which team, and the theme and sign-out buttons. */
function Who({ theme: { theme, cycle } }: { theme: ThemeState }) {
  const { session, signOut } = useSession()
  return (
    <div className="who">
      <span>{session?.displayName ?? session?.username}</span>
      <span className={`role${session?.role === 'ADMIN' ? ' admin' : session?.role === 'OWNER' ? ' owner' : ''}`}>
        {session?.role}
      </span>
      <button type="button" className="sm" onClick={cycle} title="Follow the system, or keep light or dark">
        Theme: {theme}
      </button>
      <button type="button" className="sm" onClick={() => void signOut()}>Sign out</button>
    </div>
  )
}

/** The owner has no team tabs. */
function Owner({ theme }: { theme: ThemeState }) {
  const [view, setView] = useState<'teams' | 'help'>('teams')
  return (
    <div className="wrap">
      <Masthead title="All teams" />
      <nav className="viewbar">
        {([['teams', 'Teams'], ['help', 'Help']] as const).map(([v, label]) => (
          <button
            key={v}
            type="button"
            className="vtab"
            aria-current={view === v ? 'page' : undefined}
            onClick={() => setView(v)}
          >
            {label}
          </button>
        ))}
        <Who theme={theme} />
      </nav>
      <main hidden={view !== 'teams'}>
        <OwnerConsole />
      </main>
      <main hidden={view !== 'help'}>
        <HelpView role="OWNER" />
      </main>
    </div>
  )
}

function SignedIn({ theme }: { theme: ThemeState }) {
  const { session } = useSession()
  const isAdmin = session?.role === 'ADMIN'
  const [view, setView] = useState<View>('board')
  const [date, setDate] = useState(today)
  const [rosterError, setRosterError] = useState<string | null>(null)
  const rosterState = useRoster(useReport(setRosterError))

  return (
    <div className="wrap">
      <Masthead title={session?.team ?? 'Duty Scheduler'} />
      <nav className="viewbar">
        {[...VIEWS, ...(isAdmin ? [['team', 'Team'] as [View, string]] : []), ['help', 'Help'] as [View, string]]
          .map(([v, label]) => (
          <button
            key={v}
            type="button"
            className="vtab"
            aria-current={view === v ? 'page' : undefined}
            onClick={() => setView(v)}
          >
            {label}
          </button>
        ))}
        <Who theme={theme} />
      </nav>
      {rosterError && <p className="error banner" role="alert">{rosterError}</p>}
      {/* Hidden, not unmounted, so an unlogged sheet survives switching tabs. */}
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
      {isAdmin && (
        <main hidden={view !== 'team'}>
          <TeamView active={view === 'team'} />
        </main>
      )}
      <main hidden={view !== 'help'}>
        <HelpView role={session?.role ?? 'USER'} />
      </main>
    </div>
  )
}

export default function App() {
  return (
    <SessionProvider>
      <Shell />
    </SessionProvider>
  )
}
