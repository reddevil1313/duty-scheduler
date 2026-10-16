import { useEffect, useState } from 'react'
import { ApiError, api } from '../api/client'
import type { Post, Schedule, SheetCheck, Trooper } from '../api/types'
import { useReport } from '../api/useReport'
import { useSession } from '../auth/session-context'
import { RosterPanel } from '../roster/RosterPanel'
import { setCell, swapRows } from './edit'
import { SheetGrid, type Mode } from './SheetGrid'

type Source = 'logged' | 'generated'

interface Props {
  /** Held by the app, so the records can open a day here. */
  date: string
  onDateChange: (date: string) => void
}

export function ScheduleBoard({ date, onDateChange }: Props) {
  const { session } = useSession()
  const isAdmin = session?.role === 'ADMIN'

  const [roster, setRoster] = useState<Trooper[]>([])
  const [nightGroup, setNightGroup] = useState<string[]>([])
  // The sheet as it came — generated or logged — and the sheet as it now is.
  // They are the same object until someone edits, which is all "edited" means.
  const [original, setOriginal] = useState<Schedule | null>(null)
  const [schedule, setSchedule] = useState<Schedule | null>(null)
  const [source, setSource] = useState<Source | null>(null)
  const [mode, setMode] = useState<Mode>('view')
  const [swapPick, setSwapPick] = useState<string | null>(null)
  // The check and the exact sheet it was made for. A check of an older sheet
  // is never shown against a newer one.
  const [check, setCheck] = useState<{ of: Schedule; result: SheetCheck } | null>(null)
  const [note, setNote] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const report = useReport(setError)

  const edited = schedule !== original
  const current = check && check.of === schedule ? check.result : null

  /** A fresh sheet on the board: nothing edited, nothing picked. */
  const show = (sheet: Schedule | null, from: Source | null) => {
    setOriginal(sheet)
    setSchedule(sheet)
    setSource(from)
    setMode('view')
    setSwapPick(null)
  }

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
        show(found, 'logged')
        setError(null)
        setNote(null)
      })
      .catch((e: unknown) => {
        if (cancelled) return
        if (e instanceof ApiError && e.status === 404) {
          // Not an error. Most dates have no sheet yet, and a red banner for the
          // normal case trains people to ignore red banners.
          show(null, null)
          setError(null)
          setNote('Nothing logged for this date yet.')
          return
        }
        void report(e)
      })
    return () => { cancelled = true }
  }, [date, report])

  // Every change to the sheet is checked by the server's validator, a moment
  // after the last edit. The same oracle that proves the solver right judges a
  // person's edits; there is no copy of the rules in the browser.
  useEffect(() => {
    if (!schedule) return
    let stale = false
    const timer = setTimeout(() => {
      api.check(date, schedule)
        .then((result) => { if (!stale) setCheck({ of: schedule, result }) })
        .catch((e: unknown) => { if (!stale) void report(e) })
    }, 200)
    return () => {
      stale = true
      clearTimeout(timer)
    }
  }, [date, schedule, report])

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
      show(await api.generate(date, nightGroup), 'generated')
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
      const issues = current?.issues.length ?? 0
      show(await api.log(date, schedule), 'logged')
      setNote(issues > 0 ? `Logged, with ${issues} issue${issues === 1 ? '' : 's'} on record.` : 'Logged.')
    } catch (e) {
      await report(e)
    } finally {
      setBusy(false)
    }
  }

  const toggleMode = (next: Mode) => {
    setMode((m) => (m === next ? 'view' : next))
    setSwapPick(null)
  }

  const setCellTo = (trooper: string, stayOut: boolean, slot: number, post: Post | null) => {
    setSchedule((s) => (s ? setCell(s, trooper, stayOut, slot, post) : s))
  }

  const pickRow = (trooper: string) => {
    if (swapPick === null) { setSwapPick(trooper); return }
    if (swapPick === trooper) { setSwapPick(null); return }
    setSchedule((s) => (s ? swapRows(s, swapPick, trooper) : s))
    setNote(`Swapped ${swapPick} and ${trooper}.`)
    setSwapPick(null)
  }

  const undo = () => {
    setSchedule(original)
    setMode('view')
    setSwapPick(null)
    setNote(null)
  }

  // Roster Troopers with no row, offered as empty rows while editing a sheet
  // that left them off.
  const spare = schedule
    ? roster.filter((t) => !schedule.rows.some((r) => r.trooper === t.name))
      .map((t) => ({ name: t.name, stayOut: t.stayOut }))
    : []

  const issues = current?.issues ?? []
  const canLog = !!schedule && (source === 'generated' || edited)
  const logLabel = !canLog ? 'Logged'
    : issues.length > 0 ? `Log anyway (${issues.length} issue${issues.length === 1 ? '' : 's'})`
      : source === 'logged' ? 'Log changes' : 'Log this sheet'

  return (
    <div className="board">
      <div className="controls">
        <label>
          Duty date
          <input type="date" value={date} onChange={(e) => onDateChange(e.target.value)} />
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
              className={issues.length > 0 && canLog ? 'danger' : undefined}
              onClick={log}
              // Not while a check is on its way: logging a sheet nobody has
              // looked at yet would skip the warning this button exists to give.
              disabled={busy || !canLog || current === null}
            >
              {logLabel}
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
          <div className="sheet-bar">
            <p className="status">
              {source === 'generated' ? 'Generated, not yet logged.' : 'The logged sheet for this date.'}
              {edited && ' Edited by hand.'}
              {' '}
              {current?.sheet.totalHours ?? schedule.totalHours} man-hours over {schedule.rows.length} men.
            </p>
            {isAdmin && (
              <div className="actions">
                <button type="button" aria-pressed={mode === 'edit'} onClick={() => toggleMode('edit')}>
                  {mode === 'edit' ? 'Done editing' : 'Edit'}
                </button>
                <button type="button" aria-pressed={mode === 'swap'} onClick={() => toggleMode('swap')}>
                  {mode !== 'swap' ? 'Swap two Troopers' : swapPick ? 'Pick the second' : 'Pick the first'}
                </button>
                {edited && <button type="button" onClick={undo}>Undo my edits</button>}
              </div>
            )}
          </div>
          {mode === 'edit' && <p className="note">Click any hour to change its post.</p>}
          {mode === 'swap' && <p className="note">Click two names to trade their whole day.</p>}

          <SheetGrid
            sheet={schedule}
            check={current}
            mode={mode}
            spare={spare}
            swapPick={swapPick}
            onSetCell={setCellTo}
            onPickRow={pickRow}
          />

          {issues.length > 0 && (
            <ul className="issues" aria-label="What this sheet breaks">
              {issues.map((issue, i) => (
                // Issues have no id, and the list is rebuilt whole on every
                // check, so position is a stable enough key here.
                <li key={i} className={issue.rule === 'ON_LEAVE' ? 'leave' : 'breaks'}>
                  <b>{issue.rule === 'ON_LEAVE' ? 'LEAVE' : 'BREAKS'}</b>
                  {issue.message}
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </div>
  )
}
