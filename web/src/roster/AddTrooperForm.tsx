import { useState, type FormEvent } from 'react'
import { api } from '../api/client'
import type { Trooper } from '../api/types'
import { emptyDraft, particularsOf } from './draft'
import { TrooperFields } from './TrooperFields'

interface Props {
  onAdded: (trooper: Trooper) => void

  onError: (e: unknown) => Promise<void>
}

export function AddTrooperForm({ onAdded, onError }: Props) {
  const [draft, setDraft] = useState(emptyDraft)
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    try {
      onAdded(await api.addTrooper(particularsOf(draft)))
      setDraft(emptyDraft)
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
        <TrooperFields draft={draft} onChange={setDraft} />
        <button type="submit" className="primary" disabled={busy || draft.name.trim() === ''}>
          {busy ? 'Adding…' : 'Add'}
        </button>
      </fieldset>
    </form>
  )
}
