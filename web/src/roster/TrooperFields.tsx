import type { Draft } from './draft'

interface Props {
  draft: Draft
  onChange: (draft: Draft) => void
}

/** The inputs for a Trooper's particulars, shared by the add form and the edit row. */
export function TrooperFields({ draft, onChange }: Props) {
  return (
    <>
      <label>
        Name
        <input
          value={draft.name}
          onChange={(e) => onChange({ ...draft, name: e.target.value })}
          maxLength={40}
          required
        />
      </label>
      <label>
        Rank
        <input
          value={draft.rank}
          onChange={(e) => onChange({ ...draft, rank: e.target.value })}
          maxLength={20}
        />
      </label>
      <label className="pill">
        <input
          type="checkbox"
          checked={draft.stayOut}
          // A stay-out cannot hold night hours, and the server says so with a
          // 422. Clearing the night tick here stops the form offering the mistake.
          onChange={(e) => onChange({
            ...draft,
            stayOut: e.target.checked,
            onNight: e.target.checked ? false : draft.onNight,
          })}
        />
        Stay-out
      </label>
      <label className="pill">
        <input
          type="checkbox"
          checked={draft.onNight}
          disabled={draft.stayOut}
          onChange={(e) => onChange({ ...draft, onNight: e.target.checked })}
        />
        Night group
      </label>
    </>
  )
}
