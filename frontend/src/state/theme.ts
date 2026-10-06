/**
 * Which way round the board is drawn.
 *
 * <p>The theme is one attribute on `<html>` and nothing else: the CSS in `styles.css`
 * defines every colour as a custom property and `[data-theme='light']` replaces the set.
 * So there is no second copy of the palette in TypeScript and nothing to keep in step — the
 * choice here is only which value the attribute holds and where it is remembered.
 *
 * <p>The attribute is applied by `installTheme()` in `main.tsx`, before React renders.
 * That ordering is the whole reason there is no flash of the wrong theme: the built
 * stylesheet is a `<link>` in `<head>` and the module that runs this is deferred to the
 * end of `<body>`, so the first paint already sees the right values. A test renders
 * components without `main.tsx`, so `currentTheme()` reads the attribute back rather than
 * keeping a second source of truth that could disagree with the DOM.
 */
export type Theme = 'light' | 'dark'

export const THEME_STORAGE_KEY = 'generals.theme'

function isTheme(value: unknown): value is Theme {
  return value === 'light' || value === 'dark'
}

/** The reader's own choice, or null if they have never picked one. */
export function readStoredTheme(): Theme | null {
  try {
    const raw = window.localStorage.getItem(THEME_STORAGE_KEY)
    return isTheme(raw) ? raw : null
  } catch {
    // private browsing, or a store this browser refuses to open: fall back to the OS
    return null
  }
}

export function storeTheme(theme: Theme) {
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, theme)
  } catch {
    // nothing to do; the theme still applies for this tab
  }
}

/** What the operating system asks for. Dark when it cannot be asked. */
export function systemTheme(): Theme {
  if (typeof window.matchMedia !== 'function') return 'dark'
  return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
}

/** The reader's choice, or the operating system's if they have not made one. */
export function preferredTheme(): Theme {
  return readStoredTheme() ?? systemTheme()
}

export function applyTheme(theme: Theme) {
  document.documentElement.dataset.theme = theme
}

/** The theme actually in force, read back off the document. */
export function currentTheme(): Theme {
  return isTheme(document.documentElement.dataset.theme)
    ? document.documentElement.dataset.theme
    : systemTheme()
}

/**
 * Puts the theme on the page and keeps following the system until the reader picks a side.
 *
 * <p>Called once from `main.tsx`. The listener is what makes the page follow a machine
 * switched from dark to light at sunset, but it is detached as soon as the reader picks a
 * theme — an explicit choice outranks the OS, or toggling back would undo the choice.
 */
export function installTheme() {
  applyTheme(preferredTheme())
  if (readStoredTheme() !== null) return
  if (typeof window.matchMedia !== 'function') return
  const query = window.matchMedia('(prefers-color-scheme: light)')
  const onChange = (event: MediaQueryListEvent) => applyTheme(event.matches ? 'light' : 'dark')
  if (typeof query.addEventListener === 'function') {
    query.addEventListener('change', onChange)
  } else if (typeof query.addListener === 'function') {
    // Safari before 14, and anything else still on the deprecated API
    query.addListener(onChange)
  }
}