package com.synapsedb.api.auth;

/**
 * The value stored under an API key's SHA-256 hash. Identifies which agent owns the key
 * and an optional human label. The raw key is never stored — only its hash is a map key.
 *
 * @param agentId the agent this key authenticates
 * @param label   optional human-readable label (may be null/blank)
 */
public record AgentKeyRecord(int agentId, String label) {}
