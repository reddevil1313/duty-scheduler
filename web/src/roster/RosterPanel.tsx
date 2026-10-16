import { useState, type FormEvent } from 'react'
import { api } from '../api/client'
import type { Trooper } from '../api/types'
import { AddTrooperForm } from './AddTrooperForm'
import { draftOf, particularsOf, type Draft } from './draft'
import { TrooperFields } from './TrooperFields'

interface Props {
  roster: Trooper[]
  onAdded: (trooper: Trooper) => void
  onUpdated: (before: Trooper, after: Trooper) => void
  onRemoved: (trooper: Trooper) => void
  onError: (e: unknown) => Promise<void>
}

/**
 * The roster, for an ADMIN to add to, edit and remove from. Nobody else sees
 * it, and the server refuses every change from anyone else regardless.
 */
export function RosterPanel({ roster, onAdded, onUpdated, onRemoved, onError }: Props) {
  return (
    <section className="roster" aria-label="Roster">
      <h2>Roster</h2>
      <table>
        <thead>
          <tr>
            <th>Name</th>
            <th>Rank</th>
            <th>Stay-out</th>
            <th>Night</th>
            <th aria-label="Actions" />
          </tr>
        </thead>
        <tbody>
          {roster.map((trooper) => (
            <RosterRow
              key={trooper.id}
              trooper={trooper}
              onUpdated={onUpdated}
              onRemoved={onRemoved}
              onError={onError}
            />
          ))}
        </tbody>
      </table>
      <AddTrooperForm onAdded={onAdded} onError={onError} />
    </section>
  )
}

type Mode = { kind: 'view' } | { kind: 'edit'; draft: Draft } | { kind: 'confirm-remove' }

interface RowProps {
  trooper: Trooper
  onUpdated: (before: Trooper, after: Trooper) => void
  onRemoved: (trooper: Trooper) => void
  onError: (e: unknown) => Promise<void>
}

function RosterRow({ trooper, onUpdated, onRemoved, onError }: RowProps) {
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
      // No setBusy(false) on success: the row unmounts as the board drops it.
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
        <td colSpan={5}>
          <form onSubmit={(e) => void save(e, draft)}>
            <TrooperFields draft={draft} onChange={(next) => setMode({ kind: 'edit', draft: next })} />
            <button type="submit" disabled={busy || draft.name.trim() === ''}>
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
      <td>{trooper.stayOut ? 'Yes' : ''}</td>
      <td>{trooper.onNight ? 'Yes' : ''}</td>
      <td className="row-actions">
        {mode.kind === 'confirm-remove' ? (
          // Asked in the row rather than with window.confirm, which blocks the
          // page and cannot say what removal will actually do.
          <>
            <span className="note">
              Off the roster. Kept on any sheet they already stood.
            </span>
            <button type="button" className="danger" onClick={() => void remove()} disabled={busy}>
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
      </td>
    </tr>
  )
}
