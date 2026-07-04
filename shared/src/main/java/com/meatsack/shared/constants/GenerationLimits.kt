package com.meatsack.shared.constants

/**
 * Tunables for vote-aware, multi-level insult generation and library retention.
 * Hardcoded for v1 (see docs/superpowers/specs/2026-07-03-vote-aware-generation-design.md);
 * promote to Settings later if they need per-user tuning.
 */
object GenerationLimits {
    /** Insults requested per level, per "Generate" press (4 levels => 20 total). */
    const val INSULTS_PER_LEVEL = 5

    /** Max positive (loved) style exemplars injected into a prompt. */
    const val LOVED_EXAMPLES = 5

    /** Max negative (hated) "avoid this voice" exemplars injected into a prompt. */
    const val HATED_EXAMPLES = 3

    /** Soft max messages kept per (level, tone, trigger) bucket; surplus non-loved rows are pruned. */
    const val BUCKET_CAP = 50

    /** Min fireable (votesDown < 3) rows kept per bucket; pruning never drops below this. */
    const val BUCKET_FLOOR = 5
}
