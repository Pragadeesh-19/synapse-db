package com.synapsedb.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link MemoryConfig} fails fast when {@code SHARD_SIZE} would cause
 * record-offset int arithmetic overflow in AgentRingFile (T-SHARD-INT-OVERFLOW fix).
 */
class OverflowGuardTest {

    @Test
    @DisplayName("shardSize > MAX_SHARD_SIZE throws IllegalArgumentException naming the ceiling")
    void shardSizeTooLarge() {
        int oversized = MemoryConfig.MAX_SHARD_SIZE << 1; // one step over the limit (1<<26)
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new MemoryConfig(1, oversized, 0.1f, 3_600_000L, 0.1f, 0.5f,
                        "./data", "./config", 0.7f));
        String msg = ex.getMessage();
        assertTrue(msg.contains("MAX_SHARD_SIZE") || msg.contains(String.valueOf(MemoryConfig.MAX_SHARD_SIZE)),
                "message must name the ceiling: " + msg);
    }

    @Test
    @DisplayName("shardSize == MAX_SHARD_SIZE is accepted")
    void shardSizeAtCeiling() {
        assertDoesNotThrow(() ->
                new MemoryConfig(1, MemoryConfig.MAX_SHARD_SIZE, 0.1f, 3_600_000L, 0.1f, 0.5f,
                        "./data", "./config", 0.7f));
    }

    @Test
    @DisplayName("default shardSize (1<<20) is well within the ceiling")
    void defaultShardSizeAccepted() {
        assertDoesNotThrow(MemoryConfig::defaults);
    }
}
