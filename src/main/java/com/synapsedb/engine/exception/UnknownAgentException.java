package com.synapsedb.engine.exception;

/** The requested agent is not registered. Maps to HTTP 404. */
public class UnknownAgentException extends RuntimeException {
    public UnknownAgentException(int agentId) {
        super("agent " + agentId + " is not registered");
    }
}
