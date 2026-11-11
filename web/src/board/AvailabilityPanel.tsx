import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { SheetCheck, Ticks, Trooper } from '../api/types'
import { useReport } from '../api/useReport'
import { labelAt } from './slots'

type Away = SheetCheck['away'][string]

interface Props {
  date: string
  roster: Trooper[]
  nightGroup: string[]
  ticks: Ticks
}

/** Runs of the same reason: "MA 0900–1100". The server decides the hours. */
function awayRuns(hours: Away): string[] {
  const runs: string[] = []
  for (let slot = 0; slot < hours.length; slot++) {
    const kind = hours[slot]
    if (kind === null || hours[slot - 1] === kind) continue
    let end = slot
    while (end + 1 < hours.length && hours[end + 1] === kind) end++
    runs.push(`${kind} ${labelAt(slot)}–${labelAt(end + 1)}`)
  }
  return runs
}

/** Who can stand on this sheet, and why the others can't. */
export function AvailabilityPanel({ date, roster, nightGroup, ticks }: Props) {
  const [away, setAway] = useState<SheetCheck['away'] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const report = useReport(setError)

  useEffect(() => {
    let cancelled = false
    api.away(date, ticks)
      .then((found) => { if (!cancelled) { setAway(found); setError(null) } })
      .catch((e: unknown) => { if (!cancelled) void report(e) })
    return () => { cancelled = true }
  }, [date, ticks, report])

  if (!away) return error ? <p className="message error" role="alert">{error}</p> : null

  const whole = (name: string) => away[name]?.every((k) => k !== null) ?? false
  const on = roster.filter((t) => !whole(t.name))
  const off = roster.filter((t) => whole(t.name))
  const groups: [string, Trooper[]][] = [
    ['Night', on.filter((t) => nightGroup.includes(t.name))],
    ['Day', on.filter((t) => !t.stayOut && !nightGroup.includes(t.name))],
    ['Stay-out', on.filter((t) => t.stayOut)],
  ]

  return (
    <details className="panel availability" open>
      <summary className="panel-hd">
        <h2>Available</h2>
        <span className="meta">{on.length} of {roster.length} on the roster</span>
      </summary>
      <div className="panel-body">
        {groups.filter(([, people]) => people.length > 0).map(([label, people]) => (
          <div key={label} className="avail-row">
            <span className="avail-label">{label}</span>
            {people.map((t) => (
              <span key={t.name} className="pill">
                {t.name}
                {t.stayOut && <span className="tag">0800–1800</span>}
                {away[t.name] && awayRuns(away[t.name]).map((run) => <span key={run} className="tag">{run}</span>)}
              </span>
            ))}
          </div>
        ))}
        {off.length > 0 && (
          <div className="avail-row">
            <span className="avail-label">Away</span>
            {off.map((t) => (
              <span key={t.name} className="pill ticked">
                {t.name}
                <span className="tag">{reason(away[t.name], t.name in ticks)}</span>
              </span>
            ))}
          </div>
        )}
      </div>
    </details>
  )
}

/** Whole-sheet reason: one kind, or each run when it changes partway. */
function reason(hours: Away, ticked: boolean): string {
  const kinds = new Set(hours)
  if (ticked) return `${hours[0]}, not booked`
  if (kinds.size > 1) return awayRuns(hours).join(', ')
  return hours[0] === 'SO' ? 'stay-out, weekend' : String(hours[0])
}
