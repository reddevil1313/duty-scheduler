import { useEffect, useState } from 'react'

/** System follows the browser; light and dark are a stated choice. */
export type Theme = 'system' | 'light' | 'dark'

const KEY = 'duty-scheduler.theme'
const NEXT: Record<Theme, Theme> = { system: 'light', light: 'dark', dark: 'system' }

/**
 * The theme, kept on <html> as data-theme and remembered in this browser.
 * Storage can be refused (a private window, blocked site data); the page then
 * just follows the system each visit.
 */
export function useTheme() {
  const [theme, setTheme] = useState<Theme>(() => {
    try {
      const saved = localStorage.getItem(KEY)
      return saved === 'light' || saved === 'dark' ? saved : 'system'
    } catch {
      return 'system'
    }
  })

  useEffect(() => {
    const root = document.documentElement
    if (theme === 'system') root.removeAttribute('data-theme')
    else root.setAttribute('data-theme', theme)
    try {
      if (theme === 'system') localStorage.removeItem(KEY)
      else localStorage.setItem(KEY, theme)
    } catch {
      // Not remembered; nothing else depends on it.
    }
  }, [theme])

  return { theme, cycle: () => setTheme((t) => NEXT[t]) }
}
