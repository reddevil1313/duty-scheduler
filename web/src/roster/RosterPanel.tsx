import { useState, type FormEvent } from 'react'
import { api } from '../api/client'
import type { TickKind, Ticks, Trooper } from '../api/types'
import { AddTrooperForm } from './AddTrooperForm'
import { draftOf, particularsOf, type Draft } from './draft'
import { TrooperFields } from './TrooperFields'

const TICK_KINDS: TickKind[] = ['MC', 'OL', 'AL']

interface Props {
  roster: Trooper[]
  /** False for a USER: read only. */
  canEdit: boolean
  ticks: Ticks
  onTick: (name: string, kind: TickKind | null) => void
  onAdded: (trooper: Trooper) => void
  onUpdated: (before: Trooper, after: Trooper) => void
  onRemoved: (trooper: Trooper) => void
  onError: (e: unknown) => Promise<void>
}

export function RosterPanel({ roster, canEdit, ticks, onTick, onAdded, onUpdated, onRemoved, onError }: Props) {
  return (
    <section className="roster" aria-label="Roster">
      {canEdit && <DefaultBus roster={roster} onUpdated={onUpdated} onError={onError} />}
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Rank</th>
              <th>Stay-out</th>
              <th>Night</th>
              {canEdit && TICK_KINDS.map((k) => <th key={k} className="tick">{k}</th>)}
              {canEdit && <th aria-label="Actions" />}
            </tr>
          </thead>
          <tbody>
            {roster.map((trooper) => (
              <RosterRow
                key={trooper.id}
                trooper={trooper}
                canEdit={canEdit}
                tick={ticks[trooper.name] ?? null}
                onTick={onTick}
                onUpdated={onUpdated}
                onRemoved={onRemoved}
                onError={onError}
              />
            ))}
          </tbody>
        </table>
      </div>
      {canEdit && <AddTrooperForm onAdded={onAdded} onError={onError} />}
    </section>
  )
}

interface DefaultBusProps {
  roster: Trooper[]
  onUpdated: (before: Trooper, after: Trooper) => void
  onError: (e: unknown) => Promise<void>
}

/** Where the schedule's BUS pick starts. Saved on change; the server moves it off anyone else. */
function DefaultBus({ roster, onUpdated, onError }: DefaultBusProps) {
  const [busy, setBusy] = useState(false)
  const current = roster.find((t) => t.defaultBus) ?? null

  const choose = async (id: string) => {
    const target = id === '' ? current : roster.find((t) => t.id === Number(id))
    if (!target) return
    setBusy(true)
    try {
      const after = await api.updateTrooper(target.id, { ...particularsOf(draftOf(target)), defaultBus: id !== '' })
      onUpdated(target, after)
    } catch (err) {
      await onError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="default-bus">
      <label>
        Default BUS
        <select value={current?.id ?? ''} disabled={busy} onChange={(e) => void choose(e.target.value)}>
          <option value="">None</option>
          {roster.filter((t) => !t.stayOut).map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
        </select>
      </label>
    </div>
  )
}

type Mode = { kind: 'view' } | { kind: 'edit'; draft: Draft } | { kind: 'confirm-remove' }

interface RowProps {
  trooper: Trooper
  canEdit: boolean
  tick: TickKind | null
  onTick: (name: string, kind: TickKind | null) => void
  onUpdated: (before: Trooper, after: Trooper) => void
  onRemoved: (trooper: Trooper) => void
  onError: (e: unknown) => Promise<void>
}

function RosterRow({ trooper, canEdit, tick, onTick, onUpdated, onRemoved, onError }: RowProps) {
  const [mode, setMode] = useState<Mode>({ kind: 'view' })
  const [busy, setBusy] = useState(false)

  const save = async (e: FormEvent, draft: Draft) => {
    e.preventDefault()
    setBusy(true)
    try {
      onUpdated(trooper, await api.updateTrooper(trooper.id, particularsOf(draft)))
      setMode({ kind: 'view' })
    } catch (err) {
      await onError(err)
    } finally {
      setBusy(false)
    }
  }

  const remove = async () => {
    setBusy(true)
    try {
      await api.removeTrooper(trooper.id)
      // No setBusy(false): the row unmounts.
      onRemoved(trooper)
    } catch (err) {
      setBusy(false)
      await onError(err)
    }
  }

  if (mode.kind === 'edit') {
    const { draft } = mode
    return (
      <tr className="editing">
        <td colSpan={8}>
          <form onSubmit={(e) => void save(e, draft)}>
            <TrooperFields draft={draft} onChange={(next) => setMode({ kind: 'edit', draft: next })} />
            <button type="submit" className="primary" disabled={busy || draft.name.trim() === ''}>
              {busy ? 'Saving…' : 'Save'}
            </button>
            <button type="button" onClick={() => setMode({ kind: 'view' })} disabled={busy}>
              Cancel
            </button>
          </form>
        </td>
      </tr>
    )
  }

  return (
    <tr>
      <td>{trooper.name}</td>
      <td>{trooper.rank ?? ''}</td>
      <td>{trooper.stayOut && <span className="tag">SO</span>}</td>
      <td>{trooper.onNight && <span className="tag">N</span>}</td>
      {canEdit && TICK_KINDS.map((kind) => (
        <td key={kind} className="tick">
          <input
            type="checkbox"
            aria-label={`${trooper.name} ${kind}`}
            checked={tick === kind}

            onChange={(e) => onTick(trooper.name, e.target.checked ? kind : null)}
          />
        </td>
      ))}
      {canEdit && <td className="row-actions">
        {mode.kind === 'confirm-remove' ? (
          // Inline confirm, so it can say what removal does.
          <>
            <span className="note">
              Off the roster. Kept on any sheet they already stood.
            </span>
            <button type="button" className="danger solid" onClick={() => void remove()} disabled={busy}>
              {busy ? 'Removing…' : 'Remove'}
            </button>
            <button type="button" onClick={() => setMode({ kind: 'view' })} disabled={busy}>
              Keep
            </button>
          </>
        ) : (
          <>
            <button type="button" onClick={() => setMode({ kind: 'edit', draft: draftOf(trooper) })}>
              Edit
            </button>
            <button type="button" onClick={() => setMode({ kind: 'confirm-remove' })}>
              Remove
            </button>
          </>
        )}
      </td>}
    </tr>
  )
}
