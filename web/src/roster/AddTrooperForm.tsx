import { useState, type FormEvent } from 'react'
import { api } from '../api/client'
import type { Trooper } from '../api/types'

interface Props {
  onAdded: (trooper: Trooper) => void
  /** Failures go to the board's own banner, so a 401 still triggers its session recheck. */
  onError: (e: unknown) => Promise<void>
}

/**
 * Add a Trooper to the roster. ADMIN only — the board does not render it for
 * anyone else, and the server refuses it regardless.
 */
export function AddTrooperForm({ onAdded, onError }: Props) {
  const [name, setName] = useState('')
  const [rank, setRank] = useState('')
  const [stayOut, setStayOut] = useState(false)
  const [onNight, setOnNight] = useState(false)
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    try {
      const added = await api.addTrooper({
        name: name.trim(),
        rank: rank.trim() || null,
        stayOut,
        onNight,
      })
      onAdded(added)
      setName('')
      setRank('')
      setStayOut(false)
      setOnNight(false)
    } catch (err) {
      await onError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="add-trooper" onSubmit={(e) => void submit(e)}>
      <fieldset>
        <legend>Add Trooper</legend>
        <label>
          Name
          <input value={name} onChange={(e) => setName(e.target.value)} maxLength={40} required />
        </label>
        <label>
          Rank
          <input value={rank} onChange={(e) => setRank(e.target.value)} maxLength={20} />
        </label>
        <label className="pill">
          <input
            type="checkbox"
            checked={stayOut}
            onChange={(e) => {
              setStayOut(e.target.checked)
              // A stay-out cannot hold night hours, and the server says so with
              // a 422. Unticking here stops the form offering the mistake.
              if (e.target.checked) setOnNight(false)
            }}
          />
          Stay-out
        </label>
        <label className="pill">
          <input
            type="checkbox"
            checked={onNight}
            disabled={stayOut}
            onChange={(e) => setOnNight(e.target.checked)}
          />
          Night group
        </label>
        <button type="submit" disabled={busy || name.trim() === ''}>
          {busy ? 'Adding…' : 'Add'}
        </button>
      </fieldset>
    </form>
  )
}
