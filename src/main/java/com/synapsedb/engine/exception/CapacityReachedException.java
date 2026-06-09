package com.synapsedb.engine.exception;

/**
 * All {@code maxAgents} shard slots are occupied — the server cannot register another
 * agent. Maps to HTTP 503. Extends {@link IllegalStateException} for backward source
 * compatibility (callers/tests catching {@code IllegalStateException} still match), while
 * giving the error handler a precise type to map to 503 instead of a bare
 * {@code IllegalStateException} that should default to 500.
 */
public class CapacityReachedException extends IllegalStateException {
    public CapacityReachedException(int maxAgents) {
        super("agent capacity reached (" + maxAgents + "); cannot register more agents");
    }
}
