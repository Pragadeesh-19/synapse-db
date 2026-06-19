package com.synapsedb.core;

/**
 * Pure stateless Hebbian scoring function. No array access, no graph dependency.
 *
 * <p>Formula: {@code successScore × salienceScore × exp(-λ × ΔTime) × sessionBoost}
 *
 * <p>ΔTime uses smooth {@code double} division so sub-unit recency provides meaningful
 * discrimination. Clamped to ≥ 0 so a skewed/future timestamp never amplifies the score.
 */
public final class HebbianScorer {

    private HebbianScorer() {}

    /**
     * Score one candidate thought.
     *
     * @param now  current epoch-millis; must be read once per query by the caller and passed in
     *             so all children are scored against the same instant
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
