package com.meatsack.motivator.escalation

/**
 * Tracks active-hours window membership across polls and reports the moment the app crosses
 * from outside the window to inside it. The service uses that edge to rebaseline the idle
 * clock + escalation state so the morning ramp starts fresh (root cause A in
 * docs/debug/triggering-investigation.md).
 *
 * The first in-window poll counts as a re-entry (starting state is "outside"), which is
 * harmless — a fresh start rebaselines an already-fresh baseline.
 */
class WindowReentryDetector {
    private var wasInside = false

    /** Returns true exactly on an outside → inside transition. */
    fun onPoll(inWindow: Boolean): Boolean {
        val reentered = inWindow && !wasInside
        wasInside = inWindow
        return reentered
    }
}
