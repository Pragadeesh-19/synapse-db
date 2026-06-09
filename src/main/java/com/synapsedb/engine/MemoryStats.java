package com.synapsedb.engine;

/**
 * Snapshot of an agent's shard occupancy for the stats endpoint.
 *
 * @param agentId      the agent
 * @param writeHead    monotonic ring write head (number of appends + 1; starts at 1)
 * @param usedSlots    occupied writable slots = min(writeHead - 1, capacity)
 * @param capacity     writable capacity = shardSize - 1 (slot 0 is the reserved root)
 * @param fillPercent  100 * usedSlots / capacity
 * @param wrapped      whether the ring has wrapped at least once (writeHead - 1 > capacity)
 */
public record MemoryStats(
        int agentId,
        long writeHead,
        long usedSlots,
        long capacity,
        double fillPercent,
        boolean wrapped) {}
