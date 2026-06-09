package com.synapsedb.api.error;

import java.time.Instant;

/**
 * Uniform JSON error body for every 4xx/5xx the API produces.
 *
 * @param status    HTTP status code
 * @param error     HTTP reason phrase (e.g. "Not Found")
 * @param message   human-readable detail safe to show the caller
 * @param path      request path that produced the error
 * @param timestamp when the error was produced
 */
public record ApiError(int status, String error, String message, String path, Instant timestamp) {

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(status, error, message, path, Instant.now());
    }
}
