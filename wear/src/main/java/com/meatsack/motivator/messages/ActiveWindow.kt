package com.meatsack.motivator.messages

/**
 * Windowing predicate for hour-of-day ranges. A window is [start, end): the
 * start hour is inside, the end hour is outside. Supports overnight windows
 * where start > end (e.g. 22..6). start == end is an empty window.
 *
 * Pure (no Android types) so it is unit-testable and shared by both the
 * inactivity active-hours gate and ToneResolver's work-safe-hours check.
 */
object ActiveWindow {
    fun contains(hour: Int, start: Int, end: Int): Boolean =
        if (start <= end) hour in start until end else hour >= start || hour < end
}
