package com.synapsedb.api.dto;

import jakarta.validation.constraints.Min;

/**
 * POST /api/v1/agents/{id}/thoughts body.
 *
 * <p>{@code parentId} negativity is caught here (→ 400). Upper-range and empty-slot checks,
 * plus {@code successScore} range [-1, 1], are validated in {@code SynapseEngine} because
 * Hibernate Validator does not support {@code @DecimalMin/@Max} on {@code float}.
 *
 * @param parentId     parent slot (0 = root)
 * @param stateHash    environment-state fingerprint
 * @param successScore reinforcement signal, expected in [-1.0, 1.0]
 * @param sessionId    run/conversation id
 */
public record AppendThoughtRequest(
        @Min(value = 0, message = "must be >= 0") int parentId,
        int stateHash,
        float successScore,
        int sessionId) {}
