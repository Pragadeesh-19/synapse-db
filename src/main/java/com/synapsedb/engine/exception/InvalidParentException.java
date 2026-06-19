package com.synapsedb.engine.exception;

/**
 * An append referenced a parent slot that is out of range or empty (never-written).
 * Maps to HTTP 409 — the request is well-formed but conflicts with current graph state.
 * This is the production replacement for the core's {@code assert parentSlot} guards,
 * which are disabled without {@code -ea}.
 */
public class InvalidParentException extends RuntimeException {
    public InvalidParentException(String message) {
        super(message);
    }
}
