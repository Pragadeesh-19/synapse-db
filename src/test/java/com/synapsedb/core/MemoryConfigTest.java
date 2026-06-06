package com.synapsedb.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Eng-review C1: MemoryConfig fail-fast validation. */
class MemoryConfigTest {

    private static MemoryConfig cfg(int maxAgents, int shardSize) {
        return new MemoryConfig(
                maxAgents, shardSize, 0.1f, 3_600_000L, 0.1f, 0.5f, "./data", "./config", 0.7f);
    }

    @Test
    @DisplayName("defaults() are valid and shardMask = shardSize - 1")
    void defaultsAreValid() {
        MemoryConfig d = MemoryConfig.defaults();
        assertEquals((1 << 20) - 1, d.shardMask());
        assertEquals(64, d.maxAgents());
    }

    @Test
    @DisplayName("non-power-of-2 SHARD_SIZE is rejected at construction")
    void nonPowerOfTwoShardSizeRejected() {
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> cfg(64, 1_000_000));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("power of 2"));
    }

    @Test
    @DisplayName("SHARD_SIZE of 1 is rejected (no writable slot beyond the root)")
    void shardSizeOneRejected() {
        assertThrows(IllegalArgumentException.class, () -> cfg(64, 1));
    }

    @Test
    @DisplayName("MAX_AGENTS * SHARD_SIZE overflowing int is rejected")
    void addressableOverflowRejected() {
        // 2048 * (1<<20) = 2,147,483,648 = Integer.MAX_VALUE + 1
        assertThrows(IllegalArgumentException.class, () -> cfg(2048, 1 << 20));
    }

    @Test
    @DisplayName("largest non-overflowing config is accepted")
    void largestValidAccepted() {
        // 2047 * (1<<20) = 2,146,435,072 < Integer.MAX_VALUE
        MemoryConfig c = cfg(2047, 1 << 20);
        assertEquals((1 << 20) - 1, c.shardMask());
    }

    @Test
    @DisplayName("maxAgents < 1 is rejected")
    void maxAgentsBelowOneRejected() {
        assertThrows(IllegalArgumentException.class, () -> cfg(0, 1 << 10));
    }

    @Test
    @DisplayName("rootBaseSalience outside [0,1] is rejected")
    void rootBaseSalienceOutOfRangeRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MemoryConfig(8, 1 << 10, 0.1f, 1000L, 0.1f, 1.5f, "d", "c", 0.7f));
    }
}
