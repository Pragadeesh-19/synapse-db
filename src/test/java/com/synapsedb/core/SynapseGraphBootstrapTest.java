package com.synapsedb.core;

import com.synapsedb.persistence.AgentRingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 exit-gate: write → bootstrap roundtrip correctness.
 * All tests use a tiny SHARD_SIZE=8 so ring-wrap is reachable in 7 appends.
 */
class SynapseGraphBootstrapTest {

    private static final int SHARD = 8;
    private static final int AGENT = 0;

    private static MemoryConfig cfg() {
        return new MemoryConfig(2, SHARD, 0.1f, 3_600_000L, 0.1f, 0.5f, "./data", "./config", 0.7f);
    }

    /** Write every appended slot to a ring file; return the open (not yet closed) file. */
    private static AgentRingFile writeAll(SynapseGraph g, int agentId, Path file) throws Exception {
        AgentRingFile rf = AgentRingFile.open(file, agentId, SHARD);
        // slot 0 is the root — never appended, never written to the ring file
        for (int slot = 1; slot < SHARD; slot++) {
            if (!g.isWritten(agentId, slot)) continue;
            rf.writeRecord(slot,
                    g.parentOf(agentId, slot),
                    g.stateHashOf(agentId, slot),
                    g.sessionIdOf(agentId, slot),
                    g.successScoreOf(agentId, slot),
                    g.salienceOf(agentId, slot),
                    g.timestampOf(agentId, slot),
                    g.writeHead(agentId));
        }
        return rf;
    }

    // ── Full roundtrip ───────────────────────────────────────────────────────

    @Nested
    class Roundtrip {
        @Test
        @DisplayName("all 6 persisted fields survive write → bootstrap without modification")
        void allFieldsPreserved(@TempDir Path dir) throws Exception {
            SynapseGraph live = new SynapseGraph(cfg());
            live.registerAgent(AGENT);
            // Build a small tree: root → 1 → 2, root → 3
            int s1 = live.append(AGENT, SynapseGraph.ROOT_SLOT, 100, 0.8f, 7);
            int s2 = live.append(AGENT, s1,                     200, 0.6f, 7);
            int s3 = live.append(AGENT, SynapseGraph.ROOT_SLOT, 300, 0.4f, 8);

            Path p = dir.resolve("agent-0.bin");
            try (AgentRingFile rf = writeAll(live, AGENT, p)) {
                // close triggers unmap
            }

            SynapseGraph restored = new SynapseGraph(cfg());
            try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) {
                rf.bootstrapInto(restored);
            }

            for (int slot : new int[]{s1, s2, s3}) {
                assertTrue(restored.isWritten(AGENT, slot), "slot " + slot + " must be written");
                assertEquals(live.parentOf(AGENT, slot),       restored.parentOf(AGENT, slot),       "parent[" + slot + "]");
                assertEquals(live.stateHashOf(AGENT, slot),    restored.stateHashOf(AGENT, slot),     "stateHash[" + slot + "]");
                assertEquals(live.sessionIdOf(AGENT, slot),    restored.sessionIdOf(AGENT, slot),     "sessionId[" + slot + "]");
                assertEquals(live.successScoreOf(AGENT, slot), restored.successScoreOf(AGENT, slot),  1e-6f, "successScore[" + slot + "]");
                assertEquals(live.salienceOf(AGENT, slot),     restored.salienceOf(AGENT, slot),      1e-6f, "salienceScore[" + slot + "]");
                assertEquals(live.timestampOf(AGENT, slot),    restored.timestampOf(AGENT, slot),     "timestamp[" + slot + "]");
            }
        }

        @Test
        @DisplayName("salience is the PERSISTED value, not recomputed from append()")
        void salienceNotRecomputed(@TempDir Path dir) throws Exception {
            SynapseGraph live = new SynapseGraph(cfg());
            live.registerAgent(AGENT);
            int slot = live.append(AGENT, SynapseGraph.ROOT_SLOT, 0, 0.9f, 1);
            float persistedSalience = live.salienceOf(AGENT, slot);

            Path p = dir.resolve("agent-0.bin");
            try (AgentRingFile rf = writeAll(live, AGENT, p)) { /* close */ }

            SynapseGraph restored = new SynapseGraph(cfg());
            try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) {
                rf.bootstrapInto(restored);
            }

            assertEquals(persistedSalience, restored.salienceOf(AGENT, slot), 1e-6f,
                    "bootstrap must restore persisted salience exactly, not recompute it");
        }

        @Test
        @DisplayName("timestamps are the PERSISTED values, not stamped at bootstrap time")
        void timestampsPreserved(@TempDir Path dir) throws Exception {
            SynapseGraph live = new SynapseGraph(cfg());
            live.registerAgent(AGENT);
            int s1 = live.append(AGENT, SynapseGraph.ROOT_SLOT, 0, 0.5f, 1);
            int s2 = live.append(AGENT, s1, 0, 0.5f, 1);
            long ts1 = live.timestampOf(AGENT, s1);
            long ts2 = live.timestampOf(AGENT, s2);

            Path p = dir.resolve("agent-0.bin");
            try (AgentRingFile rf = writeAll(live, AGENT, p)) { /* close */ }

            SynapseGraph restored = new SynapseGraph(cfg());
            try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) {
                rf.bootstrapInto(restored);
            }

            assertEquals(ts1, restored.timestampOf(AGENT, s1), "timestamp[s1] must match persisted value");
            assertEquals(ts2, restored.timestampOf(AGENT, s2), "timestamp[s2] must match persisted value");
        }
    }

    // ── Write head restore ───────────────────────────────────────────────────

    @Nested
    class WriteHeadRestore {
        @Test
        @DisplayName("next append after bootstrap continues at the slot after the last persisted one")
        void writeHeadRestoredFromHeader(@TempDir Path dir) throws Exception {
            SynapseGraph live = new SynapseGraph(cfg());
            live.registerAgent(AGENT);
            int s1 = live.append(AGENT, SynapseGraph.ROOT_SLOT, 0, 0.5f, 1);
            int s2 = live.append(AGENT, s1, 0, 0.5f, 1);
            long headAfterAppends = live.writeHead(AGENT); // = 3 (slots 1+2 claimed)

            Path p = dir.resolve("agent-0.bin");
            try (AgentRingFile rf = writeAll(live, AGENT, p)) { /* close */ }

            SynapseGraph restored = new SynapseGraph(cfg());
            try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) {
                rf.bootstrapInto(restored);
            }

            assertEquals(headAfterAppends, restored.writeHead(AGENT),
                    "writeHead must equal the persisted value so next append lands at slot 3");
            // Confirm by appending: the new slot must be 3 (not 1).
            int nextSlot = restored.append(AGENT, SynapseGraph.ROOT_SLOT, 99, 0.5f, 1);
            assertEquals(3, nextSlot, "first append after bootstrap must land at slot 3");
        }
    }

    // ── FCNS reconstruction ─────────────────────────────────────────────────

    @Nested
    class FcnsRebuild {
        @Test
        @DisplayName("countChildren(root) after bootstrap matches the live graph")
        void childCountMatchesLive(@TempDir Path dir) throws Exception {
            SynapseGraph live = new SynapseGraph(cfg());
            live.registerAgent(AGENT);
            live.append(AGENT, SynapseGraph.ROOT_SLOT, 1, 0.5f, 1);
            live.append(AGENT, SynapseGraph.ROOT_SLOT, 2, 0.5f, 1);
            live.append(AGENT, SynapseGraph.ROOT_SLOT, 3, 0.5f, 1);
            int liveCount = live.countChildren(AGENT, SynapseGraph.ROOT_SLOT);

            Path p = dir.resolve("agent-0.bin");
            try (AgentRingFile rf = writeAll(live, AGENT, p)) { /* close */ }

            SynapseGraph restored = new SynapseGraph(cfg());
            try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) {
                rf.bootstrapInto(restored);
            }

            assertEquals(liveCount, restored.countChildren(AGENT, SynapseGraph.ROOT_SLOT),
                    "countChildren(root) must match after bootstrap");
        }

        @Test
        @DisplayName("pre-wrap: sibling chain order matches live engine (newest = firstChild)")
        void prewrapSiblingOrderPreserved(@TempDir Path dir) throws Exception {
            SynapseGraph live = new SynapseGraph(cfg());
            live.registerAgent(AGENT);
            int a = live.append(AGENT, SynapseGraph.ROOT_SLOT, 1, 0.5f, 1);
            int b = live.append(AGENT, SynapseGraph.ROOT_SLOT, 2, 0.5f, 1);
            int c = live.append(AGENT, SynapseGraph.ROOT_SLOT, 3, 0.5f, 1);
            // Live chain: firstChild[root] = c → b → a → -1 (prepend order)
            assertEquals(c, live.firstChild(AGENT, SynapseGraph.ROOT_SLOT));
            assertEquals(b, live.nextSibling(AGENT, c));
            assertEquals(a, live.nextSibling(AGENT, b));

            Path p = dir.resolve("agent-0.bin");
            try (AgentRingFile rf = writeAll(live, AGENT, p)) { /* close */ }

            SynapseGraph restored = new SynapseGraph(cfg());
            try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) {
                rf.bootstrapInto(restored);
            }

            assertEquals(c, restored.firstChild(AGENT, SynapseGraph.ROOT_SLOT), "firstChild[root]");
            assertEquals(b, restored.nextSibling(AGENT, c), "nextSibling[c]");
            assertEquals(a, restored.nextSibling(AGENT, b), "nextSibling[b]");
            assertEquals(-1, restored.nextSibling(AGENT, a), "nextSibling[a] = end of chain");
        }

        @Test
        @DisplayName("post-wrap: bootstrap is consistent with the live graph's FCNS state")
        void postWrapFcnsConsistent(@TempDir Path dir) throws Exception {
            // SHARD=8: fill slots 1-7, then wrap and rewrite slot 1.
            // Both live and restored should agree on which slots are written.
            SynapseGraph live = new SynapseGraph(cfg());
            live.registerAgent(AGENT);
            for (int i = 0; i < SHARD - 1; i++) {
                live.append(AGENT, SynapseGraph.ROOT_SLOT, i, 0.5f, 1); // fills 1..7
            }
            // This append wraps and reuses slot 1 (evicts it).
            int reusedSlot = live.append(AGENT, SynapseGraph.ROOT_SLOT, 99, 0.6f, 2);
            assertEquals(1, reusedSlot, "ring must wrap and reuse slot 1");

            Path p = dir.resolve("agent-0.bin");
            try (AgentRingFile rf = writeAll(live, AGENT, p)) { /* close */ }

            SynapseGraph restored = new SynapseGraph(cfg());
            try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) {
                rf.bootstrapInto(restored);
            }

            // Slot 1 carries new data in both live and restored.
            assertTrue (restored.isWritten(AGENT, 1), "slot 1 must carry new data");
            assertEquals(live.salienceOf(AGENT, 1), restored.salienceOf(AGENT, 1), 1e-6f,
                    "slot 1 salience must match the post-wrap value");

            // Slots 2-7 survive the wrap in both.
            for (int slot = 2; slot <= 7; slot++) {
                assertEquals(live.isWritten(AGENT, slot), restored.isWritten(AGENT, slot),
                        "written-state must match for slot " + slot);
            }
        }
    }

    // ── Multi-agent isolation ───────────────────────────────────────────────

    @Nested
    class MultiAgentIsolation {
        @Test
        @DisplayName("agent-0 and agent-1 ring files are independent")
        void agentFilesAreIsolated(@TempDir Path dir) throws Exception {
            MemoryConfig cfg = new MemoryConfig(4, SHARD, 0.1f, 3_600_000L, 0.1f, 0.5f,
                    "./data", "./config", 0.7f);
            SynapseGraph live = new SynapseGraph(cfg);
            live.registerAgent(0);
            live.registerAgent(1);

            int s0 = live.append(0, SynapseGraph.ROOT_SLOT, 100, 0.9f, 1);
            int s1 = live.append(1, SynapseGraph.ROOT_SLOT, 200, 0.1f, 1);
            assertNotEquals(live.salienceOf(0, s0), live.salienceOf(1, s1), 1e-6f,
                    "agents must have independent salience for same slot index");

            Path p0 = dir.resolve("agent-0.bin");
            Path p1 = dir.resolve("agent-1.bin");
            try (AgentRingFile rf = AgentRingFile.open(p0, 0, SHARD)) {
                rf.writeRecord(s0, live.parentOf(0, s0), 100, 1,
                        live.successScoreOf(0, s0), live.salienceOf(0, s0),
                        live.timestampOf(0, s0), live.writeHead(0));
            }
            try (AgentRingFile rf = AgentRingFile.open(p1, 1, SHARD)) {
                rf.writeRecord(s1, live.parentOf(1, s1), 200, 1,
                        live.successScoreOf(1, s1), live.salienceOf(1, s1),
                        live.timestampOf(1, s1), live.writeHead(1));
            }

            SynapseGraph restored = new SynapseGraph(cfg);
            try (AgentRingFile rf = AgentRingFile.open(p0, 0, SHARD)) {
                rf.bootstrapInto(restored);
            }
            try (AgentRingFile rf = AgentRingFile.open(p1, 1, SHARD)) {
                rf.bootstrapInto(restored);
            }

            // Agent 0's data must not bleed into agent 1's shard and vice versa.
            assertEquals(live.salienceOf(0, s0), restored.salienceOf(0, s0), 1e-6f,
                    "agent-0 salience must be restored correctly");
            assertEquals(live.salienceOf(1, s1), restored.salienceOf(1, s1), 1e-6f,
                    "agent-1 salience must be restored correctly");
            assertNotEquals(restored.salienceOf(0, s0), restored.salienceOf(1, s1), 1e-6f,
                    "agents must remain isolated: same slot id, different storage");
        }
    }
}
