package com.synapsedb.engine;

import com.synapsedb.core.BestNextResult;
import com.synapsedb.core.MemoryConfig;
import com.synapsedb.persistence.AgentRingFile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full restart cycle: append → close → reopen → bootstrap → query.
 * Verifies that ring files survive a JVM restart (simulated by close + new SynapseEngine)
 * and that bootstrap restores the shard to a queryable state with zero corrupt records.
 */
class EngineRestartIntegrationTest {

    @TempDir
    Path tmp;

    private MemoryConfig cfg() {
        return new MemoryConfig(4, 1024, 0.1f, 3_600_000L, 0.1f, 0.5f,
                tmp.toString(), tmp.toString(), 0.7f);
    }

    @Test
    @DisplayName("append + close + reopen + bootstrap: path-to-root matches original")
    void restartPreservesPathToRoot() {
        int slot1, slot2;

        // Phase 1: write some thoughts.
        try (SynapseEngine engine = new SynapseEngine(cfg(), new SimpleMeterRegistry())) {
            engine.registerExistingAgent(0);
            slot1 = engine.appendThought(0, 0, 100, 0.8f, 1).slot();
            slot2 = engine.appendThought(0, slot1, 200, 0.6f, 1).slot();
        }

        // Phase 2: new engine on the SAME data dir — ring files persist.
        try (SynapseEngine engine2 = new SynapseEngine(cfg(), new SimpleMeterRegistry())) {
            engine2.registerExistingAgent(0);
            engine2.bootstrap(0);

            SynapseEngine.PathResult path = engine2.pathToRoot(0, slot2, 64);
            assertEquals(2, path.depth(), "depth should be 2 after bootstrap");
            assertEquals(slot2, path.path()[0], "first step is slot2");
            assertEquals(slot1, path.path()[1], "second step is slot1");
        }
    }

    @Test
    @DisplayName("bootstrap after restart: corruptSkipped == 0 for clean writes")
    void bootstrapCountsNoCorruption() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

        try (SynapseEngine engine = new SynapseEngine(cfg(), registry)) {
            engine.registerExistingAgent(0);
            engine.appendThought(0, 0, 1, 0.5f, 1);
            engine.appendThought(0, 0, 2, 0.4f, 1);
        }

        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry2 =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try (SynapseEngine engine2 = new SynapseEngine(cfg(), registry2)) {
            engine2.registerExistingAgent(0);
            engine2.bootstrap(0);

            double skipped = registry2.counter("synapse.bootstrap.corrupt.skipped").count();
            assertEquals(0.0, skipped, "no corrupt records in a clean restart");
        }
    }

    @Test
    @DisplayName("bootstrap with CRC-corrupt record through engine: corruptSkippedCounter increments")
    void bootstrapCountsCorruptionThroughEngine() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        try (SynapseEngine engine = new SynapseEngine(cfg(), registry)) {
            engine.registerExistingAgent(0);
            engine.appendThought(0, 0, 123, 0.7f, 1);
        }

        // Flip a byte in the data region [+4..+31] of slot 1's record.
        Path ringFile = tmp.resolve("agent-0.bin");
        int dataOffset = AgentRingFile.HEADER_SIZE + 1 * AgentRingFile.RECORD_SIZE + 8;
        try (RandomAccessFile raf = new RandomAccessFile(ringFile.toFile(), "rw")) {
            raf.seek(dataOffset);
            raf.write(raf.read() ^ 0xFF);
        }

        SimpleMeterRegistry registry2 = new SimpleMeterRegistry();
        try (SynapseEngine engine2 = new SynapseEngine(cfg(), registry2)) {
            engine2.registerExistingAgent(0);
            engine2.bootstrap(0);

            double skipped = registry2.counter("synapse.bootstrap.corrupt.skipped").count();
            assertEquals(1.0, skipped, "corruptSkippedCounter must increment once for the CRC-corrupt slot");
        }
    }

    @Test
    @DisplayName("registerExistingAgent with corrupt ring file (wrong version): ringfileOpenFailureCounter increments")
    void openFailureCounterIncrementsOnVersionMismatch() throws Exception {
        // Create a valid ring file for agent 0.
        Path ringFile = tmp.resolve("agent-0.bin");
        try (AgentRingFile rf = AgentRingFile.open(ringFile, 0, 1024)) {
            rf.writeRecord(1, 0, 42, 1, 0.5f, 0.5f, 1_000L, 2L);
        }

        // Overwrite the version field (offset 8 in the header) with v1, making it unreadable.
        try (RandomAccessFile raf = new RandomAccessFile(ringFile.toFile(), "rw")) {
            raf.seek(8);
            raf.writeInt(1); // version = 1 (invalid; engine expects 2)
        }

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (SynapseEngine engine = new SynapseEngine(cfg(), registry)) {
            assertThrows(IllegalStateException.class,
                    () -> engine.registerExistingAgent(0),
                    "version mismatch must throw through the engine");

            double failures = registry.counter("synapse.ringfile.open.failures").count();
            assertEquals(1.0, failures, "ringfileOpenFailureCounter must increment for version mismatch");
        }
    }

    @Test
    @DisplayName("best-next after bootstrap returns the highest-scoring child")
    void bestNextAfterBootstrap() {
        int slot1, slot2;

        try (SynapseEngine engine = new SynapseEngine(cfg(), new SimpleMeterRegistry())) {
            engine.registerExistingAgent(0);
            slot1 = engine.appendThought(0, 0, 10, 0.9f, 5).slot();
            slot2 = engine.appendThought(0, 0, 20, 0.1f, 5).slot();
        }

        try (SynapseEngine engine2 = new SynapseEngine(cfg(), new SimpleMeterRegistry())) {
            engine2.registerExistingAgent(0);
            engine2.bootstrap(0);

            BestNextResult best = engine2.bestNext(0, 0, 5);
            assertNotEquals(BestNextResult.NONE.bestSlot(), best.bestSlot(),
                    "should find a best-next child after bootstrap");
            // slot1 has successScore 0.9, slot2 has 0.1; slot1 should win
            assertEquals(slot1, best.bestSlot(), "slot with higher success score should win");
        }
    }
}
