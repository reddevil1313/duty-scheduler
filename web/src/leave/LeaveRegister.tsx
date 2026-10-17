import { useEffect, useState, type FormEvent } from 'react'
import { ApiError, api } from '../api/client'
import type { Absence, AbsenceKind, AbsenceReach, AbsenceSpan, Ticks, Trooper } from '../api/types'
import { useReport } from '../api/useReport'
import { useSession } from '../auth/session-context'
import { nowMinute, shortDate, shortInstant, today } from '../dates'
import { requestOf, type LeaveDraft } from './draft'
import { KIND_TABLES, bookedAs, byKind, daysLabel, daysOf, isOver, isRunning, type KindGroup } from './groups'

const KINDS: [AbsenceKind, string][] = [
  ['MC', 'MC'],
  ['OL', 'OL'],
  ['AL', 'AL'],
  ['MA', 'MA — appointment'],
]

// MA is always a time window, so it isn't offered here.
const SPANS: [AbsenceSpan, string][] = [
  ['FULL', 'Full days'],
  ['DAY_HALF', 'Half day — 0800 to 2000'],
  ['NIGHT_HALF', 'Half day — 2000 to 0800'],
]

type Preview = { reach: AbsenceReach } | { problem: string } | null

interface Props {
  /** The roster is re-read each time the tab is shown. */
  active: boolean
  /** Roster ticks, shown as "not booked". */
  ticks: Ticks
}

/** The leave register, a table per kind. Only an ADMIN can book or cancel. */
export function LeaveRegister({ active, ticks }: Props) {
  const { session } = useSession()
  const isAdmin = session?.role === 'ADMIN'

  const [roster, setRoster] = useState<Trooper[]>([])
  const [register, setRegister] = useState<Absence[]>([])
  const [error, setError] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)
  // One Trooper's leave across every table, or null for everyone.
  const [only, setOnly] = useState<string | null>(null)
  const report = useReport(setError)

  useEffect(() => {
    if (!active) return
    // Ignore a stale read that lands late.
    let cancelled = false
    Promise.all([api.troopers(), api.absences()])
      .then(([troopers, absences]) => {
        if (cancelled) return
        setRoster(troopers)
        setRegister(absences)
      })
      .catch((e: unknown) => { if (!cancelled) void report(e) })
    return () => { cancelled = true }
  }, [active, report])

  const booked = (absence: Absence) => {
    setRegister((current) => [absence, ...current])
    // Otherwise the new booking could be hidden by the filter.
    setOnly((current) => (current === null || current === absence.trooper ? current : null))
    setError(null)
    setNote(`${absence.trooper} booked ${absence.kind}.`)
  }

  const cancelled = (absence: Absence) => {
    setRegister((current) => current.filter((a) => a.id !== absence.id))
    setError(null)
    setNote(`${absence.trooper}'s ${absence.kind} cancelled.`)
  }

  // From the register, so archived Troopers can still be filtered on.
  const names = [...new Set(register.map((a) => a.trooper))].sort()
  const trooper = only !== null && names.includes(only) ? only : null

  const now = nowMinute()
  const groups = byKind(register, now, trooper)
  const current = groups.reduce((n, g) => n + g.current.length, 0)

  return (
    <section className="leave" aria-label="Leave register">
      <div className="panel">
        <div className="panel-hd">
          <h2>Leave register</h2>
          <span className="meta">
            {register.length === 0
              ? 'nothing booked'
              : `${current} current or to come · ${register.length - current} past`}
          </span>
          {names.length > 1 && (
            <label className="filter">
              Show
              <select value={trooper ?? ''} onChange={(e) => setOnly(e.target.value || null)}>
                <option value="">Everyone</option>
                {names.map((n) => <option key={n} value={n}>{n}</option>)}
              </select>
            </label>
          )}
        </div>

        {isAdmin ? (
          <BookingForm roster={roster} onBooked={booked} onError={report} />
        ) : (
          <p className="empty">You have view-only access. Ask an ADMIN to record leave.</p>
        )}

        {Object.keys(ticks).length > 0 && (
          <p className="ticks note">
            Ticked out on the Roster tab, for generating only — not booked, and not counted in the records:{' '}
            {Object.entries(ticks).map(([name, kind]) => `${name} (${kind})`).join(', ')}.
            {isAdmin && ' To make one stick, book it here.'}
          </p>
        )}

        {(error || note) && (
          <p className={`message${error ? ' error' : ' note'}`} role={error ? 'alert' : 'status'}>{error ?? note}</p>
        )}

        {register.length === 0 && (
          <p className="empty">
            Nothing booked yet. Anything recorded here is taken into account when that day's sheet is generated.
          </p>
        )}
      </div>

      {groups.map((group) => (
        <KindTable
          // Keyed on the filter so the past section closes when it changes.
          key={`${group.kind}:${trooper ?? ''}`}
          group={group}
          now={now}
          canCancel={isAdmin}
          onCancelled={cancelled}
          onError={report}
        />
      ))}
    </section>
  )
}

interface TableProps {
  group: KindGroup
  now: string
  canCancel: boolean
  onCancelled: (absence: Absence) => void
  onError: (e: unknown) => Promise<void>
}

/** One kind's entries: current and upcoming, with the past folded away. */
function KindTable({ group, now, canCancel, onCancelled, onError }: TableProps) {
  const [showPast, setShowPast] = useState(false)
  const { kind, current, past } = group
  const label = KIND_TABLES.find(([k]) => k === kind)?.[1] ?? kind

  const withDays = kind !== 'MA'
  const rows = showPast ? [...current, ...past] : current

  return (
    <div className="panel kind-table" aria-label={label}>
      <div className="panel-hd">
        <h2><span className={`kind ${kind}`}>{kind}</span> {label}</h2>
        <span className="meta">
          {current.length === 0 && past.length === 0
            ? 'none'
            : `${current.length} current or to come${past.length > 0 ? ` · ${past.length} past` : ''}`}
        </span>
        {past.length > 0 && (
          <button type="button" className="sm" aria-expanded={showPast} onClick={() => setShowPast((v) => !v)}>
            {showPast ? 'Hide past' : `Show ${past.length} past`}
          </button>
        )}
      </div>
      {rows.length === 0 ? (
        <p className="empty">{past.length > 0 ? 'Nothing current or to come.' : `No ${kind} booked.`}</p>
      ) : (
        <div className="table-scroll">
          <table className="register">
            <thead>
              <tr>
                {/* Fixed widths so the tables line up. */}
                <th className="c-trooper">Trooper</th>
                <th className={withDays ? 'c-dates' : 'c-when'}>{withDays ? 'Dates' : 'When'}</th>
                {withDays && <th className="n c-days">Days</th>}
                <th className="c-sheets">Sheets affected</th>
                <th>Note</th>
                {canCancel && <th className="c-act" aria-label="Actions" />}
              </tr>
            </thead>
            <tbody>
              {rows.map((absence, i) => (
                <RegisterRow
                  key={absence.id}
                  absence={absence}
                  now={now}
                  withDays={withDays}

                  firstPast={showPast && i === current.length && current.length > 0}
                  canCancel={canCancel}
                  onCancelled={onCancelled}
                  onError={onError}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

interface FormProps {
  roster: Trooper[]
  onBooked: (absence: Absence) => void
  onError: (e: unknown) => Promise<void>
}

function BookingForm({ roster, onBooked, onError }: FormProps) {
  const [draft, setDraft] = useState<LeaveDraft>(() => ({
    trooper: '',
    kind: 'MC',
    span: 'FULL',
    from: today(),
    to: today(),
    start: '09:00',
    end: '11:00',
    note: '',
  }))
  const [preview, setPreview] = useState<Preview>(null)
  const [busy, setBusy] = useState(false)

  const trooper = roster.some((t) => t.name === draft.trooper) ? draft.trooper : (roster[0]?.name ?? '')

  const change = (patch: Partial<LeaveDraft>) => {
    setDraft((current) => {
      const next = { ...current, ...patch }
      // MA is always a window; other kinds never are.
      if (next.kind === 'MA') next.span = 'WINDOW'
      else if (next.span === 'WINDOW') next.span = 'FULL'
      // Push the end along with the start.
      if (next.to < next.from) next.to = next.from
      return next
    })
  }

  // Ask the server which sheets this reaches, after typing stops.
  useEffect(() => {
    if (!trooper || !draft.from) return
    let stale = false
    const timer = setTimeout(() => {
      api.previewAbsence(requestOf({ ...draft, trooper }))
        .then((reach) => { if (!stale) setPreview({ reach }) })
        .catch((e: unknown) => {
          if (stale) return
          if (e instanceof ApiError && e.status === 400) {
            setPreview({ problem: e.message })
            return
          }
          setPreview(null)
          void onError(e)
        })
    }, 250)
    return () => {
      stale = true
      clearTimeout(timer)
    }
  }, [draft, trooper, onError])

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    try {
      onBooked(await api.bookAbsence(requestOf({ ...draft, trooper })))
      setDraft((current) => ({ ...current, note: '' }))
    } catch (err) {
      await onError(err)
    } finally {
      setBusy(false)
    }
  }

  const problem = preview && 'problem' in preview ? preview.problem : null

  return (
    <form className="leave-form" onSubmit={(e) => void submit(e)}>
      <label>
        Trooper
        <select value={trooper} onChange={(e) => change({ trooper: e.target.value })} required>
          {roster.map((t) => <option key={t.id} value={t.name}>{t.name}</option>)}
        </select>
      </label>
      <label>
        Type
        <select value={draft.kind} onChange={(e) => change({ kind: e.target.value as AbsenceKind })}>
          {KINDS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      {draft.kind !== 'MA' && (
        <label>
          Span
          <select value={draft.span} onChange={(e) => change({ span: e.target.value as AbsenceSpan })}>
            {SPANS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </label>
      )}
      <label>
        {draft.span === 'FULL' ? 'From' : 'Date'}
        <input type="date" value={draft.from} onChange={(e) => change({ from: e.target.value })} required />
      </label>
      {draft.span === 'FULL' && (
        <label>
          To
          <input type="date" value={draft.to} min={draft.from} onChange={(e) => change({ to: e.target.value })} />
        </label>
      )}
      {draft.span === 'WINDOW' && (
        <label>
          Time
          <span className="times">
            <input type="time" value={draft.start} onChange={(e) => change({ start: e.target.value })} required />
            to
            <input type="time" value={draft.end} onChange={(e) => change({ end: e.target.value })} required />
          </span>
        </label>
      )}
      <label>
        Note
        <input
          value={draft.note}
          onChange={(e) => change({ note: e.target.value })}
          placeholder="optional"
          maxLength={200}
        />
      </label>
      <button type="submit" className="primary" disabled={busy || !trooper || problem !== null}>
        {busy ? 'Booking…' : 'Book'}
      </button>

      <p className={problem ? 'preview error' : 'preview note'} aria-live="polite">
        {preview === null ? ' ' : problem ?? describe((preview as { reach: AbsenceReach }).reach)}
      </p>
    </form>
  )
}

/** Leave on the 11th mostly lands on the 10th's sheet, so spell it out. */
function describe(reach: AbsenceReach): string {
  const sheets = reach.sheets.length === 0
    ? 'reaches no sheet'
    : `reaches ${reach.sheets.length} sheet${reach.sheets.length === 1 ? '' : 's'}: ${reach.sheets.map(shortDate).join(', ')}`
  return `${shortInstant(reach.covers)} to ${shortInstant(reach.until)} · ${sheets}. ` +
    'A sheet is named by the evening it starts, so a morning appointment lands on the previous day’s sheet.'
}

interface RowProps {
  absence: Absence
  now: string
  withDays: boolean
  firstPast: boolean
  canCancel: boolean
  onCancelled: (absence: Absence) => void
  onError: (e: unknown) => Promise<void>
}

function RegisterRow({ absence, now, withDays, firstPast, canCancel, onCancelled, onError }: RowProps) {
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)

  const cancel = async () => {
    setBusy(true)
    try {
      await api.cancelAbsence(absence.id)
      onCancelled(absence)
    } catch (e) {
      setBusy(false)
      await onError(e)
    }
  }

  const { sheets } = absence
  const affected = sheets.length === 0
    ? 'none'
    : sheets.length > 3
      ? `${sheets.length} sheets, ${shortDate(sheets[0])} to ${shortDate(sheets[sheets.length - 1])}`
      : sheets.map(shortDate).join(', ')

  const past = isOver(absence, now)
  const days = daysOf(absence)

  return (
    <tr className={[past && 'past', firstPast && 'first-past'].filter(Boolean).join(' ') || undefined}>
      <td>{absence.trooper}</td>
      {/* Resolved interval on hover. */}
      <td title={`${shortInstant(absence.covers)} → ${shortInstant(absence.until)}`}>
        {bookedAs(absence)}
        {isRunning(absence, now) && <span className="tag now">now</span>}
      </td>
      {withDays && <td className="n">{days === null ? '' : daysLabel(days)}</td>}
      <td title={sheets.join(', ')}>{affected}</td>
      <td>{absence.note ?? ''}</td>
      {canCancel && (
        <td className="row-actions">
          {confirming ? (
            <>
              <button type="button" className="danger solid" onClick={() => void cancel()} disabled={busy}>
                {busy ? 'Cancelling…' : 'Cancel leave'}
              </button>
              <button type="button" onClick={() => setConfirming(false)} disabled={busy}>Keep</button>
            </>
          ) : (
            <button type="button" onClick={() => setConfirming(true)}>Cancel</button>
          )}
        </td>
      )}
    </tr>
  )
}
