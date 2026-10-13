/** How often "Show all flights" polls the server. */
export const normalPollMs = 15_000
/** While the server is still filling in the area (`complete = false`): this often at first... */
export const fastPollMs = 10_000
/** ...then this often, after [[fastPolls]] fast polls... */
export const backoffPollMs = 30_000
export const fastPolls = 2
/** ...and not at all like this once the area has been incomplete for this long (a cell adsb.lol won't give). */
export const fastModeMaxMs = 60_000

/**
 * Remembers how long the current view has been incomplete and says when to poll next. `reset` when the view changes
 * (the map moved, the mode switched) so a new area gets its fast polls.
 */
export function createTrafficPoller() {
  let incompleteSince: number | undefined
  let incompletePolls = 0
  return {
    reset() {
      incompleteSince = undefined
      incompletePolls = 0
    },
    /** Milliseconds until the next poll, given the reply to the one just made, at time `now`. */
    next(complete: boolean, now: number): number {
      if (complete) {
        this.reset()
        return normalPollMs
      }
      incompleteSince ??= now
      incompletePolls++
      if (now - incompleteSince >= fastModeMaxMs) return normalPollMs
      return incompletePolls <= fastPolls ? fastPollMs : backoffPollMs
    }
  }
}
