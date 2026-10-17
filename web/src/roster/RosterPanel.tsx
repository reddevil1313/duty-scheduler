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
      <table>
        <thead>
          <tr>
            <th>Name</th>
            <th>Rank</th>
            <th>Stay-out</th>
            <th>Night</th>
            {canEdit && <th className="tick" title="Who the board's BUS pick starts on">Default BUS</th>}
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
      {canEdit && <AddTrooperForm onAdded={onAdded} onError={onError} />}
    </section>
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
  // Optimistic default BUS tick while it saves.
  const [pendingBus, setPendingBus] = useState<boolean | null>(null)

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

  /** Saved as soon as it is ticked. */
  const setDefaultBus = async (on: boolean) => {
    setBusy(true)
    setPendingBus(on)
    try {
      onUpdated(trooper, await api.updateTrooper(trooper.id, { ...particularsOf(draftOf(trooper)), defaultBus: on }))
    } catch (err) {
      await onError(err)
    } finally {
      setBusy(false)
      setPendingBus(null)
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
        <td colSpan={9}>
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
      {canEdit && (
        <td className="tick">
          <input
            type="checkbox"
            aria-label={`${trooper.name} default BUS`}
            checked={pendingBus ?? trooper.defaultBus}

            disabled={trooper.stayOut || busy}
            onChange={(e) => void setDefaultBus(e.target.checked)}
          />
        </td>
      )}
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
