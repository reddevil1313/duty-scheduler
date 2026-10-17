import type { ReactNode } from 'react'
import type { Role } from '../api/types'

/** Help, with sections chosen by role. */
interface Section {
  id: string
  title: string
  roles: Role[]
  body: ReactNode
}

const SECTIONS: Section[] = [
  {
    id: 'owner',
    title: 'Running the server',
    roles: ['OWNER'],
    body: (
      <>
        <p>
          You belong to no team, and see none of a team's roster, sheets or leave: only the teams themselves and
          how many accounts each has.
        </p>
        <ol>
          <li>Under <b>New team</b>, give the team a name and press <b>Make team</b>.</li>
          <li>
            Copy the link that appears and send it to the person who will run that team. It is shown only once,
            works once, and expires after seven days.
          </li>
          <li>
            When they open it, they choose their own username and password and become the team's first admin.
            They invite everyone else.
          </li>
        </ol>
        <p>
          If a team's admins have all gone, or a first link expired unused, press <b>New admin link</b> on its
          row for another way in.
        </p>
      </>
    ),
  },
  {
    id: 'reading',
    title: 'Reading a sheet',
    roles: ['ADMIN', 'USER'],
    body: (
      <>
        <p>
          A sheet is named by the evening it starts. It runs from 2000 to 1959 the next morning, one column an hour,
          with the date changing at the heavier line at midnight. The silent hours are 2200 to 0600.
        </p>
        <ul>
          <li>
            Each coloured block is one stint at one post: <b>AP</b> and <b>GG</b> every hour, and <b>PAC</b> and{' '}
            <b>BUS</b> on weekdays. BUS is the 0700–1000 run.
          </li>
          <li>
            Rows run night group first, then day duty, then stay-outs. Hatched hours are leave; anyone away for the
            whole sheet is listed at the bottom, as are stay-outs on a weekend sheet, when they are not in.
          </li>
          <li>
            The coverage row under the grid shows how many posts are manned each hour against how many are
            needed. <b>Hrs</b> is each person's total; <b>Span</b> is from their first hour on to their last.
          </li>
          <li>
            <span className="tag">SO</span> marks a stay-out, who is in from 0800 to 1800 on weekdays only.
          </li>
          <li>
            On a sheet made with a fixed turnout, the <b>MP</b> row shows the hours left to the MP team.
          </li>
        </ul>
        <p>
          Pick a <b>Duty date</b> to see the sheet logged for it. <b>Save image</b> downloads it as a picture to
          send on.
        </p>
      </>
    ),
  },
  {
    id: 'day',
    title: "A day's sheet, start to finish",
    roles: ['ADMIN'],
    body: (
      <ol>
        <li>
          <b>Roster.</b> Check everyone is there, with stay-outs and the default night group marked. Someone off
          sick this morning gets an <b>MC</b>, <b>OL</b> or <b>AL</b> tick: it takes them out of the sheets you
          generate without booking anything, and is forgotten when you sign out.
        </li>
        <li>
          <b>Leave register.</b> Leave that is confirmed is booked here, and every sheet it reaches leaves the
          person out on its own. The form says which sheets a booking reaches before you book it.
        </li>
        <li>
          <b>Board.</b> Pick the <b>Duty date</b>, tick the <b>Night group</b>, choose who takes{' '}
          <b>BUS 0700–1000</b> (or leave it to <b>Any</b>), and press <b>Generate</b>. The solver shares the hours
          out as evenly as the rules allow, taking into account who has done most lately. If a sheet already logged
          for a day of the same shape and headcount fits, it is used again, and the board says which.
        </li>
        <li>
          <b>Short-handed?</b> Choose a <b>Turnout</b> of 5, 6 or 7 hours each. Everyone does that many, the
          silent hours stay fully manned, and what is left goes to the MP team.
        </li>
        <li>
          <b>Check it,</b> change it by hand if you need to (below), then <b>Log this sheet</b>, or{' '}
          <b>Log and save image</b> to log it and download the picture in one step. The log is the record of
          what was used.
        </li>
      </ol>
    ),
  },
  {
    id: 'editing',
    title: 'Changing a sheet by hand',
    roles: ['ADMIN'],
    body: (
      <>
        <ul>
          <li>Press <b>Edit</b>, then click any hour to give it a post or take it off duty.</li>
          <li>Press <b>Swap two Troopers</b>, then click two names to trade their whole day.</li>
          <li>
            Every change is checked against the same rules the solver keeps. Anything broken is listed under the
            grid, and the hours involved are marked.
          </li>
        </ul>
        <p>
          A sheet that breaks a rule can still be logged: the button reads <b>Log anyway</b>, and the day is
          counted with its issues in the records. To correct what someone actually stood, edit the logged sheet
          and log it again.
        </p>
      </>
    ),
  },
  {
    id: 'leave',
    title: 'Leave',
    roles: ['ADMIN', 'USER'],
    body: (
      <>
        <p>
          The register has a table for each kind: <b>MC</b> (medical certificate), <b>OL</b> (overseas leave),{' '}
          <b>AL</b> (annual leave) and <b>MA</b> (medical appointment). Leave running now is tagged{' '}
          <span className="tag now">now</span>; past leave is folded away until you show it. <b>Show</b> narrows
          every table to one person.
        </p>
        <p>
          Leave is booked as full days, a half day, or for an appointment a time on one day. Because a sheet is
          named by the evening it starts, leave on the 12th mostly lands on the 11th's sheet, and a morning
          appointment on the previous day's.
        </p>
      </>
    ),
  },
  {
    id: 'records',
    title: 'Records',
    roles: ['ADMIN', 'USER'],
    body: (
      <>
        <p>
          Each person's totals across every logged day: hours stood, nights, BUS runs and leave taken, plus a line
          per day logged. <b>Open on board</b> shows that day's sheet.
        </p>
        <p>
          An admin can set <b>opening hours</b>, for hours done before the app was in use, and <b>credit hours</b>{' '}
          to someone for a logged day they did not stand on the grid. These are the only numbers typed in directly;
          everything else is counted from the sheets.
        </p>
      </>
    ),
  },
  {
    id: 'team',
    title: 'Your team',
    roles: ['ADMIN'],
    body: (
      <>
        <ol>
          <li>
            On the <b>Team</b> tab, choose <b>USER</b> (sees the sheets) or <b>ADMIN</b> (runs the team) and press{' '}
            <b>Make link</b>.
          </li>
          <li>
            Copy the link and send it by chat. It is shown only once, works once, and expires after seven days.
            Links still waiting are listed, and can be revoked.
          </li>
          <li>Whoever opens it chooses their own username and password, and is signed straight in.</li>
        </ol>
        <p>
          Members can be made ADMIN or USER, or removed. A removed account is signed out at once; the roster and
          every sheet they logged stay as they were. A team always keeps at least one admin.
        </p>
      </>
    ),
  },
]

export function HelpView({ role }: { role: Role }) {
  const sections = SECTIONS.filter((s) => s.roles.includes(role))
  return (
    <section className="help" aria-label="Help">
      <div className="panel">
        <div className="panel-hd">
          <h2>How to use it</h2>
          <span className="meta">for {role === 'OWNER' ? 'the owner' : role === 'ADMIN' ? 'an admin' : 'a user'}</span>
        </div>
        {/* Buttons, not #links: the fragment is used for invite tokens. */}
        <nav className="help-toc" aria-label="Contents">
          {sections.map((s) => (
            <button
              key={s.id}
              type="button"
              className="sm"
              onClick={() => document.getElementById(`help-${s.id}`)?.scrollIntoView({ behavior: 'smooth' })}
            >
              {s.title}
            </button>
          ))}
        </nav>
      </div>
      {sections.map((s) => (
        <article key={s.id} id={`help-${s.id}`} className="panel">
          <div className="panel-hd">
            <h2>{s.title}</h2>
          </div>
          <div className="help-body">{s.body}</div>
        </article>
      ))}
    </section>
  )
}
