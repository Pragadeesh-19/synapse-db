package com.synapsedb.api.dto;

/**
 * 200 response to GET path-to-root. {@code path} is ordered from the start slot toward the
 * root; the root (slot 0) is the terminus and is NOT included. {@code depth == path.length}.
 */
public record PathToRootResponse(int[] path, int depth) {}
