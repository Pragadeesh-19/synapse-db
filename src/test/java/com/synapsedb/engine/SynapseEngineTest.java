package com.synapsedb.engine;

import com.synapsedb.core.BestNextResult;
import com.synapsedb.core.MemoryConfig;
import com.synapsedb.engine.exception.InvalidParentException;
import com.synapsedb.engine.exception.InvalidRequestException;
import com.synapsedb.engine.exception.ThoughtNotFoundException;
import com.synapsedb.engine.exception.UnknownAgentException;
import com.synapsedb.persistence.RingFileHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Engine-level unit tests (no Spring): validation (D4), the append+read-back+persist
 * orchestration, the bootstrap roundtrip, capacity, and the register-fails-cleanly gap.
 */
class SynapseEngineTest {

    @TempDir
    Path tmp;

    /** Small shard (1024) + temp dirs so the test is fast and isolated. */
    private MemoryConfig cfg() {
        return new MemoryConfig(4, 1024, 0.1f, 3_600_000L, 0.1f, 0.5f,
                tmp.toString(), tmp.toString(), 0.7f);
    }

    @Test
    @DisplayName("append persists and reads back the salience the core generated")
    void appendReadBackPersist() {
        try (SynapseEngine engine = new SynapseEngine(cfg())) {
            int agentId = engine.registerNewAgent();
            assertEquals(0, agentId);

            AppendResult r = engine.appendThought(agentId, 0, 99, 0.8f, 7);
            assertTrue(r.slot() >= 1, "slot must be a writable slot");
            assertTrue(r.persisted(), "must report persisted");
            assertTrue(r.salience() > 0f, "salience seeded from root must be positive");
        }
    }

    @Test
    @DisplayName("unknown agent → UnknownAgentException")
    void unknownAgent() {
        try (SynapseEngine engine = new SynapseEngine(cfg())) {
            assertThrows(UnknownAgentException.class,
                    () -> engine.appendThought(3, 0, 1, 0.5f, 1));
        }
    }

    @Test
    @DisplayName("parent out of range → InvalidParentException (prod guard, asserts are off)")
    void parentOutOfRange() {
        try (SynapseEngine engine = new SynapseEngine(cfg())) {
            int a = engine.registerNewAgent();
            assertThrows(InvalidParentException.class,
                    () -> engine.appendThought(a, 99999, 1, 0.5f, 1));
        }
    }

    @Test
    @DisplayName("parent points to an empty slot → InvalidParentException")
    void parentEmptySlot() {
        try (SynapseEngine engine = new SynapseEngine(cfg())) {
            int a = engine.registerNewAgent();
            // slot 500 was never written
            assertThrows(InvalidParentException.class,
                    () -> engine.appendThought(a, 500, 1, 0.5f, 1));
        }
    }

    @Test
    @DisplayName("successScore out of [-1,1] → InvalidRequestException")
    void successScoreOutOfRange() {
        try (SynapseEngine engine = new SynapseEngine(cfg())) {
            int a = engine.registerNewAgent();
            assertThrows(InvalidRequestException.class,
                    () -> engine.appendThought(a, 0, 1, 2.0f, 1));
            assertThrows(InvalidRequestException.class,
                    () -> engine.appendThought(a, 0, 1, Float.NaN, 1));
        }
    }

    @Test
    @DisplayName("read of a never-written slot → ThoughtNotFoundException")
    void readMissingSlot() {
        try (SynapseEngine engine = new SynapseEngine(cfg())) {
            int a = engine.registerNewAgent();
            assertThrows(ThoughtNotFoundException.class,
                    () -> engine.pathToRoot(a, 42, 64));
        }
    }

    @Test
    @DisplayName("bestNext on a childless root → NONE")
    void bestNextChildless() {
        try (SynapseEngine engine = new SynapseEngine(cfg())) {
            int a = engine.registerNewAgent();
            BestNextResult r = engine.bestNext(a, 0, 7);
            assertEquals(BestNextResult.NONE, r);
        }
    }

    @Test
    @DisplayName("pathToRoot returns the chain newest→oldest, root excluded")
    void pathToRootChain() {
        try (SynapseEngine engine = new SynapseEngine(cfg())) {
            int a = engine.registerNewAgent();
            int s1 = engine.appendThought(a, 0, 1, 0.5f, 1).slot();
            int s2 = engine.appendThought(a, s1, 2, 0.5f, 1).slot();
            int s3 = engine.appendThought(a, s2, 3, 0.5f, 1).slot();

            SynapseEngine.PathResult p = engine.pathToRoot(a, s3, 64);
            assertEquals(3, p.depth());
            assertArrayEquals(new int[]{s3, s2, s1}, p.path());
        }
    }

    @Test
    @DisplayName("maxDepth out of range → InvalidRequestException")
    void badMaxDepth() {
        try (SynapseEngine engine = new SynapseEngine(cfg())) {
            int a = engine.registerNewAgent();
            int s1 = engine.appendThought(a, 0, 1, 0.5f, 1).slot();
            assertThrows(InvalidRequestException.class, () -> engine.pathToRoot(a, s1, 0));
            assertThrows(InvalidRequestException.class, () -> engine.pathToRoot(a, s1, 99999));
        }
    }

    @Test
    @DisplayName("capacity reached → IllegalStateException (maps to 503)")
    void capacityReached() {
        try (SynapseEngine engine = new SynapseEngine(cfg())) { // maxAgents = 4
            for (int i = 0; i < 4; i++) {
                assertEquals(i, engine.registerNewAgent());
            }
            assertThrows(IllegalStateException.class, engine::registerNewAgent);
        }
    }

    @Test
    @DisplayName("register fails cleanly: ring-file open failure leaves no half-created agent")
    void registerFailsCleanly() throws IOException {
        // Point the data dir at a regular FILE so AgentRingFile.open's createDirectories fails.
        Path fileAsDir = tmp.resolve("not-a-dir");
        Files.writeString(fileAsDir, "x");
        MemoryConfig badCfg = new MemoryConfig(4, 1024, 0.1f, 3_600_000L, 0.1f, 0.5f,
                fileAsDir.toString(), tmp.toString(), 0.7f);
        try (SynapseEngine engine = new SynapseEngine(badCfg)) {
            assertThrows(UncheckedIOException.class, engine::registerNewAgent);
            assertFalse(engine.isRegistered(0), "no shard/lock/ringfile may be published on open failure");
        }
    }

    @Test
    @DisplayName("bootstrap roundtrip: write, close, reopen, reload — thoughts survive")
    void bootstrapRoundtrip() {
        int s1, s2;
        long writeHeadBefore;
        // Engine A writes and closes (unmaps the file).
        try (SynapseEngine a = new SynapseEngine(cfg())) {
            int agentId = a.registerNewAgent();
            s1 = a.appendThought(agentId, 0, 11, 0.7f, 7).slot();
            s2 = a.appendThought(agentId, s1, 22, 0.9f, 7).slot();
            writeHeadBefore = a.stats(agentId).writeHead();
        }

        // Engine B opens the same files and reloads.
        try (SynapseEngine b = new SynapseEngine(cfg())) {
            b.registerExistingAgent(0);
            RingFileHeader.Snapshot snap = b.bootstrap(0);
            assertEquals(writeHeadBefore, snap.writeHead(), "write head must be restored from disk");

            // The reloaded child of s1 must be discoverable and the chain must rebuild.
            SynapseEngine.PathResult p = b.pathToRoot(0, s2, 64);
            assertArrayEquals(new int[]{s2, s1}, p.path(), "FCNS chain must rebuild after bootstrap");
        }
    }
}
