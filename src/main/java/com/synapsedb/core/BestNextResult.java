package com.synapsedb.core;

/**
 * Result of a {@link SynapseGraph#getBestNextThought} query.
 *
 * <p>{@link #NONE} is returned when no scored children exist (no children,
 * or all linked slots have {@code timestamp == 0}).
 */
public record BestNextResult(int bestSlot, float bestScore) {

    /** Sentinel returned when no valid child was found. */
    public static final BestNextResult NONE = new BestNextResult(-1, 0f);
}
