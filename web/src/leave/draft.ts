import type { AbsenceKind, AbsenceRequest, AbsenceSpan } from '../api/types'

/** The booking form's fields, as strings. `requestOf` picks what to send. */
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
    // Only the fields this span uses.
    to: draft.span === 'FULL' ? draft.to || draft.from : null,
    start: draft.span === 'WINDOW' ? draft.start : null,
    end: draft.span === 'WINDOW' ? draft.end : null,
    note: draft.note.trim() || null,
  }
}
