package com.synapsedb.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HebbianScorerTest {

    // Shared constants for predictable, hand-calculable inputs.
    private static final float  LAMBDA       = 0.1f;
    private static final long   DECAY_MS     = 3_000L;  // small unit for easy math
    private static final int    CUR_SESSION  = 7;
    private static final int    OTHER_SESSION = 42;

    @Test
    @DisplayName("formula_knownInputs: exact value matches hand-calculated result")
    void formula_knownInputs() {
        // deltaTime = (10_000 - 4_000) / 3_000.0 = 2.0
        // decay     = exp(-0.1 * 2.0) = exp(-0.2) ≈ 0.81873
        // boost     = 2.0  (same session)
        // score     = 0.8 * 0.5 * 0.81873 * 2.0 ≈ 0.6550
        float score = HebbianScorer.score(
                0.8f, 0.5f,
                4_000L, CUR_SESSION,
                10_000L, CUR_SESSION,
                LAMBDA, DECAY_MS);

        double expected = 0.8 * 0.5 * Math.exp(-0.1 * 2.0) * 2.0;
        assertEquals((float) expected, score, 1e-5f, "score must match hand-calculated formula");
    }

    @Test
    @DisplayName("sessionBoost_applied: same session doubles the score")
    void sessionBoost_applied() {
        long ts  = 1_000L;
        long now = 4_000L;
        float base = HebbianScorer.score(0.8f, 0.5f, ts, OTHER_SESSION, now, CUR_SESSION,  LAMBDA, DECAY_MS);
        float boosted = HebbianScorer.score(0.8f, 0.5f, ts, CUR_SESSION,  now, CUR_SESSION, LAMBDA, DECAY_MS);
        assertEquals(2.0f * base, boosted, 1e-5f, "same-session score must be exactly 2× other-session score");
    }

    @Test
    @DisplayName("sessionBoost_absent: different session gives 1× score")
    void sessionBoost_absent() {
        long ts  = 1_000L;
        long now = 4_000L;
        float score = HebbianScorer.score(0.8f, 0.5f, ts, OTHER_SESSION, now, CUR_SESSION, LAMBDA, DECAY_MS);
        // Compute the expected value with boost=1.0
        double delta = (now - ts) / (double) DECAY_MS;
        double expected = 0.8 * 0.5 * Math.exp(-LAMBDA * delta) * 1.0;
        assertEquals((float) expected, score, 1e-5f, "different-session score must use boost=1.0");
    }

    @Test
    @DisplayName("subUnitDecay_isSmooth: half-unit recency produces factor strictly between 0 and 1 (not step)")
    void subUnitDecay_isSmooth() {
        long now = 10_000L;
        long ts  = now - DECAY_MS / 2;   // exactly half a decay unit ago → deltaTime = 0.5
        float score = HebbianScorer.score(1.0f, 1.0f, ts, CUR_SESSION, now, CUR_SESSION, LAMBDA, DECAY_MS);
        // With long division: deltaTime would be 0 → decay=1.0 → score = 2.0 (wrong)
        // With double division: decay = exp(-0.1*0.5) ≈ 0.9512 → score ≈ 1.902
        double decayFactor = Math.exp(-LAMBDA * 0.5);
        float expected = (float) (1.0 * 1.0 * decayFactor * 2.0);
        assertEquals(expected, score, 1e-5f, "sub-unit recency must use smooth double division");
        assertTrue(score > 0f && score < 2.0f, "decay factor must be strictly between 0 and 1");
    }

    @Test
    @DisplayName("futureTimestamp_clamped: future ts yields deltaTime=0, no amplification")
    void futureTimestamp_clamped() {
        long now = 10_000L;
        long futureTs = now + 100_000L;  // timestamp in the future (clock skew / restored)
        float score = HebbianScorer.score(0.8f, 0.5f, futureTs, CUR_SESSION, now, CUR_SESSION, LAMBDA, DECAY_MS);
        // Without clamp: deltaTime < 0 → exp(positive) > 1 → score > unclamped value
        // With clamp:    deltaTime = 0 → decay = 1.0
        float unclamped_max = 0.8f * 0.5f * 1.0f * 2.0f;
        assertEquals(unclamped_max, score, 1e-5f, "future timestamp must clamp deltaTime to 0 (no amplification)");
    }

    @Test
    @DisplayName("zeroLambda_noDecay: λ=0 means exp(0)=1 regardless of elapsed time")
    void zeroLambda_noDecay() {
        long now = 1_000_000L;
        long oldTs = 1L;   // written a long time ago
        float score = HebbianScorer.score(0.8f, 0.5f, oldTs, CUR_SESSION, now, CUR_SESSION, 0.0f, DECAY_MS);
        float expected = 0.8f * 0.5f * 1.0f * 2.0f;  // decay = exp(0) = 1.0
        assertEquals(expected, score, 1e-5f, "λ=0 must produce no temporal decay");
    }

    @Test
    @DisplayName("zeroFactor_zeroScore: zero success or salience drives score to zero")
    void zeroFactor_zeroScore() {
        long now = 5_000L;
        float zeroSuccess = HebbianScorer.score(0.0f, 0.5f, 1_000L, CUR_SESSION, now, CUR_SESSION, LAMBDA, DECAY_MS);
        float zeroSalience = HebbianScorer.score(0.8f, 0.0f, 1_000L, CUR_SESSION, now, CUR_SESSION, LAMBDA, DECAY_MS);
        assertEquals(0.0f, zeroSuccess,  1e-7f, "success=0 must yield score=0");
        assertEquals(0.0f, zeroSalience, 1e-7f, "salience=0 must yield score=0");
    }

    @Test
    @DisplayName("negativeSuccess_negScore: negative reinforcement yields negative score")
    void negativeSuccess_negScore() {
        long now = 5_000L;
        float score = HebbianScorer.score(-0.5f, 0.6f, 2_000L, OTHER_SESSION, now, CUR_SESSION, LAMBDA, DECAY_MS);
        assertTrue(score < 0f, "negative success score must produce a negative Hebbian score, got: " + score);
    }
}
