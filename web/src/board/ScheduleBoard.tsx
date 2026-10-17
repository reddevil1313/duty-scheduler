import { useEffect, useState } from 'react'
import { ApiError, api } from '../api/client'
import type { Post, Schedule, SheetCheck } from '../api/types'
import { useReport } from '../api/useReport'
import { useSession } from '../auth/session-context'
import { shortDate } from '../dates'
import { download } from '../download'
import type { RosterState } from '../roster/useRoster'
import { availableFirst, mpShortfall, setCell, swapRows } from './edit'
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
  // Who takes the BUS run, chosen like the night group; null lets the solver
  // choose, and undefined means nobody has picked yet, so the roster's default
  // BUS Trooper stands. Read through busPick, so a Trooper since removed,
  // renamed or ticked out falls back to the solver's choice instead of a
  // request that fails.
  const [bus, setBus] = useState<string | null | undefined>(undefined)
  const defaultBus = roster.find((t) => t.defaultBus)?.name ?? null
  const wanted = bus === undefined ? defaultBus : bus
  const busPick = wanted !== null && roster.some((t) => t.name === wanted) && !(wanted in ticks) ? wanted : null
  // The turnout to generate with (null for the free split), and the one the
  // sheet on the board was made with — generated now, or logged with it. The
  // MP hours themselves are never stored; the turnout is, and it is what tells
  // an MP hour from a gap.
  const [turnout, setTurnout] = useState<number | null>(null)
  const [sheetTurnout, setSheetTurnout] = useState<number | null>(null)
  const report = useReport(setError)

  const edited = schedule !== original
  const current = check && check.of === schedule ? check.result : null
  // The most recent check for this date, even if of an earlier edit: what the
  // grid shows while a newer one is on its way, so nothing blanks and reflows.
  const latest = check && schedule && check.of.date === schedule.date ? check.result : null

  /**
   * The sheet as it goes to the server: rows in the order the board shows
   * them, so the logged sheet and its image list them the same way.
   */
  const asShown = (sheet: Schedule): Schedule =>
    ({ ...sheet, rows: availableFirst(sheet.rows, latest?.away), turnout: sheetTurnout })

  /** A fresh sheet on the board: nothing edited, nothing picked. */
  const show = (sheet: Schedule | null, from: Source | null, withTurnout: number | null = null) => {
    setOriginal(sheet)
    setSchedule(sheet)
    setSource(from)
    setSheetTurnout(withTurnout)
    setMode('view')
    setSwapPick(null)
  }

  // Clearing the banners happens in the branches below rather than at the top of
  // the effect. Setting state synchronously as an effect opens schedules a second
  // render before the browser has painted the first; doing it in the handlers
  // costs nothing and keeps each outcome to one update.
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
      // Ticks go too, so someone ticked out shows greyed and is flagged if
      // posted — exactly as they would be with a booked MC.
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
      // Someone ticked out stays out of the night group sent; the server drops
      // them too, but sending it right keeps the request saying what it means.
      const sheet = await api.generate(date, {
        nightGroup: nightGroup.filter((n) => !(n in ticks)),
        ticks,
        bus: busPick,
        busDefault: bus === undefined && busPick !== null,
        turnout,
      })
      show(sheet, 'generated', turnout)
      // The default gave way when the sheet came back with BUS on someone else.
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
      // The turnout goes with the sheet, so the logged sheet keeps its MP row
      // and can be reused for another day made the same way.
      const saved = await api.log(date, asShown(schedule))
      show(saved, 'logged', saved.turnout ?? null)
      setNote(count > 0 ? `Logged, with ${count} issue${count === 1 ? '' : 's'} on record.` : 'Logged.')
    } catch (e) {
      await report(e)
    } finally {
      setBusy(false)
    }
  }

  /**
   * Log the sheet and save its image, in one request: the server draws, then
   * logs, then sends, so the image saved is always the sheet recorded.
   */
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

  /** The image of the sheet as logged, for sending it on again. */
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

  // Roster Troopers with no row, offered as empty rows while editing a sheet
  // that left them off.
  const spare = schedule
    ? roster.filter((t) => !schedule.rows.some((r) => r.trooper === t.name))
      .map((t) => ({ name: t.name, stayOut: t.stayOut }))
    : []

  // MP hours on a fixed-turnout sheet: the posts nobody from the team is on.
  const mpHours = sheetTurnout !== null && latest && schedule
    ? mpShortfall(schedule, latest.demand).reduce((n, posts) => n + posts.length, 0)
    : 0
  // The posts this date stands, from the server's demand rather than a copy of
  // the weekday rules, for the tag in the sheet's header.
  const postsStanding = latest
    ? (['AP', 'GG', 'PAC', 'BUS'] as Post[]).filter((p) => latest.demand.some((d) => (d[p] ?? 0) > 0))
    : []
  // Who holds the silent hours on the sheet as it stands, edits included.
  const nightOn = schedule
    ? schedule.rows.filter((r) => r.posts.some((p, slot) => p !== null && isSilent(slot))).map((r) => r.trooper)
    : []
  const manned = (slot: number) => !!schedule?.rows.some((r) => r.posts[slot] !== null)

  // From the latest check, dimmed while a newer one is on its way, so the list
  // does not vanish and reappear around every edit. On a fixed-turnout sheet an
  // under-manned hour is the MP team's, and listed in their row instead — but
  // only where the rules let it be: never a silent hour, never an hour with
  // nobody from the team on. Those still show as broken, because they are.
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
              // Ticked out on the roster: shown, so the gap is explained, but
              // not tickable for the night while the tick stands.
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
            {/* Stay-outs are not in until 0800, so they are never offered;
                the server refuses them regardless. */}
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
              // Not while a check is on its way: logging a sheet nobody has
              // looked at yet would skip the warning this button exists to give.
              disabled={busy || !canLog || current === null}
            >
              {logLabel}
            </button>
            {/* One button for both, so nothing shifts: a sheet still to log is
                logged by saving its image; a logged one just saves it again. */}
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

      {/* Always rendered, one line tall, so a message arriving or clearing
          never moves the board below it. */}
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
              {current?.sheet.totalHours ?? schedule.totalHours} hours over {schedule.rows.length} Troopers.
            </p>
            {!isAdmin && source === 'logged' && (
              <div className="actions">
                <button type="button" onClick={saveImage} disabled={busy}>Save image</button>
              </div>
            )}
            {isAdmin && (
              <div className="actions">
                {/* Fixed widths, and Undo kept in place but hidden, so the
                    buttons do not slide about as their labels change. */}
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
          {/* The hint line is always there, empty outside the modes, so
              entering one does not push the grid down a line. */}
          <p className="hint note">
            {mode === 'edit' ? 'Click any hour to change its post.'
              : mode === 'swap' ? 'Click two names to trade their whole day.' : ''}
          </p>

          <div className="scroller">
            <SheetGrid
              sheet={schedule}
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
                    // Issues have no id, and the list is rebuilt whole on every
                    // check, so position is a stable enough key here.
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
