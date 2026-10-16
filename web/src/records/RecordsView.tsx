import { useEffect, useState, type FormEvent } from 'react'
import { api } from '../api/client'
import type { DayRecord, Records, TrooperRecord } from '../api/types'
import { useReport } from '../api/useReport'
import { useSession } from '../auth/session-context'
import { shortDate } from '../dates'

interface Props {
  /** True while the tab is showing; the records are re-read on each showing. */
  active: boolean
  /** Show a logged day on the board, where its grid can be corrected and logged again. */
  onOpen: (date: string) => void
}

/** Leave in days drops a needless ".0", and keeps a half day as ".5". */
function days(n: number): string {
  return n === 0 ? '' : String(Math.round(n * 10) / 10)
}

/**
 * The records: every Trooper's running totals, and a line per day logged.
 *
 * <p>Correcting what someone stood is done on the board, by opening the day
 * and logging it again, so the hours always match a real grid. What is edited
 * here is only what has no grid: hours from before the tracker, and hours
 * credited for a day not stood.
 */
export function RecordsView({ active, onOpen }: Props) {
  const { session } = useSession()
  const isAdmin = session?.role === 'ADMIN'

  const [records, setRecords] = useState<Records | null>(null)
  const [editingOpening, setEditingOpening] = useState(false)
  const [crediting, setCrediting] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)
  const report = useReport(setError)

  useEffect(() => {
    if (!active) return
    let cancelled = false
    api.records()
      .then((r) => { if (!cancelled) setRecords(r) })
      .catch((e: unknown) => { if (!cancelled) void report(e) })
    return () => { cancelled = true }
  }, [active, report])

  if (!records) {
    return <section className="records" aria-label="Records"><p className="note">Loading…</p></section>
  }

  const openingSaved = (next: Records) => {
    setRecords(next)
    setEditingOpening(false)
    setError(null)
    setNote('Opening hours saved.')
  }

  const credited = async (date: string) => {
    setRecords(await api.records())
    setCrediting(null)
    setError(null)
    setNote(`Credited hours saved for ${shortDate(date)}.`)
  }

  return (
    <section className="records" aria-label="Records">
      <div className="section-hd">
        <h2>Records</h2>
        <span className="note">
          {records.days.length} day{records.days.length === 1 ? '' : 's'} logged
        </span>
        {isAdmin && !editingOpening && (
          <button type="button" onClick={() => setEditingOpening(true)}>Edit opening hours</button>
        )}
      </div>

      {error && <p className="error" role="alert">{error}</p>}
      {note && !error && <p className="note">{note}</p>}

      {editingOpening ? (
        <OpeningForm
          troopers={records.troopers}
          onSaved={openingSaved}
          onCancel={() => setEditingOpening(false)}
          onError={report}
        />
      ) : (
        <TotalsTable troopers={records.troopers} />
      )}

      <div className="section-hd">
        <h2>Days logged</h2>
      </div>
      {records.days.length === 0 ? (
        <p className="note">No sheets logged yet.</p>
      ) : (
        <table className="register">
          <thead>
            <tr>
              <th>Date</th>
              <th className="n">Hours</th>
              <th className="n">Credited</th>
              <th>Night</th>
              <th>BUS</th>
              <th>Issues</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {records.days.map((day) => crediting === day.date ? (
              <tr key={day.date} className="editing">
                <td colSpan={7}>
                  <CreditForm
                    day={day}
                    troopers={records.troopers}
                    onSaved={() => credited(day.date)}
                    onCancel={() => setCrediting(null)}
                    onError={report}
                  />
                </td>
              </tr>
            ) : (
              <tr key={day.date}>
                <td>{shortDate(day.date)}</td>
                <td className="n">{day.worked}</td>
                <td className="n">{day.credited || ''}</td>
                <td>{day.night.join(', ')}</td>
                <td>{day.bus.join(', ')}</td>
                <td>{day.issues > 0 && <span className="kind MC">{day.issues} BROKEN</span>}</td>
                <td className="row-actions">
                  <button type="button" onClick={() => onOpen(day.date)}>Open on board</button>
                  {isAdmin && (
                    <button type="button" onClick={() => setCrediting(day.date)}>Credit hours</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}

function TotalsTable({ troopers }: { troopers: TrooperRecord[] }) {
  return (
    <table className="register totals">
      <thead>
        <tr>
          <th>Trooper</th>
          <th className="n">Opening</th>
          <th className="n">Worked</th>
          <th className="n">Credited</th>
          <th className="n">Total</th>
          <th className="n">Duties</th>
          <th className="n">Nights</th>
          <th className="n">BUS</th>
          <th className="n">MC</th>
          <th className="n">OL</th>
          <th className="n">AL</th>
          <th className="n">MA</th>
        </tr>
      </thead>
      <tbody>
        {troopers.map((t) => (
          <tr key={t.name} className={t.active ? undefined : 'past'}>
            <td>{t.name}{!t.active && <span className="tag">archived</span>}</td>
            <td className="n">{t.opening || ''}</td>
            <td className="n">{t.worked}</td>
            <td className="n">{t.credited || ''}</td>
            <td className="n total">{t.total}</td>
            <td className="n">{t.duties}</td>
            <td className="n">{t.nights}</td>
            <td className="n">{t.bus}</td>
            <td className="n">{days(t.mc)}</td>
            <td className="n">{days(t.ol)}</td>
            <td className="n">{days(t.al)}</td>
            <td className="n">{t.ma || ''}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

interface OpeningProps {
  troopers: TrooperRecord[]
  onSaved: (records: Records) => void
  onCancel: () => void
  onError: (e: unknown) => Promise<void>
}

function OpeningForm({ troopers, onSaved, onCancel, onError }: OpeningProps) {
  const [hours, setHours] = useState<Record<string, string>>(
    () => Object.fromEntries(troopers.map((t) => [t.name, String(t.opening)])))
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    try {
      // Only what changed is sent; the server leaves everyone else alone.
      const changed = Object.fromEntries(troopers
        .filter((t) => Number(hours[t.name] || 0) !== t.opening)
        .map((t) => [t.name, Number(hours[t.name] || 0)]))
      onSaved(await api.setOpening(changed))
    } catch (err) {
      setBusy(false)
      await onError(err)
    }
  }

  return (
    <form className="leave-form" onSubmit={(e) => void submit(e)}>
      <p className="note preview">Hours each Trooper had done before this tracker existed.</p>
      {troopers.map((t) => (
        <label key={t.name}>
          {t.name}
          <input
            type="number"
            min={0}
            step={1}
            className="hours"
            value={hours[t.name]}
            onChange={(e) => setHours((h) => ({ ...h, [t.name]: e.target.value }))}
          />
        </label>
      ))}
      <button type="submit" disabled={busy}>{busy ? 'Saving…' : 'Save'}</button>
      <button type="button" onClick={onCancel} disabled={busy}>Cancel</button>
    </form>
  )
}

interface CreditProps {
  day: DayRecord
  troopers: TrooperRecord[]
  onSaved: () => Promise<void>
  onCancel: () => void
  onError: (e: unknown) => Promise<void>
}

function CreditForm({ day, troopers, onSaved, onCancel, onError }: CreditProps) {
  const [hours, setHours] = useState<Record<string, string>>(
    () => Object.fromEntries(troopers.map((t) => [t.name, day.credits[t.name] ? String(day.credits[t.name]) : ''])))
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    try {
      // The whole day is sent: the server replaces the day's credits, so
      // anyone left blank has none.
      await api.credit(day.date, Object.fromEntries(
        Object.entries(hours).filter(([, h]) => Number(h) > 0).map(([name, h]) => [name, Number(h)])))
      await onSaved()
    } catch (err) {
      setBusy(false)
      await onError(err)
    }
  }

  return (
    <form className="leave-form" onSubmit={(e) => void submit(e)}>
      <p className="note preview">
        Hours credited for {shortDate(day.date)} to anyone who did not stand them, such as an MC or OL day.
        To change who stood what, open the day on the board instead.
      </p>
      {troopers.map((t) => (
        <label key={t.name}>
          {t.name}
          <input
            type="number"
            min={0}
            max={24}
            step={1}
            className="hours"
            value={hours[t.name]}
            onChange={(e) => setHours((h) => ({ ...h, [t.name]: e.target.value }))}
          />
        </label>
      ))}
      <button type="submit" disabled={busy}>{busy ? 'Saving…' : 'Save'}</button>
      <button type="button" onClick={onCancel} disabled={busy}>Cancel</button>
    </form>
  )
}
