import { useEffect, useRef, useState } from 'react'
import { useGame } from '../state/GameContext'

/** Mirrors GameSession.MAX_CHAT_LENGTH, so the box refuses what the server would refuse. */
const MAX_LENGTH = 200

/**
 * The conversation between the two players.
 *
 * <p>Chat is for the things a player cannot say by moving: "are you going for my flag?",
 * "sorry, that was me", "one more?". It travels inside the game state, so it arrives on the
 * same socket push as the board and needs no connection of its own — and it survives a
 * refresh, because the state is re-fetched when the page comes back.
 *
 * <p>The author's name comes from the server's seat record rather than from anything typed
 * here, so nobody can speak as somebody else.
 */
export function ChatPanel() {
  const { game, sendChat, sendingChat, chatError, clearChatError } = useGame()
  const [draft, setDraft] = useState('')
  const listRef = useRef<HTMLOListElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  const messages = game?.chat ?? []
  const overLong = draft.length > MAX_LENGTH

  useEffect(() => {
    const list = listRef.current
    if (list) {
      list.scrollTop = list.scrollHeight
    }
  }, [messages.length])

  // Keep focus in the box after a line goes out, so a player can say several things
  // without reaching for the mouse. Guarded on there being something to focus.
  useEffect(() => {
    if (!sendingChat && !chatError) {
      inputRef.current?.focus()
    }
  }, [sendingChat, chatError])

  async function send(event: React.FormEvent) {
    event.preventDefault()
    const text = draft.trim()
    if (!text || overLong || sendingChat) return
    const accepted = await sendChat(text)
    // Only clear on success: a refused line stays in the box to be edited, because losing
    // what you typed to a validation error is how people stop using the thing.
    if (accepted) setDraft('')
  }

  if (!game) return null

  return (
    <section className="panel__section panel__section--chat">
      <h2 className="panel__title">Chat</h2>
      <ol className="chat" ref={listRef} aria-live="polite" aria-label="Conversation">
        {messages.length === 0 && <li className="chat__empty">Nothing said yet.</li>}
        {messages.map((message) => {
          const mine = message.color === game.youAre
          return (
            <li key={message.id} className={`chat__line ${mine ? 'chat__line--mine' : ''}`}>
              <span className={`chat__who chat__who--${message.color.toLowerCase()}`}>
                {mine ? 'You' : message.author}
              </span>
              <span className="chat__text">{message.text}</span>
            </li>
          )
        })}
      </ol>

      {chatError && (
        <p className="chat__error" role="alert">
          {chatError}
          <button
            type="button"
            className="chat__dismiss"
            onClick={clearChatError}
            aria-label="Dismiss"
          >
            ×
          </button>
        </p>
      )}

      <form className="chat__form" onSubmit={send}>
        <input
          ref={inputRef}
          className="chat__input"
          value={draft}
          onChange={(e) => {
            setDraft(e.target.value)
            if (chatError) clearChatError()
          }}
          placeholder="Say something"
          aria-label="Message"
          maxLength={MAX_LENGTH + 40}
          disabled={sendingChat}
        />
        <button
          type="submit"
          className="btn btn--ghost chat__send"
          disabled={sendingChat || !draft.trim() || overLong}
        >
          {sendingChat ? '…' : 'Send'}
        </button>
      </form>
      {overLong && (
        <p className="chat__hint" role="alert">
          {draft.length} characters — the limit is {MAX_LENGTH}.
        </p>
      )}
    </section>
  )
}