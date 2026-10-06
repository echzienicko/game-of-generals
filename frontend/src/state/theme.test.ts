import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  applyTheme,
  currentTheme,
  installTheme,
  preferredTheme,
  readStoredTheme,
  storeTheme,
  systemTheme,
  THEME_STORAGE_KEY,
} from './theme'

/** A matchMedia that answers "light" or "dark" and remembers its listeners. */
function stubSystem(prefersLight: boolean) {
  const listeners: ((event: MediaQueryListEvent) => void)[] = []
  const query = {
    matches: prefersLight,
    media: '(prefers-color-scheme: light)',
    addEventListener: (_: string, fn: (event: MediaQueryListEvent) => void) => {
      listeners.push(fn)
    },
  }
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => query),
  )
  return {
    /** Pretends the machine was switched at sunset. */
    flip(matches: boolean) {
      query.matches = matches
      listeners.forEach((fn) => fn({ matches } as MediaQueryListEvent))
    },
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
  // the setItem spy in one test would otherwise still be throwing into the next one
  vi.restoreAllMocks()
  localStorage.clear()
  delete document.documentElement.dataset.theme
})

describe('which theme a reader gets', () => {
  it('follows the operating system when they have never chosen', () => {
    stubSystem(true)
    expect(readStoredTheme()).toBeNull()
    expect(preferredTheme()).toBe('light')

    vi.unstubAllGlobals()
    stubSystem(false)
    expect(preferredTheme()).toBe('dark')
  })

  it('outranks the operating system once they have chosen', () => {
    stubSystem(true)
    storeTheme('dark')
    expect(readStoredTheme()).toBe('dark')
    expect(preferredTheme()).toBe('dark')
  })

  it('is dark on a machine that cannot be asked', () => {
    vi.stubGlobal('matchMedia', undefined)
    expect(systemTheme()).toBe('dark')
    expect(preferredTheme()).toBe('dark')
  })

  it('ignores a store holding something that is not a theme', () => {
    stubSystem(true)
    localStorage.setItem(THEME_STORAGE_KEY, 'chartreuse')
    expect(readStoredTheme()).toBeNull()
    expect(preferredTheme()).toBe('light')
  })

  it('still switches with the store unavailable', () => {
    stubSystem(false)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('private browsing')
    })
    expect(() => storeTheme('light')).not.toThrow()
    expect(readStoredTheme()).toBeNull()
  })
})

describe('putting the theme on the page', () => {
  it('sets the attribute the stylesheet keys off', () => {
    applyTheme('light')
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(currentTheme()).toBe('light')
  })

  it('reads the theme back off the document rather than keeping its own copy', () => {
    applyTheme('dark')
    // something else moved the page to light, and the toggle must not disagree with the DOM
    document.documentElement.dataset.theme = 'light'
    expect(currentTheme()).toBe('light')
  })

  it('falls back to the system when nothing has been applied yet', () => {
    stubSystem(true)
    delete document.documentElement.dataset.theme
    expect(currentTheme()).toBe('light')
  })

  it('applies the system theme on install and follows it as it changes', () => {
    const system = stubSystem(true)
    installTheme()
    expect(document.documentElement.dataset.theme).toBe('light')

    system.flip(false)
    expect(document.documentElement.dataset.theme).toBe('dark')
  })

  it('stops following the machine once the reader has picked a side', () => {
    storeTheme('light')
    const system = stubSystem(false)
    installTheme()
    expect(document.documentElement.dataset.theme).toBe('light')

    // the machine going dark must not undo a choice
    system.flip(false)
    expect(document.documentElement.dataset.theme).toBe('light')
  })

  it('does not throw on a browser with no matchMedia listener API', () => {
    vi.stubGlobal(
      'matchMedia',
      vi.fn(() => ({ matches: false, addListener: undefined, addEventListener: undefined })),
    )
    expect(() => installTheme()).not.toThrow()
    expect(document.documentElement.dataset.theme).toBe('dark')
  })
})