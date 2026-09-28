// Whether a history channel that went QUIET without the ring's `0x50` end-of-history is really
// finished — the "half-night sync" (tester report 2026-09-28).
//
// WHAT HAPPENED. A Gen 3 FR05.011 ring held a whole night, yet each of four morning drains
// (07:36 → 08:27) exited `quietAfterPages` after 1–2 `0x4c` pages and ZERO `0x50`s, delivering
// 9 / 3 / 3 / 8 records whose timestamps march forward (03:11–03:31, 03:51–03:56, 03:58–04:03,
// 04:06–04:23). Every drain classified `.complete`, so the night was staged from what had arrived
// and the card read "woke at ~4 am". The same morning another FR05.011 ring streamed 22 pages in
// 6 s and ENDED ON `0x50`. So "quiet for 3 s" is not "done" on every ring — only the `0x50` is a
// ring-side statement, and even that is not proof the ring is empty (`endmarker-not-ring-empty`).
//
// THE RULE. Keep asking while the ring keeps giving, and stop the moment it either says it is done
// (`0x50`) or answers a re-ask with nothing:
//   1. NUDGE — at the quiet exit, a channel that has streamed pages but sent no `0x50` gets one more
//      `07 00 00` fetch and another quiet window. Pages resuming resets the allowance.
//   2. REOPEN — a channel that still ends quiet-without-`0x50` but ADDED records is reopened in the
//      same sync. Each reopen is the same thing the next periodic drain would do minutes later
//      (and that the tester's drains demonstrably answered), just without the wait.
// Both only ever LENGTHEN a drain that is still yielding data; neither changes an `0x50` exit, an
// empty channel, or the commit gate. Pure so the stop conditions are asserted by tests.

import Foundation

public enum DrainContinuation {

    /// Fetch nudges allowed in a row with NO new page in between. One: a ring that is waiting to be
    /// asked answers the first ask; a ring that is genuinely done costs one extra quiet window.
    public static let maxNudgesWithoutProgress = 1

    /// Reopen rounds per channel per sync. POLICY BOUNDS, not measurements. Foreground: at the
    /// tester's observed 3–9 records per round, 12 rounds cover ~1.5–4.5 h of backlog in about two
    /// minutes of wall time, and a round that adds nothing stops it earlier. Background: iOS gives
    /// ~30 s, so at most 2 extra rounds and only with time left (`minBackgroundSecondsForReopen`);
    /// whatever is left is resumed by the next wake exactly as before.
    public static let maxReopenRoundsForeground = 12
    public static let maxReopenRoundsBackground = 2
    public static let minBackgroundSecondsForReopen: TimeInterval = 15

    /// At the quiet exit: send another fetch instead of ending the channel?
    public static func shouldNudge(sawPages: Bool, sawEndMarker: Bool,
                                   nudgesWithoutProgress: Int,
                                   maxNudgesWithoutProgress: Int = maxNudgesWithoutProgress) -> Bool {
        sawPages && !sawEndMarker && nudgesWithoutProgress < maxNudgesWithoutProgress
    }

    /// After a channel returns: open the SAME channel again in this sync?
    ///
    /// - Parameters:
    ///   - exitReason: how the round ended. Only `.quietAfterPages` qualifies — an `0x50`
    ///     (`.endMarker`) is the ring saying it is done, and every other reason means nothing came
    ///     or the link/task is going away.
    ///   - recordsAdded: records THIS round added. Zero means the ring had nothing more.
    ///   - round: reopen rounds already performed for this channel in this sync.
    ///   - inBackground / backgroundSecondsRemaining: the bounded-window guard.
    public static func shouldReopen(exitReason: HistoryChannelExitReason?,
                                    recordsAdded: Int,
                                    round: Int,
                                    inBackground: Bool,
                                    backgroundSecondsRemaining: TimeInterval) -> Bool {
        guard exitReason == .quietAfterPages, recordsAdded > 0 else { return false }
        if inBackground {
            return round < maxReopenRoundsBackground
                && backgroundSecondsRemaining >= minBackgroundSecondsForReopen
        }
        return round < maxReopenRoundsForeground
    }
}
