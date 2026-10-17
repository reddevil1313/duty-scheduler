import { useState } from 'react'
import type { Invite } from '../api/types'
import { localDay, shortDate } from '../dates'
import { inviteLink } from './invite'

/** A new link, shown once. */
export function InviteLink({ invite, lead }: { invite: Invite; lead: string }) {
  const [copied, setCopied] = useState<'yes' | 'no' | null>(null)
  if (!invite.token) return null
  const link = inviteLink(invite.token)

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(link)
      setCopied('yes')
    } catch {
      // No clipboard access: select the field instead.
      setCopied('no')
    }
  }

  return (
    <div className="invite-link" role="status">
      <p className="note">
        {lead} It is for a new {invite.role}, works once, and expires {shortDate(localDay(invite.expiresAt))}.
        It is shown only now, so copy it before leaving this page.
      </p>
      <div className="row">
        <input
          readOnly
          value={link}
          aria-label="Invite link"
          onFocus={(e) => e.target.select()}
          ref={(el) => { if (el && copied === 'no') el.select() }}
        />
        <button type="button" className="primary" onClick={() => void copy()}>
          {copied === 'yes' ? 'Copied' : 'Copy link'}
        </button>
      </div>
      {copied === 'no' && <p className="note">Could not copy here. The link is selected: copy it with the keyboard.</p>}
    </div>
  )
}
