package com.synapsedb.api.dto;

import com.synapsedb.engine.MemoryStats;

/**
 * 200 response to GET memory/stats.
 */
public record StatsResponse(
        int agentId,
        long writeHead,
        long usedSlots,
        long capacity,
        double fillPercent,
        boolean wrapped) {

    public static StatsResponse from(MemoryStats s) {
        return new StatsResponse(s.agentId(), s.writeHead(), s.usedSlots(),
                s.capacity(), s.fillPercent(), s.wrapped());
    }
}
