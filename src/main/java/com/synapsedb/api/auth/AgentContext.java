package com.synapsedb.api.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The authenticated identity for one request, stashed by {@link ApiKeyFilter} as a request
 * attribute after the key is verified. Controllers authorize off the {@code {id}} path
 * variable (which the filter already proved equals this agentId), so this is primarily for
 * logging and future use.
 *
 * @param agentId the authenticated agent
 * @param label   the key's label (may be null)
 */
public record AgentContext(int agentId, String label) {

    public static final String ATTRIBUTE = "synapse.agentContext";

    /** Retrieve the context the filter set, or {@code null} on an unauthenticated path. */
    public static AgentContext from(HttpServletRequest request) {
        Object v = request.getAttribute(ATTRIBUTE);
        return (v instanceof AgentContext ctx) ? ctx : null;
    }
}
