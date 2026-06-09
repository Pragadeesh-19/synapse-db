package com.synapsedb.api.dto;

/**
 * 201 response to agent registration. The raw {@code apiKey} is returned exactly ONCE here
 * and never again (only its hash is stored). The client must save it.
 *
 * @param agentId the new agent's id
 * @param apiKey  the raw API key (format {@code sk_syn_<uuid>}) — shown once
 */
public record RegisterAgentResponse(int agentId, String apiKey) {}
