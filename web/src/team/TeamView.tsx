import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import type { Invite, Member } from '../api/types'
import { useReport } from '../api/useReport'
import { localDay, shortDate } from '../dates'
import { InviteLink } from './InviteLink'

interface Props {
  /** True while the tab is showing; members and invites are re-read on each showing. */
  active: boolean
}

/**
 * The team's accounts and invites, for an ADMIN. Accounts are who can sign
 * in; they are not the roster, and removing one leaves every Trooper and
 * every logged sheet as it was.
 */
export function TeamView({ active }: Props) {
  const [members, setMembers] = useState<Member[] | null>(null)
  const [pending, setPending] = useState<Invite[]>([])
  const [made, setMade] = useState<Invite | null>(null)
  const [role, setRole] = useState<Invite['role']>('USER')
  const [error, setError] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)
  const report = useReport(setError)

  const reload = useCallback(async () => {
    const [m, i] = await Promise.all([api.members(), api.invites()])
    setMembers(m)
    setPending(i)
  }, [])

  useEffect(() => {
    if (!active) return
    let stale = false
    Promise.all([api.members(), api.invites()])
      .then(([m, i]) => {
        if (stale) return
        setMembers(m)
        setPending(i)
      })
      .catch((e: unknown) => { if (!stale) void report(e) })
    return () => { stale = true }
  }, [active, report])

  /** Run a change, then re-read both lists, so what is shown is what the server holds. */
  const change = async (work: () => Promise<unknown>, done: string) => {
    setError(null)
    setNote(null)
    try {
      await work()
      await reload()
      setNote(done)
    } catch (e) {
      await report(e)
    }
  }

  const invite = async () => {
    setError(null)
    setNote(null)
    try {
      setMade(await api.createInvite(role))
      setPending(await api.invites())
    } catch (e) {
      await report(e)
    }
  }

  return (
    <section aria-label="Team">
      <div className="panel">
        <div className="panel-hd">
          <h2>Invite</h2>
          <span className="meta">{pending.length} waiting</span>
        </div>
        <div className="inline-form">
          <label>
            Role
            <select value={role} onChange={(e) => setRole(e.target.value as Invite['role'])}>
              <option value="USER">USER — sees the sheets</option>
              <option value="ADMIN">ADMIN — runs the team</option>
            </select>
          </label>
          <button type="button" className="primary" onClick={() => void invite()}>Make link</button>
        </div>
        {made && <InviteLink key={made.id} invite={made} lead="Send this by chat." />}
        {(error || note) && (
          <p className={`message${error ? ' error' : ' note'}`} role={error ? 'alert' : 'status'}>{error ?? note}</p>
        )}
        {pending.length > 0 && (
          <div className="table-scroll">
            <table className="register">
              <thead>
                <tr>
                  <th>For</th>
                  <th>Made by</th>
                  <th>Made</th>
                  <th>Expires</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {pending.map((i) => (
                  <PendingRow
                    key={i.id}
                    invite={i}
                    onRevoke={() => change(() => api.revokeInvite(i.id), 'Invite revoked; its link no longer works.')}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="panel">
        <div className="panel-hd">
          <h2>Members</h2>
          {members && <span className="meta">{members.length} {members.length === 1 ? 'account' : 'accounts'}</span>}
        </div>
        {members === null ? (
          <p className="empty">Loading…</p>
        ) : (
          <div className="table-scroll">
            <table className="register">
              <thead>
                <tr>
                  <th>Username</th>
                  <th>Name</th>
                  <th>Role</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {members.map((m) => (
                  <MemberRow
                    key={m.id}
                    member={m}
                    onRole={(next) => change(() => api.setMemberRole(m.id, next), `${m.username} is now ${next}.`)}
                    onRemove={() => change(() => api.removeMember(m.id), `${m.username} can no longer sign in.`)}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  )
}

function PendingRow({ invite, onRevoke }: { invite: Invite; onRevoke: () => Promise<void> }) {
  const [confirming, setConfirming] = useState(false)
  return (
    <tr>
      <td><span className={`role${invite.role === 'ADMIN' ? ' admin' : ''}`}>{invite.role}</span></td>
      <td>{invite.createdBy}</td>
      <td>{shortDate(localDay(invite.createdAt))}</td>
      <td>{shortDate(localDay(invite.expiresAt))}</td>
      <td className="row-actions">
        {confirming ? (
          <>
            <button type="button" className="danger solid" onClick={() => void onRevoke()}>Revoke link</button>
            <button type="button" onClick={() => setConfirming(false)}>Keep</button>
          </>
        ) : (
          <button type="button" onClick={() => setConfirming(true)}>Revoke</button>
        )}
      </td>
    </tr>
  )
}

interface MemberRowProps {
  member: Member
  onRole: (role: Member['role']) => Promise<void>
  onRemove: () => Promise<void>
}

function MemberRow({ member, onRole, onRemove }: MemberRowProps) {
  const [confirming, setConfirming] = useState(false)
  return (
    <tr>
      <td>{member.username}{member.you && <span className="tag">you</span>}</td>
      <td>{member.displayName}</td>
      <td>
        {/* Your own role is not changed from here: stepping down would sign
            you out mid-click. Another admin can change it. */}
        {member.you ? (
          <span className={`role${member.role === 'ADMIN' ? ' admin' : ''}`}>{member.role}</span>
        ) : (
          <select
            aria-label={`${member.username} role`}
            value={member.role}
            onChange={(e) => void onRole(e.target.value as Member['role'])}
          >
            <option value="USER">USER</option>
            <option value="ADMIN">ADMIN</option>
          </select>
        )}
      </td>
      <td className="row-actions">
        {member.you ? null : confirming ? (
          <>
            <button type="button" className="danger solid" onClick={() => void onRemove()}>Remove account</button>
            <button type="button" onClick={() => setConfirming(false)}>Keep</button>
          </>
        ) : (
          <button type="button" onClick={() => setConfirming(true)}>Remove</button>
        )}
      </td>
    </tr>
  )
}
