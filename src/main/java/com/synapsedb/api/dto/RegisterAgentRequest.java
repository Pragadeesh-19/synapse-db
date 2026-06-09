package com.synapsedb.api.dto;

/**
 * POST /api/v1/agents body. All fields optional — a label is a human convenience.
 *
 * @param label optional human-readable label for the new agent (may be null/blank)
 */
public record RegisterAgentRequest(String label) {}
