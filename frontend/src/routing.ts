import { useCallback, useEffect, useState } from 'react'

/**
 * Which page the browser is on.
 *
 * <p>There is no router in this app — the server forwards every path to the same document
 * and the SPA decides. So the pathname is read from `window.location` and watched through
 * `popstate`, which is also what {@link navigate} fires by hand after a `pushState`, since
 * `pushState` itself raises nothing.
 */
export function usePathname(): string {
  const [pathname, setPathname] = useState(() => window.location.pathname)

  useEffect(() => {
    const sync = () => setPathname(window.location.pathname)
    window.addEventListener('popstate', sync)
    return () => window.removeEventListener('popstate', sync)
  }, [])

  return pathname
}

/** Moves to another page of the single-page app, keeping the browser's history honest. */
export function navigate(path: string) {
  window.history.pushState({}, '', path)
  window.dispatchEvent(new PopStateEvent('popstate'))
}

/**
 * A link that stays a real link: middle-click and ctrl-click still work, and the SPA
 * handles only a plain left click.
 */
export function useLinkHandler(path: string) {
  return useCallback(
    (event: React.MouseEvent<HTMLAnchorElement>) => {
      if (event.defaultPrevented || event.button !== 0) return
      if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
      event.preventDefault()
      navigate(path)
    },
    [path],
  )
}
