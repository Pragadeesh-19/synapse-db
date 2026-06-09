package com.synapsedb.engine.exception;

/** A request parameter is out of range or malformed (e.g. slot id, maxDepth). Maps to HTTP 400. */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
