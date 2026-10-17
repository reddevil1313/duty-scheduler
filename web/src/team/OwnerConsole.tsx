import { useEffect, useState, type FormEvent } from 'react'
import { api } from '../api/client'
import type { Invite, Team } from '../api/types'
import { useReport } from '../api/useReport'
import { localDay, shortDate } from '../dates'
import { InviteLink } from './InviteLink'

/** The owner's view: teams and account counts, no team data. */
export function OwnerConsole() {
  const [teams, setTeams] = useState<Team[] | null>(null)
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // One link on screen at a time.
  const [link, setLink] = useState<{ team: string; invite: Invite } | null>(null)
  const report = useReport(setError)

  useEffect(() => {
    let stale = false
    api.teams().then((t) => { if (!stale) setTeams(t) }).catch((e: unknown) => { if (!stale) void report(e) })
    return () => { stale = true }
  }, [report])

  const create = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const created = await api.createTeam(name)
      setTeams((current) => [...(current ?? []), created.team].sort((a, b) => a.name.localeCompare(b.name)))
      setLink({ team: created.team.name, invite: created.invite })
      setName('')
    } catch (err) {
      await report(err)
    } finally {
      setBusy(false)
    }
  }

  const newAdminLink = async (team: Team) => {
    setError(null)
    try {
      setLink({ team: team.name, invite: await api.inviteToTeam(team.id, 'ADMIN') })
    } catch (err) {
      await report(err)
    }
  }

  return (
    <section aria-label="Teams">
      <div className="panel">
        <div className="panel-hd">
          <h2>New team</h2>
        </div>
        <form className="inline-form" onSubmit={(e) => void create(e)}>
          <label>
            Team name
            <input value={name} onChange={(e) => setName(e.target.value)} maxLength={60} required />
          </label>
          <button type="submit" className="primary" disabled={busy || name.trim() === ''}>
            {busy ? 'Making…' : 'Make team'}
          </button>
          <p className="note full">
            A team starts empty. Its first admin joins by the link this gives you, and invites everyone else.
          </p>
        </form>
        {error && <p className="message error" role="alert">{error}</p>}
        {link && (
          <InviteLink
            key={link.invite.id}
            invite={link.invite}
            lead={`Send this to ${link.team}'s admin.`}
          />
        )}
      </div>

      <div className="panel">
        <div className="panel-hd">
          <h2>Teams</h2>
          {teams && <span className="meta">{teams.length} {teams.length === 1 ? 'team' : 'teams'}</span>}
        </div>
        {teams === null ? (
          <p className="empty">Loading…</p>
        ) : teams.length === 0 ? (
          <p className="empty">No teams yet.</p>
        ) : (
          <div className="table-scroll">
            <table className="register">
              <thead>
                <tr>
                  <th>Team</th>
                  <th className="n">Admins</th>
                  <th className="n">Users</th>
                  <th>Made</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {teams.map((team) => (
                  <tr key={team.id}>
                    <td>{team.name}</td>
                    <td className="n">{team.admins}</td>
                    <td className="n">{team.users}</td>
                    <td>{shortDate(localDay(team.createdAt))}</td>
                    <td className="row-actions">
                      <button type="button" onClick={() => void newAdminLink(team)}>New admin link</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  )
}
