import { useEffect, useState, type FormEvent } from 'react'
import { ApiError, api } from '../api/client'
import type { Absence, AbsenceKind, AbsenceReach, AbsenceSpan, Ticks, Trooper } from '../api/types'
import { useReport } from '../api/useReport'
import { useSession } from '../auth/session-context'
import { shortDate, shortInstant, today } from '../dates'
import { requestOf, type LeaveDraft } from './draft'

const KINDS: [AbsenceKind, string][] = [
  ['MC', 'MC'],
  ['OL', 'OL'],
  ['AL', 'AL'],
  ['MA', 'MA — appointment'],
]

// No window here: a time window is what an appointment is, so MA always gets
// one and nothing else is offered it.
const SPANS: [AbsenceSpan, string][] = [
  ['FULL', 'Full days'],
  ['DAY_HALF', 'Half day — 0800 to 2000'],
  ['NIGHT_HALF', 'Half day — 2000 to 0800'],
]

type Preview = { reach: AbsenceReach } | { problem: string } | null

interface Props {
  /** True while the tab is showing. The roster is re-read on each showing, so a Trooper added on the board is there to book. */
  active: boolean
  /** Roster ticks: shown here so they are visible, and so it is plain they are not bookings. */
  ticks: Ticks
}

/**
 * The leave register: MCs, off-in-lieu, annual leave and medical
 * appointments. Anyone signed in can read it; only an ADMIN can book or
 * cancel, and the server refuses anyone else regardless.
 */
export function LeaveRegister({ active, ticks }: Props) {
  const { session } = useSession()
  const isAdmin = session?.role === 'ADMIN'

  const [roster, setRoster] = useState<Trooper[]>([])
  const [register, setRegister] = useState<Absence[]>([])
  const [error, setError] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)
  const report = useReport(setError)

  useEffect(() => {
    if (!active) return
    // Cancelled on the way out, so a quick switch away and back cannot let the
    // older of two reads land last.
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
    setRegister((current) => [absence, ...current].sort(latestFirst))
    setError(null)
    setNote(`${absence.trooper} booked ${absence.kind}.`)
  }

  const cancelled = (absence: Absence) => {
    setRegister((current) => current.filter((a) => a.id !== absence.id))
    setError(null)
    setNote(`${absence.trooper}'s ${absence.kind} cancelled.`)
  }

  return (
    <section className="leave" aria-label="Leave register">
      <div className="section-hd">
        <h2>Leave register</h2>
        {register.length > 0 && (
          <span className="note">{register.length} {register.length === 1 ? 'entry' : 'entries'}</span>
        )}
      </div>

      {isAdmin ? (
        <BookingForm roster={roster} onBooked={booked} onError={report} />
      ) : (
        <p className="note">You have view-only access. Ask an ADMIN to record leave.</p>
      )}

      {Object.keys(ticks).length > 0 && (
        <p className="ticks note">
          Ticked out on the Roster tab, for generating only — not booked, and not counted in the records:{' '}
          {Object.entries(ticks).map(([name, kind]) => `${name} (${kind})`).join(', ')}.
          {isAdmin && ' To make one stick, book it here.'}
        </p>
      )}

      {error && <p className="error" role="alert">{error}</p>}
      {note && !error && <p className="note">{note}</p>}

      {register.length === 0 ? (
        <p className="note">
          Nothing booked yet. Anything recorded here is taken into account when that day's sheet is generated.
        </p>
      ) : (
        <table className="register">
          <thead>
            <tr>
              <th>Trooper</th>
              <th>Type</th>
              <th>Covers</th>
              <th>Sheets affected</th>
              <th>Note</th>
              {isAdmin && <th aria-label="Actions" />}
            </tr>
          </thead>
          <tbody>
            {register.map((absence) => (
              <RegisterRow
                key={absence.id}
                absence={absence}
                canCancel={isAdmin}
                onCancelled={cancelled}
                onError={report}
              />
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}

function latestFirst(a: Absence, b: Absence): number {
  return b.from.localeCompare(a.from) || b.id - a.id
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

  // Default to the first Trooper once the roster arrives, and move off one who
  // has since been removed from it.
  const trooper = roster.some((t) => t.name === draft.trooper) ? draft.trooper : (roster[0]?.name ?? '')

  const change = (patch: Partial<LeaveDraft>) => {
    setDraft((current) => {
      const next = { ...current, ...patch }
      // An appointment is always a window on one day; anything else is full
      // days or a half. Kept in step here so the form never offers a shape the
      // kind cannot have.
      if (next.kind === 'MA') next.span = 'WINDOW'
      else if (next.span === 'WINDOW') next.span = 'FULL'
      // Moving the start past the end carries the end along, rather than
      // flagging an error about a field nobody touched.
      if (next.to < next.from) next.to = next.from
      return next
    })
  }

  // Ask the server what this booking would reach, a moment after typing stops.
  // The server owns the 2000 rule; re-deriving it here would be a second copy
  // free to drift from the first.
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
      <button type="submit" disabled={busy || !trooper || problem !== null}>
        {busy ? 'Booking…' : 'Book'}
      </button>

      <p className={problem ? 'preview error' : 'preview note'} aria-live="polite">
        {preview === null ? ' ' : problem ?? describe((preview as { reach: AbsenceReach }).reach)}
      </p>
    </form>
  )
}

/**
 * The sheets named outright. A sheet is named by the evening it starts, so
 * leave on the 11th mostly lands on the 10th's sheet — saying so at the moment
 * of booking is what stops that being a surprise a week later.
 */
function describe(reach: AbsenceReach): string {
  const sheets = reach.sheets.length === 0
    ? 'reaches no sheet'
    : `reaches ${reach.sheets.length} sheet${reach.sheets.length === 1 ? '' : 's'}: ${reach.sheets.map(shortDate).join(', ')}`
  return `${shortInstant(reach.covers)} to ${shortInstant(reach.until)} · ${sheets}. ` +
    'A sheet is named by the evening it starts, so a morning appointment lands on the previous day’s sheet.'
}

interface RowProps {
  absence: Absence
  canCancel: boolean
  onCancelled: (absence: Absence) => void
  onError: (e: unknown) => Promise<void>
}

function RegisterRow({ absence, canCancel, onCancelled, onError }: RowProps) {
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
  // Dimmed once it is over, so the eye lands on what still matters.
  const past = absence.until.slice(0, 10) < today()

  return (
    <tr className={past ? 'past' : undefined}>
      <td>{absence.trooper}</td>
      <td><span className={`kind ${absence.kind}`}>{absence.kind}</span></td>
      <td>{shortInstant(absence.covers)} → {shortInstant(absence.until)}</td>
      <td title={sheets.join(', ')}>{affected}</td>
      <td>{absence.note ?? ''}</td>
      {canCancel && (
        <td className="row-actions">
          {confirming ? (
            <>
              <button type="button" className="danger" onClick={() => void cancel()} disabled={busy}>
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
