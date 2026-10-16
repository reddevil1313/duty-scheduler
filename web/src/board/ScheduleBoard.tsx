import { useCallback, useEffect, useState } from 'react'
import { ApiError, api } from '../api/client'
import type { Schedule, Trooper } from '../api/types'
import { useSession } from '../auth/session-context'
import { RosterPanel } from '../roster/RosterPanel'
import { SLOT_COUNT, isCrossover, isSilent, labelAt } from './slots'

type Source = 'logged' | 'generated'

function today(): string {
  // Local, not toISOString(). A sheet is named by the evening it starts, and at
  // 2100 in Singapore toISOString() already says tomorrow.
  const now = new Date()
  const offset = now.getTimezoneOffset() * 60_000
  return new Date(now.getTime() - offset).toISOString().slice(0, 10)
}

export function ScheduleBoard() {
  const { session, recheck } = useSession()
  const isAdmin = session?.role === 'ADMIN'

  const [date, setDate] = useState(today)
  const [roster, setRoster] = useState<Trooper[]>([])
  const [nightGroup, setNightGroup] = useState<string[]>([])
  const [schedule, setSchedule] = useState<Schedule | null>(null)
  const [source, setSource] = useState<Source | null>(null)
  const [note, setNote] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  /**
   * One place where a failed call becomes something on screen.
   *
   * <p>The 401 branch is the reason this exists rather than a try/catch at each
   * call site. A session that has quietly expired makes every button fail, and
   * without the recheck the app would keep drawing a signed-in shell over an API
   * that refuses everything.
   */
  const report = useCallback(async (e: unknown) => {
    if (e instanceof ApiError && e.status === 401) {
      await recheck()
      return
    }
    setError(e instanceof Error ? e.message : 'Something went wrong.')
  }, [recheck])

  useEffect(() => {
    let cancelled = false
    api.troopers()
      .then((list) => {
        if (cancelled) return
        setRoster(list)
        setNightGroup(list.filter((t) => t.onNight).map((t) => t.name))
      })
      .catch((e: unknown) => { void report(e) })
    return () => { cancelled = true }
  }, [report])

  // Clearing the banners happens in the branches below rather than at the top of
  // the effect. Setting state synchronously as an effect opens schedules a second
  // render before the browser has painted the first; doing it in the handlers
  // costs nothing and keeps each outcome to one update.
  useEffect(() => {
    let cancelled = false
    api.schedule(date)
      .then((found) => {
        if (cancelled) return
        setSchedule(found)
        setSource('logged')
        setError(null)
        setNote(null)
      })
      .catch((e: unknown) => {
        if (cancelled) return
        if (e instanceof ApiError && e.status === 404) {
          // Not an error. Most dates have no sheet yet, and a red banner for the
          // normal case trains people to ignore red banners.
          setSchedule(null)
          setSource(null)
          setError(null)
          setNote('Nothing logged for this date yet.')
          return
        }
        void report(e)
      })
    return () => { cancelled = true }
  }, [date, report])

  const trooperAdded = (trooper: Trooper) => {
    setRoster((current) => [...current, trooper])
    if (trooper.onNight) {
      setNightGroup((current) => [...current, trooper.name])
    }
    setError(null)
    setNote(`${trooper.name} added to the roster.`)
  }

  const trooperUpdated = (before: Trooper, after: Trooper) => {
    setRoster((current) => current.map((t) => (t.id === after.id ? after : t)))
    // The night group is ticked by name, so a rename has to carry the tick
    // across, and someone who has become a stay-out can no longer hold it.
    setNightGroup((current) => {
      const without = current.filter((n) => n !== before.name)
      const wasTicked = without.length !== current.length
      return wasTicked && !after.stayOut ? [...without, after.name] : without
    })
    setError(null)
    setNote(`${after.name} updated.`)
  }

  const trooperRemoved = (trooper: Trooper) => {
    setRoster((current) => current.filter((t) => t.id !== trooper.id))
    setNightGroup((current) => current.filter((n) => n !== trooper.name))
    setError(null)
    setNote(`${trooper.name} removed from the roster.`)
  }

  const toggleNight = (name: string) => {
    setNightGroup((current) =>
      current.includes(name) ? current.filter((n) => n !== name) : [...current, name])
  }

  const generate = async () => {
    setBusy(true)
    setError(null)
    setNote(null)
    try {
      setSchedule(await api.generate(date, nightGroup))
      setSource('generated')
    } catch (e) {
      await report(e)
    } finally {
      setBusy(false)
    }
  }

  const log = async () => {
    if (!schedule) return
    setBusy(true)
    setError(null)
    try {
      setSchedule(await api.log(date, schedule))
      setSource('logged')
      setNote('Logged.')
    } catch (e) {
      await report(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="board">
      <div className="controls">
        <label>
          Duty date
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        </label>

        {isAdmin && (
          <fieldset className="night">
            <legend>Night group</legend>
            {roster.filter((t) => !t.stayOut).map((t) => (
              <label key={t.name} className="pill">
                <input
                  type="checkbox"
                  checked={nightGroup.includes(t.name)}
                  onChange={() => toggleNight(t.name)}
                />
                {t.name}
              </label>
            ))}
          </fieldset>
        )}

        {isAdmin && (
          <div className="actions">
            <button type="button" onClick={generate} disabled={busy || nightGroup.length === 0}>
              {busy ? 'Working…' : 'Generate'}
            </button>
            <button
              type="button"
              onClick={log}
              disabled={busy || !schedule || source === 'logged'}
            >
              Log this sheet
            </button>
          </div>
        )}
      </div>

      {isAdmin && (
        <RosterPanel
          roster={roster}
          onAdded={trooperAdded}
          onUpdated={trooperUpdated}
          onRemoved={trooperRemoved}
          onError={report}
        />
      )}

      {error && <p className="error" role="alert">{error}</p>}
      {note && !error && <p className="note">{note}</p>}

      {schedule && (
        <>
          <p className="status">
            {source === 'generated'
              ? 'Generated, not yet logged.'
              : 'The logged sheet for this date.'}
            {' '}
            {schedule.totalHours} man-hours over {schedule.rows.length} men.
          </p>

          <div className="grid" role="table" aria-label={`Duty sheet for ${schedule.date}`}>
            <div className="row head" role="row">
              <div className="name" role="columnheader">Trooper</div>
              {Array.from({ length: SLOT_COUNT }, (_, slot) => (
                <div
                  key={slot}
                  role="columnheader"
                  className={isSilent(slot) ? 'hour silent' : isCrossover(slot) ? 'hour cross' : 'hour'}
                >
                  {labelAt(slot).slice(0, 2)}
                </div>
              ))}
              <div className="tally" role="columnheader">Hrs</div>
              <div className="tally" role="columnheader">Span</div>
            </div>

            {schedule.rows.map((row) => (
              <div className="row" role="row" key={row.trooper}>
                <div className="name" role="rowheader">
                  {row.trooper}
                  {row.stayOut && <span className="tag">SO</span>}
                </div>
                {row.posts.map((post, slot) => (
                  <div
                    // The index is the slot, and a slot is a fixed position on
                    // the board — it is not a list that reorders, so it is a
                    // legitimate key rather than the usual index-as-key mistake.
                    key={slot}
                    role="cell"
                    className={
                      `cell${post ? ` on ${post.toLowerCase()}` : ''}` +
                      `${isSilent(slot) ? ' silent' : ''}`
                    }
                    title={`${labelAt(slot)} — ${post ?? 'off'}`}
                  >
                    {post ?? ''}
                  </div>
                ))}
                <div className="tally" role="cell">{row.hours}</div>
                <div className="tally" role="cell">{row.span}</div>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  )
}
