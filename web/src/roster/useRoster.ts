import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { TickKind, Ticks, Trooper } from '../api/types'

/**
 * The roster, the night group and the roster ticks, held once for the whole
 * app. The Roster tab changes them, the board generates from them and the
 * leave register shows the ticks, so none of the three keeps its own copy.
 *
 * <p>Ticks are browser state only. They are sent with each generate and check
 * and never saved; signing out drops them, because the app remounts this per
 * signed-in user.
 */
export function useRoster(report: (e: unknown) => Promise<void>) {
  const [roster, setRoster] = useState<Trooper[]>([])
  const [nightGroup, setNightGroup] = useState<string[]>([])
  const [ticks, setTicks] = useState<Ticks>({})

  useEffect(() => {
    let cancelled = false
    api.troopers()
      .then((list) => {
        if (cancelled) return
        setRoster(list)
        setNightGroup(list.filter((t) => t.onNight).map((t) => t.name))
      })
      .catch((e: unknown) => { if (!cancelled) void report(e) })
    return () => { cancelled = true }
  }, [report])

  const added = (trooper: Trooper) => {
    setRoster((current) => [...current, trooper])
    if (trooper.onNight) setNightGroup((current) => [...current, trooper.name])
  }

  const updated = (before: Trooper, after: Trooper) => {
    setRoster((current) => current.map((t) => (t.id === after.id ? after : t)))
    // Night group and ticks are held by name, so a rename carries them across,
    // and someone who has become a stay-out can no longer hold the night.
    setNightGroup((current) => {
      const without = current.filter((n) => n !== before.name)
      const wasTicked = without.length !== current.length
      return wasTicked && !after.stayOut ? [...without, after.name] : without
    })
    setTicks((current) => {
      if (!(before.name in current) || before.name === after.name) return current
      const { [before.name]: kind, ...rest } = current
      return { ...rest, [after.name]: kind }
    })
  }

  const removed = (trooper: Trooper) => {
    setRoster((current) => current.filter((t) => t.id !== trooper.id))
    setNightGroup((current) => current.filter((n) => n !== trooper.name))
    setTicks((current) => {
      const { [trooper.name]: _gone, ...rest } = current
      return rest
    })
  }

  const toggleNight = (name: string) => {
    setNightGroup((current) =>
      current.includes(name) ? current.filter((n) => n !== name) : [...current, name])
  }

  /** One reason at a time: ticking MC clears an OL tick. Null unticks. */
  const tick = (name: string, kind: TickKind | null) => {
    setTicks((current) => {
      const { [name]: _old, ...rest } = current
      return kind ? { ...rest, [name]: kind } : rest
    })
  }

  return { roster, nightGroup, ticks, added, updated, removed, toggleNight, tick }
}

export type RosterState = ReturnType<typeof useRoster>
