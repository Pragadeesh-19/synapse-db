package com.synapsedb.engine.exception;

/** A read targeted a slot that holds no thought (empty/never-written). Maps to HTTP 404. */
public class ThoughtNotFoundException extends RuntimeException {
    public ThoughtNotFoundException(int agentId, int slot) {
        super("thought " + slot + " not found for agent " + agentId);
    }
}
