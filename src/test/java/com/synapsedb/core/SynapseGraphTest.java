package com.synapsedb.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 SynapseGraph coverage. Lives in the same package to reach the debug
 * constructor and the {@code unsafeSetNextSibling} walk-guard test hook.
 */
class SynapseGraphTest {

    private static final int AGENT = 0;

    /** Small power-of-2 shard so ring wrap is reachable in a handful of appends. */
    private static MemoryConfig smallCfg(int shardSize, float rootSalience) {
        return new MemoryConfig(
                2, shardSize, 0.1f, 3_600_000L, 0.1f, rootSalience, "./data", "./config", 0.7f);
    }

    private static SynapseGraph graph(int shardSize, float rootSalience) {
        SynapseGraph g = new SynapseGraph(smallCfg(shardSize, rootSalience));
        g.registerAgent(AGENT);
        return g;
    }

    // ── T1: initialisation (A1, A2) ──────────────────────────────────────────

    @Nested
    class Initialisation {
        @Test
        @DisplayName("FCNS arrays initialise to -1, not Java's default 0")
        void fcnsInitialisedToMinusOne() {
            SynapseGraph g = graph(8, 0.5f);
            for (int slot = 1; slot < 8; slot++) {
                assertEquals(-1, g.firstChild(AGENT, slot), "firstChild[" + slot + "]");
                assertEquals(-1, g.nextSibling(AGENT, slot), "nextSibling[" + slot + "]");
            }
        }

        @Test
        @DisplayName("slot 0 is the reserved root: written, self-parented, seeded salience")
        void rootSlotReserved() {
            SynapseGraph g = graph(8, 0.5f);
            assertTrue(g.isWritten(AGENT, SynapseGraph.ROOT_SLOT), "root must read as written");
            assertEquals(SynapseGraph.ROOT_SLOT, g.parentOf(AGENT, SynapseGraph.ROOT_SLOT));
            assertEquals(0.5f, g.salienceOf(AGENT, SynapseGraph.ROOT_SLOT), 1e-6);
        }

        @Test
        @DisplayName("unwritten slots report empty via timestamps==0")
        void unwrittenSlotsAreEmpty() {
            SynapseGraph g = graph(8, 0.5f);
            for (int slot = 1; slot < 8; slot++) {
                assertFalse(g.isWritten(AGENT, slot), "slot " + slot + " should be empty");
            }
        }

        @Test
        @DisplayName("append on an unregistered agent throws")
        void appendUnregisteredThrows() {
            SynapseGraph g = new SynapseGraph(smallCfg(8, 0.5f)); // not registered
            assertThrows(IllegalStateException.class, () -> g.append(AGENT, 0, 1, 0.5f, 1));
        }
    }

    // ── T3: append + FCNS (A2, A4, A5) ───────────────────────────────────────

    @Nested
    class Append {
        @Test
        @DisplayName("first real thought attaches under the root and lands on slot 1")
        void firstThoughtUnderRoot() {
            SynapseGraph g = graph(8, 0.5f);
            int slot = g.append(AGENT, SynapseGraph.ROOT_SLOT, 99, 0.8f, 7);
            assertEquals(1, slot);
            assertEquals(SynapseGraph.ROOT_SLOT, g.parentOf(AGENT, slot));
            assertEquals(slot, g.firstChild(AGENT, SynapseGraph.ROOT_SLOT));
        }

        @Test
        @DisplayName("FCNS prepend: newest child becomes firstChild, chain runs newest→oldest")
        void fcnsPrependOrder() {
            SynapseGraph g = graph(8, 0.5f);
            int a = g.append(AGENT, 0, 1, 0.1f, 1);
            int b = g.append(AGENT, 0, 2, 0.1f, 1);
            int c = g.append(AGENT, 0, 3, 0.1f, 1);

            assertEquals(c, g.firstChild(AGENT, 0), "newest is firstChild");
            assertEquals(b, g.nextSibling(AGENT, c));
            assertEquals(a, g.nextSibling(AGENT, b));
            assertEquals(-1, g.nextSibling(AGENT, a), "oldest terminates the chain");
            assertEquals(3, g.countChildren(AGENT, 0));
        }

        @Test
        @DisplayName("salience seeds from parent: clamp(parent + 0.1*success)")
        void salienceSeedsFromParent() {
            SynapseGraph g = graph(8, 0.5f);
            int child = g.append(AGENT, 0, 1, 0.8f, 1); // 0.5 + 0.1*0.8 = 0.58
            assertEquals(0.58f, g.salienceOf(AGENT, child), 1e-6);

            int grandchild = g.append(AGENT, child, 1, 1.0f, 1); // 0.58 + 0.1 = 0.68
            assertEquals(0.68f, g.salienceOf(AGENT, grandchild), 1e-6);
        }

        @Test
        @DisplayName("salience clamps at the [0,1] bounds")
        void salienceClamps() {
            SynapseGraph hi = graph(8, 1.0f);
            int top = hi.append(AGENT, 0, 1, 1.0f, 1); // 1.0 + 0.1 → clamp 1.0
            assertEquals(1.0f, hi.salienceOf(AGENT, top), 1e-6);

            SynapseGraph lo = graph(8, 0.0f);
            int bottom = lo.append(AGENT, 0, 1, -1.0f, 1); // 0.0 - 0.1 → clamp 0.0
            assertEquals(0.0f, lo.salienceOf(AGENT, bottom), 1e-6);
        }
    }

    // ── T3: ring buffer (A2) ─────────────────────────────────────────────────

    @Nested
    class RingBuffer {
        @Test
        @DisplayName("ring wrap never returns slot 0 and stays within [1, shardSize-1]")
        void wrapSkipsRoot() {
            SynapseGraph g = graph(8, 0.5f); // usable slots 1..7
            for (int i = 0; i < 30; i++) {
                int slot = g.append(AGENT, SynapseGraph.ROOT_SLOT, i, 0.1f, 1);
                assertNotEquals(SynapseGraph.ROOT_SLOT, slot, "ring must skip the root slot");
                assertTrue(slot >= 1 && slot <= 7, "slot in range: " + slot);
            }
        }

        @Test
        @DisplayName("first wrap reuses slot 1 (after 7 writable slots)")
        void firstWrapReusesSlotOne() {
            SynapseGraph g = graph(8, 0.5f);
            int[] slots = new int[8];
            for (int i = 0; i < 8; i++) {
                slots[i] = g.append(AGENT, SynapseGraph.ROOT_SLOT, i, 0.1f, 1);
            }
            assertEquals(1, slots[0]);
            assertEquals(7, slots[6]);
            assertEquals(1, slots[7], "8th append wraps and reuses slot 1");
        }

        @Test
        @DisplayName("after eviction+reuse, the evicted node's old child is unreachable through the reused slot")
        void evictionMakesOldChildUnreachable() {
            SynapseGraph g = graph(8, 0.5f);
            int a = g.append(AGENT, 0, 1, 0.1f, 1);      // slot 1
            int oldChild = g.append(AGENT, a, 2, 0.1f, 1); // slot 2, child of a
            assertEquals(oldChild, g.firstChild(AGENT, a), "a reaches its child before eviction");

            // Drive the ring until slot 1 (a) is reused. 8th append reuses slot 1.
            int reused = -1;
            for (int i = 0; i < 6; i++) {
                reused = g.append(AGENT, SynapseGraph.ROOT_SLOT, 100 + i, 0.1f, 1);
            }
            assertEquals(1, reused, "slot 1 was reused");

            // The invariant that matters: walking the reused slot's children must NOT
            // surface the evicted node's old child. The reused slot is a fresh node
            // (no children), so oldChild (slot 2) is no longer reachable through it.
            assertEquals(-1, g.firstChild(AGENT, reused),
                    "reused slot starts with no children");
            assertEquals(0, g.countChildren(AGENT, reused),
                    "reused slot has zero children");
            for (int c = g.firstChild(AGENT, reused); c != -1; c = g.nextSibling(AGENT, c)) {
                assertNotEquals(oldChild, c,
                        "evicted node's old child must be unreachable through the reused slot");
            }
        }
    }

    // ── T4 / T6: bounded walk guard (A3) — CRITICAL ──────────────────────────

    @Nested
    class WalkGuard {
        @Test
        @DisplayName("CRITICAL: a cyclic sibling chain (from eviction/reuse) does not hang the walk")
        void cyclicChainDoesNotHang() {
            SynapseGraph g = graph(8, 0.5f);
            int c1 = g.append(AGENT, 0, 1, 0.1f, 1);
            int c2 = g.append(AGENT, 0, 2, 0.1f, 1);
            int c3 = g.append(AGENT, 0, 3, 0.1f, 1); // root.firstChild = c3 → c2 → c1 → -1

            // Inject the corruption a reused slot could create: c1 → c3, forming a cycle.
            g.unsafeSetNextSibling(AGENT, c1, c3);

            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                int n = g.countChildren(AGENT, SynapseGraph.ROOT_SLOT);
                assertTrue(n <= 8, "bounded by shardSize, got " + n);
            }, "bounded walk must terminate on a cyclic chain");
        }

        @Test
        @DisplayName("a node with no children counts zero")
        void noChildrenCountsZero() {
            SynapseGraph g = graph(8, 0.5f);
            int leaf = g.append(AGENT, 0, 1, 0.1f, 1);
            assertEquals(0, g.countChildren(AGENT, leaf));
        }
    }

    // ── T5: getPathToRoot with caller buffer (P1) ────────────────────────────

    @Nested
    class PathToRoot {
        @Test
        @DisplayName("backtracks to root, root excluded, written into caller buffer")
        void backtracksToRoot() {
            SynapseGraph g = graph(16, 0.5f);
            int a = g.append(AGENT, 0, 1, 0.1f, 1); // 1
            int b = g.append(AGENT, a, 2, 0.1f, 1); // 2
            int c = g.append(AGENT, b, 3, 0.1f, 1); // 3

            int[] out = new int[32];
            int count = g.getPathToRoot(AGENT, c, out, 50);
            assertEquals(3, count, "root (slot 0) is the terminus, not included");
            assertEquals(c, out[0]);
            assertEquals(b, out[1]);
            assertEquals(a, out[2]);
        }

        @Test
        @DisplayName("from the root, the path is empty")
        void fromRootIsEmpty() {
            SynapseGraph g = graph(16, 0.5f);
            int[] out = new int[8];
            assertEquals(0, g.getPathToRoot(AGENT, SynapseGraph.ROOT_SLOT, out, 50));
        }

        @Test
        @DisplayName("maxDepth caps the walk")
        void maxDepthCaps() {
            SynapseGraph g = graph(16, 0.5f);
            int p = 0;
            for (int i = 0; i < 5; i++) {
                p = g.append(AGENT, p, i, 0.1f, 1);
            }
            int[] out = new int[32];
            assertEquals(2, g.getPathToRoot(AGENT, p, out, 2), "maxDepth=2 returns 2 slots");
        }

        @Test
        @DisplayName("the same buffer is reused across calls (zero allocation)")
        void bufferReused() {
            SynapseGraph g = graph(16, 0.5f);
            int a = g.append(AGENT, 0, 1, 0.1f, 1);
            int b = g.append(AGENT, a, 2, 0.1f, 1);

            int[] out = new int[32];
            assertEquals(1, g.getPathToRoot(AGENT, a, out, 50));
            assertEquals(2, g.getPathToRoot(AGENT, b, out, 50), "reusing the same buffer works");
            assertEquals(b, out[0]);
            assertEquals(a, out[1]);
        }
    }

    // ── T7: debug asserts (C2) ───────────────────────────────────────────────

    @Nested
    class DebugAsserts {
        private boolean assertionsEnabled() {
            boolean ea = false;
            assert ea = true; // side-effect only when -ea is on
            return ea;
        }

        @Test
        @DisplayName("out-of-range parentId trips the debug assert (when -ea)")
        void outOfRangeParentAsserts() {
            assumeTrue(assertionsEnabled(), "requires -ea (surefire enables by default)");
            SynapseGraph g = graph(8, 0.5f);
            assertThrows(AssertionError.class, () -> g.append(AGENT, 999, 1, 0.5f, 1));
        }

        @Test
        @DisplayName("parentId pointing to an empty slot trips the debug assert (when -ea)")
        void emptyParentAsserts() {
            assumeTrue(assertionsEnabled(), "requires -ea");
            SynapseGraph g = graph(8, 0.5f);
            // slot 5 is in range but never written
            assertThrows(AssertionError.class, () -> g.append(AGENT, 5, 1, 0.5f, 1));
        }
    }

    // ── P2: per-agent slice isolation ────────────────────────────────────────

    @Nested
    class MultiAgentIsolation {
        @Test
        @DisplayName("one agent's writes do not affect another agent's slices")
        void agentsAreFullyIsolated() {
            SynapseGraph g = new SynapseGraph(smallCfg(8, 0.5f));
            int agent0 = 0;
            int agent1 = 1;
            g.registerAgent(agent0);
            g.registerAgent(agent1);

            // Agent 0: a single thought under root with a distinct success score.
            int a0 = g.append(agent0, SynapseGraph.ROOT_SLOT, 11, 0.8f, 1); // salience 0.58
            float a0Salience = g.salienceOf(agent0, a0);
            int a0First = g.firstChild(agent0, SynapseGraph.ROOT_SLOT);
            int[] a0Path = new int[8];
            int a0Len = g.getPathToRoot(agent0, a0, a0Path, 8);

            // Agent 1: a deeper chain with different scores — must not touch agent 0.
            int p = SynapseGraph.ROOT_SLOT;
            for (int i = 0; i < 5; i++) {
                p = g.append(agent1, p, 200 + i, -0.5f, 9);
            }

            // Agent 0 is byte-for-byte unchanged after agent 1's writes.
            assertEquals(a0Salience, g.salienceOf(agent0, a0), 1e-6,
                    "agent 0 salience unaffected by agent 1");
            assertEquals(a0First, g.firstChild(agent0, SynapseGraph.ROOT_SLOT),
                    "agent 0 root firstChild unaffected by agent 1");
            assertEquals(1, g.countChildren(agent0, SynapseGraph.ROOT_SLOT),
                    "agent 0 still has exactly its one child");

            int[] recheck = new int[8];
            assertEquals(a0Len, g.getPathToRoot(agent0, a0, recheck, 8),
                    "agent 0 path length unchanged");
            assertEquals(a0Path[0], recheck[0], "agent 0 path content unchanged");

            // Sanity: agent 1 really did build its own independent chain.
            assertEquals(5, g.getPathToRoot(agent1, p, new int[8], 8),
                    "agent 1 has its own depth-5 chain");
            // Both agents' first thought lands on local slot 1 (same id, separate arrays):
            // independence shows up as different stored state in that same-numbered slot.
            assertNotEquals(g.salienceOf(agent0, 1), g.salienceOf(agent1, 1),
                    "same slot id, independent per-agent storage");
        }
    }
}
