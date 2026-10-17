import { useEffect, useState } from 'react'
import { ApiError, api } from '../api/client'
import type { Post, Schedule, SheetCheck } from '../api/types'
import { useReport } from '../api/useReport'
import { useSession } from '../auth/session-context'
import { shortDate } from '../dates'
import { download } from '../download'
import type { RosterState } from '../roster/useRoster'
import { boardOrder, mpShortfall, setCell, swapRows } from './edit'
import { SheetGrid, type Mode } from './SheetGrid'
import { isSilent } from './slots'

type Source = 'logged' | 'generated'

interface Props {
  /** Held by the app, so the records can open a day here. */
  date: string
  onDateChange: (date: string) => void
  /** Held by the app: the Roster tab edits and ticks, the board generates from it. */
  rosterState: RosterState
}

export function ScheduleBoard({ date, onDateChange, rosterState }: Props) {
  const { session } = useSession()
  const isAdmin = session?.role === 'ADMIN'
  const { roster, nightGroup, ticks, toggleNight } = rosterState
  // The sheet as loaded and as edited; the same object until the first edit.
  const [original, setOriginal] = useState<Schedule | null>(null)
  const [schedule, setSchedule] = useState<Schedule | null>(null)
  const [source, setSource] = useState<Source | null>(null)
  const [mode, setMode] = useState<Mode>('view')
  const [swapPick, setSwapPick] = useState<string | null>(null)
  // A check, paired with the sheet it was for.
  const [check, setCheck] = useState<{ of: Schedule; result: SheetCheck } | null>(null)
  const [note, setNote] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  // null = solver chooses, undefined = roster default. Read via busPick, which
  // falls back to null if that Trooper is gone or ticked out.
  const [bus, setBus] = useState<string | null | undefined>(undefined)
  const defaultBus = roster.find((t) => t.defaultBus)?.name ?? null
  const wanted = bus === undefined ? defaultBus : bus
  const busPick = wanted !== null && roster.some((t) => t.name === wanted) && !(wanted in ticks) ? wanted : null
  // Turnout to generate with, and the one the current sheet was made with.
  // MP hours are derived from the latter.
  const [turnout, setTurnout] = useState<number | null>(null)
  const [sheetTurnout, setSheetTurnout] = useState<number | null>(null)
  const report = useReport(setError)

  const edited = schedule !== original
  const current = check && check.of === schedule ? check.result : null
  // Latest check for this date, shown while a newer one loads.
  const latest = check && schedule && check.of.date === schedule.date ? check.result : null

  /** Re-sorted by what was actually stood, so the logged sheet reads in board order. */
  const asShown = (sheet: Schedule): Schedule =>
    ({ ...sheet, rows: boardOrder(sheet.rows, latest?.away), turnout: sheetTurnout })

  const show = (sheet: Schedule | null, from: Source | null, withTurnout: number | null = null) => {
    setOriginal(sheet)
    setSchedule(sheet)
    setSource(from)
    setSheetTurnout(withTurnout)
    setMode('view')
    setSwapPick(null)
  }

  useEffect(() => {
    let cancelled = false
    api.schedule(date)
      .then((found) => {
        if (cancelled) return
        show(found, 'logged', found.turnout ?? null)
        setError(null)
        setNote(null)
      })
      .catch((e: unknown) => {
        if (cancelled) return
        if (e instanceof ApiError && e.status === 404) {
          // Normal, so not shown as an error.
          show(null, null)
          setError(null)
          setNote('Nothing logged for this date yet.')
          return
        }
        void report(e)
      })
    return () => { cancelled = true }
  }, [date, report])

  // Check with the server shortly after each edit.
  useEffect(() => {
    if (!schedule) return
    let stale = false
    const timer = setTimeout(() => {

      api.check(date, schedule, ticks)
        .then((result) => { if (!stale) setCheck({ of: schedule, result }) })
        .catch((e: unknown) => { if (!stale) void report(e) })
    }, 200)
    return () => {
      stale = true
      clearTimeout(timer)
    }
  }, [date, schedule, ticks, report])

  const generate = async () => {
    setBusy(true)
    setError(null)
    setNote(null)
    try {

      const sheet = await api.generate(date, {
        nightGroup: nightGroup.filter((n) => !(n in ticks)),
        ticks,
        bus: busPick,
        busDefault: bus === undefined && busPick !== null,
        turnout,
      })
      show(sheet, 'generated', turnout)
      // The default BUS gave way.
      const gaveWay = bus === undefined && busPick !== null && sheet.rows.some((r) =>
        r.trooper !== busPick && r.posts.includes('BUS'))
      const notes = [
        gaveWay && `${busPick}, the default BUS Trooper, could not take BUS on this sheet, so the solver chose.`,
        sheet.reusedFrom && `Reused the sheet logged for ${shortDate(sheet.reusedFrom)}: the same posts, headcount and turnout.`,
      ].filter(Boolean)
      if (notes.length > 0) setNote(notes.join(' '))
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
      const count = issues.length

      const saved = await api.log(date, asShown(schedule))
      show(saved, 'logged', saved.turnout ?? null)
      setNote(count > 0 ? `Logged, with ${count} issue${count === 1 ? '' : 's'} on record.` : 'Logged.')
    } catch (e) {
      await report(e)
    } finally {
      setBusy(false)
    }
  }

  const logAndImage = async () => {
    if (!schedule) return
    setBusy(true)
    setError(null)
    try {
      const image = await api.logAndImage(date, asShown(schedule))
      download(image, `duty-sheet-${date}.png`)
      const saved = await api.schedule(date)
      show(saved, 'logged', saved.turnout ?? null)
      setNote('Logged, and the image saved.')
    } catch (e) {
      await report(e)
    } finally {
      setBusy(false)
    }
  }

  const saveImage = async () => {
    setBusy(true)
    setError(null)
    try {
      download(await api.image(date), `duty-sheet-${date}.png`)
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

  // Roster Troopers missing from the sheet, offered as empty rows when editing.
  const spare = schedule
    ? roster.filter((t) => !schedule.rows.some((r) => r.trooper === t.name))
      .map((t) => ({ name: t.name, stayOut: t.stayOut }))
    : []

  const mpHours = sheetTurnout !== null && latest && schedule
    ? mpShortfall(schedule, latest.demand).reduce((n, posts) => n + posts.length, 0)
    : 0

  const postsStanding = latest
    ? (['AP', 'GG', 'PAC', 'BUS'] as Post[]).filter((p) => latest.demand.some((d) => (d[p] ?? 0) > 0))
    : []

  const nightOn = schedule
    ? schedule.rows.filter((r) => r.posts.some((p, slot) => p !== null && isSilent(slot))).map((r) => r.trooper)
    : []
  // Same count as SheetImage.subtitle.
  const available = schedule ? schedule.rows.filter((r) => r.posts.some((p) => p !== null)).length : 0
  const manned = (slot: number) => !!schedule?.rows.some((r) => r.posts[slot] !== null)

  // On a fixed-turnout sheet, hide UNDER_MANNED where the MP team may cover it
  // (not silent, someone still on).
  const issues = (latest?.issues ?? []).filter((issue) => !(
    sheetTurnout !== null && issue.rule === 'UNDER_MANNED' && issue.slot !== null
    && !isSilent(issue.slot) && manned(issue.slot)))
  const canLog = !!schedule && (source === 'generated' || edited)
  const logLabel = !canLog ? 'Logged'
    : issues.length > 0 ? `Log anyway (${issues.length} issue${issues.length === 1 ? '' : 's'})`
      : source === 'logged' ? 'Log changes' : 'Log this sheet'

  return (
    <div className="board">
      <section className="panel controls" aria-label="Sheet to work on">
        <label>
          Duty date
          <input type="date" value={date} onChange={(e) => onDateChange(e.target.value)} />
        </label>

        {isAdmin && (
          <fieldset className="night">
            <legend>Night group</legend>
            {roster.filter((t) => !t.stayOut).map((t) => (

              <label key={t.name} className={`pill${t.name in ticks ? ' ticked' : ''}`}>
                <input
                  type="checkbox"
                  checked={nightGroup.includes(t.name) && !(t.name in ticks)}
                  disabled={t.name in ticks}
                  onChange={() => toggleNight(t.name)}
                />
                {t.name}
                {t.name in ticks && <span className="tag">{ticks[t.name]}</span>}
              </label>
            ))}
          </fieldset>
        )}

        {isAdmin && (
          <fieldset className="bus">
            <legend>BUS 0700–1000</legend>
            <label className="pill">
              <input type="radio" name="bus" checked={busPick === null} onChange={() => setBus(null)} />
              Any
            </label>
            {roster.filter((t) => !t.stayOut).map((t) => (
              <label key={t.name} className={`pill${t.name in ticks ? ' ticked' : ''}`}>
                <input
                  type="radio"
                  name="bus"
                  checked={busPick === t.name}
                  disabled={t.name in ticks}
                  onChange={() => setBus(t.name)}
                />
                {t.name}
                {t.name === defaultBus && <span className="tag">default</span>}
              </label>
            ))}
          </fieldset>
        )}

        {isAdmin && (
          <label>
            Turnout
            <select
              value={turnout ?? ''}
              onChange={(e) => setTurnout(e.target.value === '' ? null : Number(e.target.value))}
            >
              <option value="">Free split</option>
              <option value="5">5h each, rest to MP</option>
              <option value="6">6h each, rest to MP</option>
              <option value="7">7h each, rest to MP</option>
            </select>
          </label>
        )}

        {isAdmin && (
          <div className="actions">
            <button
              type="button"
              className="primary"
              onClick={generate}
              disabled={busy || nightGroup.filter((n) => !(n in ticks)).length === 0}
            >
              {busy ? 'Working…' : 'Generate'}
            </button>
            <button
              type="button"
              className={`log${issues.length > 0 && canLog ? ' danger' : ''}`}
              onClick={log}
              // Wait for the check, so the warning isn't skipped.
              disabled={busy || !canLog || current === null}
            >
              {logLabel}
            </button>
            <button
              type="button"
              className="image"
              onClick={canLog ? logAndImage : saveImage}
              disabled={busy || !schedule || (canLog && current === null)}
            >
              {canLog ? 'Log and save image' : 'Save image'}
            </button>
          </div>
        )}
      </section>

      {isAdmin && (
        Object.keys(ticks).length > 0 && (
          <p className="ticks note">
            Ticked out on the Roster tab, for generating only — not booked:{' '}
            {Object.entries(ticks).map(([name, kind]) => `${name} (${kind})`).join(', ')}
          </p>
        )
      )}

      {/* Always rendered so the layout doesn't jump. */}
      <p className={`message${error ? ' error' : ' note'}`} role={error ? 'alert' : 'status'}>
        {error ?? note ?? ''}
      </p>

      {schedule && (
        <section className="panel sheet" aria-label="Sheet">
          <div className="panel-hd sheet-bar">
            <h2>Sheet · {shortDate(schedule.date)}</h2>
            {postsStanding.length > 0 && <span className="daytag">{postsStanding.join(' ')}</span>}
            <p className="status">
              {source === 'generated'
                ? `Generated${sheetTurnout ? ` at ${sheetTurnout}h each` : ''}, not yet logged.`
                : `The logged sheet for this date${sheetTurnout ? `, at ${sheetTurnout}h each` : ''}.`}
              {mpHours > 0 && ` ${mpHours} post-hour${mpHours === 1 ? '' : 's'} to the MP team.`}
              {edited && ' Edited by hand.'}
              {' '}
              {current?.sheet.totalHours ?? schedule.totalHours} hours over {available} Trooper{available === 1 ? '' : 's'}.
            </p>
            {!isAdmin && source === 'logged' && (
              <div className="actions">
                <button type="button" onClick={saveImage} disabled={busy}>Save image</button>
              </div>
            )}
            {isAdmin && (
              <div className="actions">
                <button type="button" className="mode" aria-pressed={mode === 'edit'} onClick={() => toggleMode('edit')}>
                  {mode === 'edit' ? 'Done editing' : 'Edit'}
                </button>
                <button type="button" className="mode wide" aria-pressed={mode === 'swap'} onClick={() => toggleMode('swap')}>
                  {mode !== 'swap' ? 'Swap two Troopers' : swapPick ? 'Pick the second' : 'Pick the first'}
                </button>
                <button
                  type="button"
                  className={edited ? undefined : 'placeholder'}
                  onClick={undo}
                  disabled={!edited}
                  aria-hidden={!edited}
                  tabIndex={edited ? undefined : -1}
                >
                  Undo my edits
                </button>
              </div>
            )}
          </div>
          <p className="hint note">
            {mode === 'edit' ? 'Click any hour to change its post.'
              : mode === 'swap' ? 'Click two names to trade their whole day.' : ''}
          </p>

          <div className="scroller">
            <SheetGrid
              sheet={schedule}
              orderBasis={original?.rows}
              check={latest}
              fresh={current !== null}
              mp={sheetTurnout !== null}
              mode={mode}
              spare={spare}
              swapPick={swapPick}
              onSetCell={setCellTo}
              onPickRow={pickRow}
            />
          </div>

          {(issues.length > 0 || mpHours > 0 || nightOn.length > 0) && (
            <div className="notes">
              {issues.length > 0 && (
                <ul className={`issues${current ? '' : ' stale'}`} aria-label="What this sheet breaks">
                  {issues.map((issue, i) => (
                    // No id; the list is rebuilt on every check.
                    <li key={i} className={issue.rule === 'ON_LEAVE' ? 'leave' : 'breaks'}>
                      <b>{issue.rule === 'ON_LEAVE' ? 'LEAVE' : 'BREAKS'}</b>
                      {issue.message}
                    </li>
                  ))}
                </ul>
              )}
              {mpHours > 0 && (
                <p className="note mp">
                  <b>MP</b>
                  {mpHours} post-hour{mpHours === 1 ? '' : 's'} to the MP team at {sheetTurnout}h each; their row is under the grid.
                </p>
              )}
              {nightOn.length > 0 && (
                <p className="note info">
                  <b>NIGHT</b>
                  {nightOn.join(', ')} on the silent hours.
                </p>
              )}
            </div>
          )}

          <div className="legend" aria-label="Key">
            <span className="lg"><span className="sw ap">AP</span><span className="sw gg">GG</span>every hour</span>
            <span className="lg"><span className="sw pac">PAC</span><span className="sw bus">BUS</span>weekday posts</span>
            {sheetTurnout !== null && <span className="lg"><span className="sw mp">MP</span>MP team</span>}
            <span className="lg"><span className="sw hatch" />away</span>
            <span className="lg"><span className="tag">SO</span>stay-out, in 0800–1800</span>
            <span className="lg"><span className="hour cross">06</span>night may extend into 0600–0859</span>
          </div>
        </section>
      )}
    </div>
  )
}
