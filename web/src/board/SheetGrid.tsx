import { useEffect, useState, type MouseEvent } from 'react'
import type { Post, Schedule, ScheduleRow, SheetCheck } from '../api/types'
import { coverage } from './edit'
import { SLOT_COUNT, isCrossover, isSilent, labelAt } from './slots'

export type Mode = 'view' | 'edit' | 'swap'

/** A roster Trooper with no row on the sheet: shown as an empty row while editing, so they can be put on. */
export interface SpareTrooper {
  name: string
  stayOut: boolean
}

interface Props {
  sheet: Schedule
  /** The latest check of exactly this sheet, or null while one is on its way. */
  check: SheetCheck | null
  mode: Mode
  spare: SpareTrooper[]
  /** The first Trooper picked for a swap, waiting for the second. */
  swapPick: string | null
  onSetCell: (trooper: string, stayOut: boolean, slot: number, post: Post | null) => void
  onPickRow: (trooper: string) => void
}

interface Menu {
  trooper: string
  stayOut: boolean
  slot: number
  x: number
  y: number
}

const POSTS: Post[] = ['AP', 'GG', 'PAC', 'BUS']

// The menu's size in pixels, for placing it on screen before it renders: one
// row per post, plus the heading and "Off duty", plus padding. Kept in step
// with .cell-menu in index.css; erring large only places it a little higher.
const MENU_ROW = 32
const MENU_PAD = 12
const MENU_WIDTH = 160

export function SheetGrid({ sheet, check, mode, spare, swapPick, onSetCell, onPickRow }: Props) {
  const [menu, setMenu] = useState<Menu | null>(null)

  // A click elsewhere, Escape, or a resize closes the menu. A scroll does not:
  // the menu is placed in page coordinates and scrolls with its cell. Closing
  // on scroll was tried first, and lost the menu to the scroll that the click
  // itself had set off.
  useEffect(() => {
    if (!menu) return
    const close = () => setMenu(null)
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close() }
    window.addEventListener('click', close)
    window.addEventListener('resize', close)
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('click', close)
      window.removeEventListener('resize', close)
      window.removeEventListener('keydown', onKey)
    }
  }, [menu])

  // Leaving edit mode closes the menu: derived from the mode rather than an
  // effect that resets state.
  const openMenu = mode === 'edit' ? menu : null

  const rows: ScheduleRow[] = mode === 'edit'
    ? [...sheet.rows, ...spare.map((s) => ({
        trooper: s.name, stayOut: s.stayOut, posts: Array(SLOT_COUNT).fill(null), hours: 0, span: 0,
      }))]
    : sheet.rows

  // Hours and span as the server worked them out for this sheet; until it
  // answers, the local count of hours, and no span.
  const checked = new Map((check?.sheet.rows ?? []).map((r) => [r.trooper, r]))
  const cover = check ? coverage(sheet, check.demand) : null

  // Cells a rule names by Trooper and hour get outlined, so the eye goes
  // straight to the hour a message is about.
  const flagged = new Set((check?.issues ?? [])
    .filter((i) => i.trooper !== null && i.slot !== null)
    .map((i) => `${i.trooper}@${i.slot}`))

  const clickCell = (e: MouseEvent<HTMLDivElement>, row: ScheduleRow, slot: number) => {
    if (mode !== 'edit') return
    e.stopPropagation()
    const r = e.currentTarget.getBoundingClientRect()
    // Opened where it can be read whole: above the cell when the window has no
    // room below it, and inside the right edge. Then turned into page
    // coordinates, so it stays beside its cell as the page scrolls.
    const height = MENU_ROW * (offeredAt(slot).length + 2) + MENU_PAD
    const below = r.bottom + 3
    const y = below + height > window.innerHeight ? Math.max(4, r.top - height - 3) : below
    const x = Math.min(r.left, window.innerWidth - MENU_WIDTH - 8)
    setMenu({ trooper: row.trooper, stayOut: row.stayOut, slot, x: x + window.scrollX, y: y + window.scrollY })
  }

  const choose = (post: Post | null) => {
    if (!openMenu) return
    onSetCell(openMenu.trooper, openMenu.stayOut, openMenu.slot, post)
    setMenu(null)
  }

  // Only the posts that stand at that hour are offered, from the server's
  // demand. Before the first check lands, all four.
  const offeredAt = (slot: number): Post[] =>
    check ? POSTS.filter((p) => (check.demand[slot]?.[p] ?? 0) > 0) : POSTS
  const offered = openMenu ? offeredAt(openMenu.slot) : []

  return (
    <>
      <div
        className={`grid${mode === 'edit' ? ' editing' : ''}${mode === 'swap' ? ' swapping' : ''}`}
        role="table"
        aria-label={`Duty sheet for ${sheet.date}`}
      >
        <div className="row head" role="row">
          <div className="name" role="columnheader">Trooper</div>
          {Array.from({ length: SLOT_COUNT }, (_, slot) => (
            <div
              key={slot}
              role="columnheader"
              className={isSilent(slot) ? 'hour silent' : isCrossover(slot) ? 'hour cross' : 'hour'}
            >
              {labelAt(slot).slice(0, 2)}
            </div>
          ))}
          <div className="tally" role="columnheader">Hrs</div>
          <div className="tally" role="columnheader">Span</div>
        </div>

        {rows.map((row) => {
          const server = checked.get(row.trooper)
          const isSpare = !sheet.rows.some((r) => r.trooper === row.trooper)
          const nameClass = `name${mode === 'swap' && !isSpare ? ' pickable' : ''}` +
            `${swapPick === row.trooper ? ' picked' : ''}`
          return (
            <div className={`row${isSpare ? ' spare' : ''}`} role="row" key={row.trooper}>
              <div
                className={nameClass}
                role="rowheader"
                onClick={mode === 'swap' && !isSpare ? () => onPickRow(row.trooper) : undefined}
              >
                {row.trooper}
                {row.stayOut && <span className="tag">SO</span>}
              </div>
              {row.posts.map((post, slot) => (
                <div
                  // The index is the slot, and a slot is a fixed position on
                  // the board — it is not a list that reorders, so it is a
                  // legitimate key rather than the usual index-as-key mistake.
                  key={slot}
                  role="cell"
                  className={
                    `cell${post ? ` on ${post.toLowerCase()}` : ''}` +
                    `${isSilent(slot) ? ' silent' : ''}` +
                    `${flagged.has(`${row.trooper}@${slot}`) ? ' flag' : ''}`
                  }
                  title={`${labelAt(slot)} — ${post ?? 'off'}`}
                  onClick={(e) => clickCell(e, row, slot)}
                >
                  {post ?? ''}
                </div>
              ))}
              <div className="tally" role="cell">{server?.hours ?? row.hours}</div>
              <div className="tally" role="cell">{server ? server.span : '…'}</div>
            </div>
          )
        })}

        {cover && (
          <div className="row cover" role="row">
            <div className="name" role="rowheader">Manned</div>
            {cover.map((c, slot) => (
              <div
                key={slot}
                role="cell"
                className={`cell cov ${c.state}`}
                title={`${labelAt(slot)} — ${c.manned} of ${c.needed}`}
              >
                {c.state === 'none' ? '' : c.manned}
              </div>
            ))}
            <div className="tally" role="cell" />
            <div className="tally" role="cell" />
          </div>
        )}
      </div>

      {openMenu && (
        <div
          className="cell-menu"
          role="menu"
          style={{ left: openMenu.x, top: openMenu.y }}
          onClick={(e) => e.stopPropagation()}
        >
          <div className="menu-hd">{labelAt(openMenu.slot)} · {openMenu.trooper}</div>
          {offered.length === 0 && <div className="menu-none">No post stands at this hour.</div>}
          {offered.map((p) => (
            <button key={p} type="button" role="menuitem" onClick={() => choose(p)}>
              <span className={`dot on ${p.toLowerCase()}`} />{p}
            </button>
          ))}
          <button type="button" role="menuitem" onClick={() => choose(null)}>
            <span className="dot off" />Off duty
          </button>
        </div>
      )}
    </>
  )
}
