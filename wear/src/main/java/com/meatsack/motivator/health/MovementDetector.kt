package com.meatsack.motivator.health

/**
 * Android-free movement / idle detection, extracted from [HealthTracker] so it can be
 * unit-tested on the plain JVM. It consumes the cumulative STEPS_DAILY total via
 * [onStepTotal] and reads the user's step threshold and window length through provider
 * lambdas, so a settings change synced from the phone takes effect on the next step
 * update with no re-wiring.
 *
 * The window is *tumbling* (a fixed box reset at each boundary), matching the prior
 * behavior — steps in an expired window are discarded rather than rolled forward.
 *
 * [now] is injectable so tests can drive time deterministically; production uses the
 * system clock.
 */
class MovementDetector(
    private val stepThresholdProvider: () -> Int,
    private val windowMinutesProvider: () -> Int,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var lastMovementTimestamp: Long = now()
    private var windowStartTimestamp: Long = now()
    private var stepsInCurrentWindow: Int = 0

    // Baseline for turning the cumulative STEPS_DAILY total into per-update deltas.
    // <0 means "no reading yet"; it is re-baselined (delta 0) on the first reading and
    // on a daily rollover (when the cumulative count drops at midnight).
    private var lastStepTotal: Int = -1

    @Synchronized
    fun onStepTotal(currentTotal: Int) {
        val nowMs = now()
        // coerceAtLeast(1): a 0/negative window (malformed sync or corrupted prefs —
        // inactivityThreshold is not floored on the wire) would tumble on every callback
        // and silently break accumulation. Floor it locally so the invariant holds
        // regardless of caller.
        val windowMs = windowMinutesProvider().coerceAtLeast(1).toLong() * 60_000L

        val delta = when {
            lastStepTotal < 0 -> 0 // first reading: set baseline, count nothing
            currentTotal < lastStepTotal -> 0 // midnight rollover: re-baseline, count nothing
            else -> currentTotal - lastStepTotal
        }
        lastStepTotal = currentTotal

        if (nowMs - windowStartTimestamp > windowMs) {
            stepsInCurrentWindow = 0
            windowStartTimestamp = nowMs
        }
        stepsInCurrentWindow += delta

        if (stepsInCurrentWindow >= stepThresholdProvider()) {
            lastMovementTimestamp = nowMs
            stepsInCurrentWindow = 0
            windowStartTimestamp = nowMs
        }
    }

    @Synchronized
    fun minutesSinceLastMovement(): Int =
        ((now() - lastMovementTimestamp) / 60_000L).toInt()

    @Synchronized
    fun hasSignificantMovement(): Boolean =
        stepsInCurrentWindow >= stepThresholdProvider()

    /**
     * Reset the idle clock and the current step window to "now", leaving the cumulative
     * [lastStepTotal] baseline intact so later deltas stay correct. Called on active-window
     * re-entry so the day's inactivity ramp starts fresh instead of inheriting the overnight
     * accumulation that fired level 4 at 7am (docs/debug/triggering-investigation.md, root cause A).
     */
    @Synchronized
    fun rebaseline() {
        val nowMs = now()
        lastMovementTimestamp = nowMs
        windowStartTimestamp = nowMs
        stepsInCurrentWindow = 0
    }

    /**
     * Read-only view of the detector's otherwise-private state, for the on-device
     * diagnostics dev tool (docs/debug/triggering-investigation.md). Computed under the
     * same lock as the rest of the class so the snapshot is internally consistent.
     */
    @Synchronized
    fun debugSnapshot(): MovementSnapshot {
        val nowMs = now()
        return MovementSnapshot(
            minutesSinceLastMovement = ((nowMs - lastMovementTimestamp) / 60_000L).toInt(),
            stepsInCurrentWindow = stepsInCurrentWindow,
            windowAgeMs = nowMs - windowStartTimestamp,
            lastStepTotal = lastStepTotal,
        )
    }
}

/** Snapshot of [MovementDetector] internals for diagnostics logging. */
data class MovementSnapshot(
    val minutesSinceLastMovement: Int,
    val stepsInCurrentWindow: Int,
    val windowAgeMs: Long,
    val lastStepTotal: Int,
)
