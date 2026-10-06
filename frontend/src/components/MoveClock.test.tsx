import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MoveClock } from './MoveClock'
import { secondsLeft, shareLeft } from '../clock'
import { makeGame } from '../test/factories'
import type { GameState } from '../types'

/** A live turn with the clock set to whatever the test is about. */
function onTheClock(seconds: number, overrides: Partial<GameState> = {}): GameState {
  return makeGame([], { turnSeconds: seconds, turnDeadlineMillis: Date.now() + seconds * 1000, ...overrides })
}

function timer(): HTMLElement {
  return screen.getByRole('timer')
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe('the turn clock', () => {
  it('shows the seconds the server says are left', () => {
    render(<MoveClock game={onTheClock(60)} />)
    expect(timer()).toHaveTextContent('60s')
  })

  it('counts down as the deadline approaches', () => {
    render(<MoveClock game={onTheClock(60)} />)
    act(() => vi.advanceTimersByTime(30_000))
    expect(timer()).toHaveTextContent('30s')
    act(() => vi.advanceTimersByTime(29_400))
    expect(timer()).toHaveTextContent('1s')
  })

  it('empties the bar in step with the number', () => {
    render(<MoveClock game={onTheClock(60)} />)
    const fill = () => timer().querySelector<HTMLElement>('.clock__fill')!.style.width
    expect(fill()).toBe('100%')
    act(() => vi.advanceTimersByTime(15_000))
    expect(fill()).toBe('75%')
    act(() => vi.advanceTimersByTime(45_000))
    expect(fill()).toBe('0%')
  })

  it('warns when the move is nearly out and says so when it is', () => {
    render(<MoveClock game={onTheClock(60)} />)
    expect(timer()).toHaveClass('clock--calm')

    act(() => vi.advanceTimersByTime(50_500))
    expect(timer()).toHaveClass('clock--warn')
    expect(timer()).toHaveTextContent('10s')

    act(() => vi.advanceTimersByTime(10_000))
    expect(timer()).toHaveClass('clock--expired')
    expect(timer()).toHaveTextContent('0s')
  })

  it('never shows a negative number, however late the tick is', () => {
    render(<MoveClock game={onTheClock(1)} />)
    act(() => vi.advanceTimersByTime(90_000))
    expect(timer()).toHaveTextContent('0s')
  })

  it('says whose move it is counting down', () => {
    const { rerender } = render(<MoveClock game={onTheClock(60)} />)
    expect(timer()).toHaveTextContent('Time left on your move')

    rerender(<MoveClock game={onTheClock(60, { currentPlayer: 'BLUE' })} />)
    expect(timer()).toHaveTextContent('Time left on their move')
  })

  it('does not announce itself once a second', () => {
    render(<MoveClock game={onTheClock(60)} />)
    // a live region nobody can listen to is worse than no live region
    expect(timer()).toHaveAttribute('aria-live', 'off')
  })

  it('is not there when nothing is being timed', () => {
    const { container, rerender } = render(<MoveClock game={onTheClock(60, { turnDeadlineMillis: null })} />)
    expect(container).toBeEmptyDOMElement()

    // a stale deadline on a finished game must not leave a clock counting at zero
    rerender(<MoveClock game={onTheClock(60, { status: 'FINISHED' })} />)
    expect(container).toBeEmptyDOMElement()

    // nor during deployment, before anybody has the move
    rerender(<MoveClock game={onTheClock(60, { status: 'PLACEMENT' })} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('starts again from the new deadline when the turn changes', () => {
    const { rerender } = render(<MoveClock game={onTheClock(60)} />)
    act(() => vi.advanceTimersByTime(59_000))
    expect(timer()).toHaveTextContent('1s')

    rerender(<MoveClock game={onTheClock(60, { currentPlayer: 'BLUE' })} />)
    expect(timer()).toHaveTextContent('60s')
  })
})

describe('secondsLeft', () => {
  it('rounds up, so a turn reads 1s for the whole of its last second', () => {
    expect(secondsLeft(10_000, 0)).toBe(10)
    expect(secondsLeft(10_000, 1)).toBe(10)
    expect(secondsLeft(10_000, 9_001)).toBe(1)
    expect(secondsLeft(10_000, 10_000)).toBe(0)
  })

  it('stops at zero rather than counting into debt', () => {
    expect(secondsLeft(10_000, 30_000)).toBe(0)
  })
})

describe('shareLeft', () => {
  it('is the share of the turn still to run', () => {
    expect(shareLeft(60, 60)).toBe(100)
    expect(shareLeft(45, 60)).toBe(75)
    expect(shareLeft(0, 60)).toBe(0)
  })

  it('stays inside the bar when the browser clock disagrees with the server', () => {
    expect(shareLeft(90, 60)).toBe(100)
    expect(shareLeft(-5, 60)).toBe(0)
    expect(shareLeft(60, 0)).toBe(0)
  })
})