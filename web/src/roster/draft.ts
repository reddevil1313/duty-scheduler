import type { Trooper, TrooperParticulars } from '../api/types'

/**
 * What the Trooper inputs hold while someone is typing. Rank is a string here,
 * null on the wire.
 *
 * <p>In its own file rather than beside TrooperFields: Vite's fast refresh only
 * hot-swaps a module that exports nothing but components.
 */
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
