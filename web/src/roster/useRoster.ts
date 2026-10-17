import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { TickKind, Ticks, Trooper } from '../api/types'

/**
 * Roster, night group and ticks, shared by the Roster tab, the board and the
 * leave register. Ticks are never saved and reset on sign-out.
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
    setRoster((current) => [
      ...current.map((t) => (trooper.defaultBus ? { ...t, defaultBus: false } : t)),
      trooper,
    ])
    if (trooper.onNight) setNightGroup((current) => [...current, trooper.name])
  }

  const updated = (before: Trooper, after: Trooper) => {
    // Only one default BUS, as on the server.
    setRoster((current) => current.map((t) =>
      t.id === after.id ? after : after.defaultBus ? { ...t, defaultBus: false } : t))
    // Carry names across a rename; stay-outs leave the night group.
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

  /** One kind at a time. Null unticks. */
  const tick = (name: string, kind: TickKind | null) => {
    setTicks((current) => {
      const { [name]: _old, ...rest } = current
      return kind ? { ...rest, [name]: kind } : rest
    })
  }

  return { roster, nightGroup, ticks, added, updated, removed, toggleNight, tick }
}

export type RosterState = ReturnType<typeof useRoster>
