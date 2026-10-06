import { useState } from 'react'
import {
  applyTheme,
  currentTheme,
  storeTheme,
  type Theme,
} from '../state/theme'

/**
 * Switches between the two themes.
 *
 * <p>The button names the theme in force rather than the one it would give you, so it reads
 * the same on every screen and a glance at it is an answer to "which am I in?" — the
 * accessible name is the opposite, because that is what the control *does*.
 *
 * <p>It lives in each screen's own header rather than pinned over the page: a fixed corner
 * control would sit on top of `.game__controls` on the board, and the game header is
 * already the width a phone is most likely to disagree with.
 */
export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>(currentTheme)
  const next: Theme = theme === 'dark' ? 'light' : 'dark'

  return (
    <button
      type="button"
      className="theme-toggle"
      aria-label={`Switch to the ${next} theme`}
      aria-pressed={theme === 'light'}
      title={`Switch to the ${next} theme`}
      onClick={() => {
        applyTheme(next)
        storeTheme(next)
        setTheme(next)
      }}
    >
      <span className="theme-toggle__glyph" aria-hidden="true">
        {theme === 'dark' ? '☾' : '☀'}
      </span>
      {theme === 'dark' ? 'Dark' : 'Light'}
    </button>
  )
}