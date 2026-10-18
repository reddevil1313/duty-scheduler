import { useEffect, useState, type MouseEvent } from 'react'
import type { Post, Schedule, ScheduleRow, SheetCheck } from '../api/types'
import { boardOrder, coverage, mpShortfall } from './edit'
import { shortDate } from '../dates'
import { SLOT_COUNT, dateAt, isCrossover, labelAt } from './slots'

export type Mode = 'view' | 'edit' | 'swap'

/** A roster Trooper with no row, shown empty while editing. */
export interface SpareTrooper {
  name: string
  stayOut: boolean
}

interface Props {
  sheet: Schedule
  /** The sheet before edits, for grouping rows. */
  orderBasis?: ScheduleRow[]
  /** Latest check, possibly of an earlier edit. Cell highlights wait for `fresh`. */
  check: SheetCheck | null
  /** Whether `check` was made of exactly this sheet. */
  fresh: boolean
  /** Fixed-turnout sheet: show the MP row. */
  mp: boolean
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

// Estimated menu size, for placing it before it renders. Matches .cell-menu.
const MENU_ROW = 32
const MENU_PAD = 12
const MENU_WIDTH = 160

/** Midnight, drawn with a heavier line. */
const MIDNIGHT = 4
const mid = (slot: number) => (slot === MIDNIGHT ? ' mid' : '')

export function SheetGrid({ sheet, check, fresh, mp, mode, spare, swapPick, onSetCell, onPickRow, orderBasis }: Props) {
  const [menu, setMenu] = useState<Menu | null>(null)

  // Close on outside click, Escape or resize. Not on scroll: the click itself
  // can scroll the page.
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

  const openMenu = mode === 'edit' ? menu : null

  // Grouped by the unedited sheet so rows don't move while editing.
  const rows: ScheduleRow[] = boardOrder(mode === 'edit'
    ? [...sheet.rows, ...spare.map((s) => ({
        trooper: s.name, stayOut: s.stayOut, posts: Array(SLOT_COUNT).fill(null), hours: 0, span: 0,
      }))]
    : sheet.rows, check?.away, orderBasis)

  const checked = new Map((check?.sheet.rows ?? []).map((r) => [r.trooper, r]))
  const cover = check ? coverage(sheet, check.demand) : null
  const mpPosts = mp && check ? mpShortfall(sheet, check.demand) : null

  // Outline cells named by an issue, from a fresh check only.
  const flagged = new Set((fresh ? check?.issues ?? [] : [])
    .filter((i) => i.trooper !== null && i.slot !== null)
    .map((i) => `${i.trooper}@${i.slot}`))

  const clickCell = (e: MouseEvent<HTMLDivElement>, row: ScheduleRow, slot: number) => {
    if (mode !== 'edit') return
    e.stopPropagation()
    const r = e.currentTarget.getBoundingClientRect()
    // Keep it on screen; page coordinates so it scrolls with the cell.
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

  // Only posts that stand at that hour; all four before the first check.
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
        <div className="row days" aria-hidden="true">
          <div className="eve">{shortDate(sheet.date)}</div>
          <div className="morn">{shortDate(dateAt(sheet.date, MIDNIGHT))}</div>
          <div className="tot">Total</div>
        </div>
        <div className="row head" role="row">
          <div className="name" role="columnheader">Trooper</div>
          {Array.from({ length: SLOT_COUNT }, (_, slot) => (
            <div
              key={slot}
              role="columnheader"
              className={`hour${isCrossover(slot) ? ' cross' : ''}${mid(slot)}`}
              title={isCrossover(slot) ? `${labelAt(slot)} — a night Trooper may be extended into this hour` : undefined}
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
          const away = check?.away[row.trooper]
          // Away all sheet: one grey band, except while editing.
          const outAllSheet = !!away && away.every((k) => k !== null)
          const asBand = outAllSheet && mode !== 'edit'
          const nameClass = `name${mode === 'swap' && !isSpare && !asBand ? ' pickable' : ''}` +
            `${swapPick === row.trooper ? ' picked' : ''}`
          return (
            <div
              className={`row${isSpare ? ' spare' : ''}${outAllSheet ? ' out' : ''}`}
              role="row"
              key={row.trooper}
            >
              <div
                className={nameClass}
                role="rowheader"
                onClick={mode === 'swap' && !isSpare && !asBand ? () => onPickRow(row.trooper) : undefined}
              >
                <span className="nm" title={row.trooper}>{row.trooper}</span>
                {row.stayOut && <span className="tag">SO</span>}
              </div>
              {asBand ? (
                <div className="band" role="cell">
                  <span className="why">{away[0] === 'SO' ? 'Not in' : away[0]}</span>
                </div>
              ) : row.posts.map((post, slot) => (
                <div
                  // The index is the slot, which never reorders.
                  key={slot}
                  role="cell"
                  className={
                    `cell${post ? ` on ${post.toLowerCase()}` : ''}${mid(slot)}` +
                    // One block per stint, labelled on its first hour.
                    `${post && row.posts[slot - 1] !== post ? ' s-start' : ''}` +
                    `${post && row.posts[slot + 1] !== post ? ' s-end' : ''}` +
                    `${away?.[slot] ? ' away' : ''}` +
                    `${flagged.has(`${row.trooper}@${slot}`) ? ' flag' : ''}`
                  }
                  title={`${labelAt(slot)} — ${post ?? 'off'}${away?.[slot] ? ` · ${away[slot]}` : ''}`}
                  onClick={(e) => clickCell(e, row, slot)}
                >
                  {post && (
                    <span className="blk">
                      {row.posts[slot - 1] === post ? '' : post}
                    </span>
                  )}
                </div>
              ))}
              {/* Span comes from the server, dimmed while stale. */}
              <div className="tally" role="cell">{row.hours}</div>
              <div className={`tally${fresh ? '' : ' stale'}`} role="cell">{server?.span ?? ''}</div>
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
                className={`cell cov ${c.state}${mid(slot)}`}
                title={`${labelAt(slot)} — ${c.manned} of ${c.needed}`}
              >
                {c.state === 'none' ? '' : c.manned}
              </div>
            ))}
            <div className="tally" role="cell" />
            <div className="tally" role="cell" />
          </div>
        )}

        {mpPosts && (
          <div className="row mp" role="row">
            <div className="name" role="rowheader">MP team</div>
            {mpPosts.map((posts, slot) => (
              <div
                key={slot}
                role="cell"
                className={`cell${posts.length ? ' on mp s-start s-end' : ''}${mid(slot)}`}
                title={`${labelAt(slot)} — ${posts.length ? `MP: ${posts.join(', ')}` : 'covered by the team'}`}
              >
                {/* One post fits a cell; two shows the first and a count. */}
                {posts.length > 0 && (
                  <span className="blk">{posts.length === 1 ? posts[0] : `${posts[0]}+${posts.length - 1}`}</span>
                )}
              </div>
            ))}
            <div className="tally" role="cell">{mpPosts.reduce((n, p) => n + p.length, 0) || ''}</div>
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
