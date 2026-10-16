import type { AbsenceKind, AbsenceRequest, AbsenceSpan } from '../api/types'

/**
 * The booking form while it is being filled in. Every field is a string the
 * inputs can hold; `requestOf` decides which ones the chosen span sends.
 */
export interface LeaveDraft {
  trooper: string
  kind: AbsenceKind
  span: AbsenceSpan
  from: string
  to: string
  start: string
  end: string
  note: string
}

export function requestOf(draft: LeaveDraft): AbsenceRequest {
  return {
    trooper: draft.trooper,
    kind: draft.kind,
    span: draft.span,
    from: draft.from,
    // Sent only where the span uses them. A leftover "to" date on a half day
    // would be ignored by the server, but it is clearer not to send it at all.
    to: draft.span === 'FULL' ? draft.to || draft.from : null,
    start: draft.span === 'WINDOW' ? draft.start : null,
    end: draft.span === 'WINDOW' ? draft.end : null,
    note: draft.note.trim() || null,
  }
}
