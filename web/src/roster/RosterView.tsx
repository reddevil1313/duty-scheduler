import { useState } from 'react'
import type { Trooper } from '../api/types'
import { useReport } from '../api/useReport'
import { useSession } from '../auth/session-context'
import { RosterPanel } from './RosterPanel'
import type { RosterState } from './useRoster'

interface Props {
  rosterState: RosterState
}

/** The Roster tab: who is on the team, and who is ticked out of the next sheet. */
export function RosterView({ rosterState }: Props) {
  const { session } = useSession()
  const isAdmin = session?.role === 'ADMIN'
  const { roster, ticks, tick, added, updated, removed } = rosterState
  const [error, setError] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)
  const report = useReport(setError)

  const said = (message: string) => {
    setError(null)
    setNote(message)
  }

  return (
    <section className="panel roster-view" aria-label="Roster">
      <div className="panel-hd">
        <h2>Roster</h2>
        <span className="meta">{roster.length} Trooper{roster.length === 1 ? '' : 's'}</span>
      </div>
      {isAdmin && (
        <p className="note">
          Tick MC, OL or AL to keep someone out of the sheets you generate. A tick is not a leave booking:
          it is kept only in this browser and goes when you sign out. To record leave, use the leave register.
        </p>
      )}
      <p className={`message${error ? ' error' : ' note'}`} role={error ? 'alert' : 'status'}>
        {error ?? note ?? ''}
      </p>
      <RosterPanel
        roster={roster}
        canEdit={isAdmin}
        ticks={ticks}
        onTick={tick}
        onAdded={(t: Trooper) => { added(t); said(`${t.name} added to the roster.`) }}
        onUpdated={(before: Trooper, after: Trooper) => { updated(before, after); said(`${after.name} updated.`) }}
        onRemoved={(t: Trooper) => { removed(t); said(`${t.name} removed from the roster.`) }}
        onError={report}
      />
    </section>
  )
}
