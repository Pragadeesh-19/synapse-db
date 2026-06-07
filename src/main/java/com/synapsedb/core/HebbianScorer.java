package com.synapsedb.core;

/**
 * Pure stateless Hebbian scoring function. No array access, no graph dependency.
 *
 * <p>Formula: {@code successScore × salienceScore × exp(-λ × ΔTime) × sessionBoost}
 *
 * <p>ΔTime uses smooth double division (not long) so sub-unit recency provides
 * meaningful discrimination. Clamped to ≥ 0 so a restored/future timestamp never
 * produces exp(positive) > 1 amplification (Phase 3 eng-review D3).
 */
public final class HebbianScorer {

    private HebbianScorer() {}

    /**
     * Score one candidate thought.
     *
     * @param successScore    reinforcement signal (array field)
     * @param salienceScore   accumulated Hebbian weight (array field)
     * @param timestamp       epoch-millis when the thought was written
     * @param sessionId       session that created the thought
     * @param now             current epoch-millis (read ONCE per query, passed in)
     * @param currentSessionId session currently active (for boost)
     * @param lambda          decay rate (config.lambda())
     * @param decayUnitMs     one decay unit in millis (config.decayUnitMs())
     */
    public static float score(float successScore, float salienceScore,
                              long timestamp, int sessionId,
                              long now, int currentSessionId,
                              float lambda, long decayUnitMs) {
        double deltaTime = Math.max(0.0, (now - timestamp) / (double) decayUnitMs);
        double decay = Math.exp(-lambda * deltaTime);
        float sessionBoost = (sessionId == currentSessionId) ? 2.0f : 1.0f;
        return (float) (successScore * salienceScore * decay * sessionBoost);
    }
}
