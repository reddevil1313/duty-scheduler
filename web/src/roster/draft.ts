import type { Trooper, TrooperParticulars } from '../api/types'

// Separate from TrooperFields: fast refresh needs component-only modules.
export interface Draft {
  name: string
  rank: string
  stayOut: boolean
  onNight: boolean
}

export const emptyDraft: Draft = { name: '', rank: '', stayOut: false, onNight: false }

export function draftOf(trooper: Trooper): Draft {
  return {
    name: trooper.name,
    rank: trooper.rank ?? '',
    stayOut: trooper.stayOut,
    onNight: trooper.onNight,
  }
}

export function particularsOf(draft: Draft): TrooperParticulars {
  return {
    name: draft.name.trim(),
    rank: draft.rank.trim() || null,
    stayOut: draft.stayOut,
    onNight: draft.onNight,
  }
}
