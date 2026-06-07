package com.synapsedb.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static com.synapsedb.core.SynapseGraph.ROOT_SLOT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 exit-gate: getBestNextThought correctness.
 * Uses the package-private clock-injection seam for deterministic scoring.
 */
class BestNextTest {

    private static final int AGENT       = 0;
    private static final int CUR_SESSION = 7;
    private static final int OTHER_SESSION = 99;

    /** Config with default decay params. Clock is frozen → no temporal decay. */
    private static MemoryConfig cfg() {
        return new MemoryConfig(2, 1024, 0.1f, 3_600_000L, 0.1f, 0.5f,
                "./data", "./config", 0.7f);
    }

    /** Config with tiny decayUnitMs so even small time deltas produce visible decay. */
    private static MemoryConfig cfgFastDecay() {
        return new MemoryConfig(2, 1024, 0.1f, 1_000L, 0.1f, 0.5f,
                "./data", "./config", 0.7f);
    }

    private static SynapseGraph frozenGraph(MemoryConfig config) {
        long now = 1_000_000L;
        return new SynapseGraph(config, () -> now);
    }

    @Test
    @DisplayName("singleChild: only child is returned with a positive score")
    void singleChild() {
        SynapseGraph g = frozenGraph(cfg());
        g.registerAgent(AGENT);
        int child = g.append(AGENT, ROOT_SLOT, 1, 0.7f, CUR_SESSION);

        BestNextResult r = g.getBestNextThought(AGENT, ROOT_SLOT, CUR_SESSION);

        assertEquals(child, r.bestSlot(), "single child must be returned");
        assertTrue(r.bestScore() > 0f, "score must be positive for a positive-success child");
    }

    @Test
    @DisplayName("highestSalienceWins: among children at equal timestamps, highest score wins")
    void highestSalienceWins() {
        SynapseGraph g = frozenGraph(cfg());
        g.registerAgent(AGENT);
        // success scores vary: a=0.1, b=0.9 (winner), c=0.5
        int a = g.append(AGENT, ROOT_SLOT, 1, 0.1f, OTHER_SESSION);
        int b = g.append(AGENT, ROOT_SLOT, 2, 0.9f, OTHER_SESSION);  // highest
        int c = g.append(AGENT, ROOT_SLOT, 3, 0.5f, OTHER_SESSION);

        BestNextResult r = g.getBestNextThought(AGENT, ROOT_SLOT, OTHER_SESSION);

        assertEquals(b, r.bestSlot(), "child with highest Hebbian score must win");
    }

    @Test
    @DisplayName("sessionBoostFlipsWinner: 2× session boost can overturn a higher raw score")
    void sessionBoostFlipsWinner() {
        SynapseGraph g = frozenGraph(cfg());
        g.registerAgent(AGENT);
        // A: high success (0.9) but different session  →  no boost
        // B: lower success (0.6) but CURRENT session   →  2× boost
        //   A salience = 0.5 + 0.1 * 0.9 = 0.59  →  score = 0.9 * 0.59 * 1.0 = 0.531
        //   B salience = 0.5 + 0.1 * 0.6 = 0.56  →  score = 0.6 * 0.56 * 2.0 = 0.672  ← wins
        int a = g.append(AGENT, ROOT_SLOT, 1, 0.9f, OTHER_SESSION);
        int b = g.append(AGENT, ROOT_SLOT, 2, 0.6f, CUR_SESSION);

        BestNextResult r = g.getBestNextThought(AGENT, ROOT_SLOT, CUR_SESSION);

        assertEquals(b, r.bestSlot(),
                "session boost must flip the winner: B (lower raw, current session) beats A");
    }

    @Test
    @DisplayName("decayFlipsWinner: strong time decay can overturn a higher-salience older child")
    void decayFlipsWinner() {
        // Use decayUnitMs=1000 so 100 units of time produce nearly zero score.
        AtomicLong clock = new AtomicLong(1_000L);
        SynapseGraph g = new SynapseGraph(cfgFastDecay(), clock::get);
        g.registerAgent(AGENT);

        // A written early (ts=1000), high success → wins without decay
        clock.set(1_000L);
        int a = g.append(AGENT, ROOT_SLOT, 1, 0.9f, OTHER_SESSION);

        // B written much later (ts=100_000), lower success → wins with decay
        clock.set(100_000L);
        int b = g.append(AGENT, ROOT_SLOT, 2, 0.5f, OTHER_SESSION);

        // now = 101_000: A is 100 decay-units old (score ≈ 0), B is 1 unit old
        clock.set(101_000L);
        BestNextResult r = g.getBestNextThought(AGENT, ROOT_SLOT, OTHER_SESSION);

        assertEquals(b, r.bestSlot(),
                "decay must flip winner: B (newer, lower success) beats heavily decayed A");
    }

    @Test
    @DisplayName("noChildren_sentinel: slot with no children returns BestNextResult.NONE")
    void noChildren_sentinel() {
        SynapseGraph g = frozenGraph(cfg());
        g.registerAgent(AGENT);

        BestNextResult r = g.getBestNextThought(AGENT, ROOT_SLOT, CUR_SESSION);

        assertEquals(-1, r.bestSlot(), "no children → bestSlot must be -1");
        assertEquals(0f, r.bestScore(), 1e-7f, "no children → bestScore must be 0f");
    }

    @Test
    @DisplayName("skipsEmptyLinkedSlot: ts==0 linked slot is skipped, valid sibling is still scored")
    void skipsEmptyLinkedSlot() {
        SynapseGraph g = frozenGraph(cfg());
        g.registerAgent(AGENT);

        // Append one real child.
        int realChild = g.append(AGENT, ROOT_SLOT, 10, 0.8f, CUR_SESSION);

        // Inject a stale link: realChild → 500 (slot 500 was never written, ts==0).
        g.unsafeSetNextSibling(AGENT, realChild, 500);

        BestNextResult r = g.getBestNextThought(AGENT, ROOT_SLOT, CUR_SESSION);

        assertEquals(realChild, r.bestSlot(),
                "empty-linked slot (ts==0) must be skipped; real child must still win");
    }

    @Test
    @DisplayName("tieBreak_deterministic: equal scores always return the same slot across calls")
    void tieBreak_deterministic() {
        SynapseGraph g = frozenGraph(cfg());
        g.registerAgent(AGENT);
        // Two siblings with identical success scores → identical Hebbian scores.
        g.append(AGENT, ROOT_SLOT, 1, 0.5f, OTHER_SESSION);
        g.append(AGENT, ROOT_SLOT, 2, 0.5f, OTHER_SESSION);

        BestNextResult r1 = g.getBestNextThought(AGENT, ROOT_SLOT, OTHER_SESSION);
        BestNextResult r2 = g.getBestNextThought(AGENT, ROOT_SLOT, OTHER_SESSION);
        BestNextResult r3 = g.getBestNextThought(AGENT, ROOT_SLOT, OTHER_SESSION);

        assertEquals(r1.bestSlot(), r2.bestSlot(), "same slot on second call");
        assertEquals(r1.bestSlot(), r3.bestSlot(), "same slot on third call");
        assertTrue(r1.bestSlot() != -1, "a valid child must be selected");
    }

    @Test
    @DisplayName("cyclicChain_boundedNoHang: injected cycle terminates at guard, does not loop forever")
    void cyclicChain_boundedNoHang() {
        SynapseGraph g = frozenGraph(cfg());
        g.registerAgent(AGENT);

        // Build: root → A → B (normal FCNS chain).
        int a = g.append(AGENT, ROOT_SLOT, 1, 0.5f, CUR_SESSION);
        int b = g.append(AGENT, ROOT_SLOT, 2, 0.6f, CUR_SESSION);

        // Inject a cycle: B.nextSibling → A (B is firstChild[root] after FCNS prepend).
        g.unsafeSetNextSibling(AGENT, b, a);
        g.unsafeSetNextSibling(AGENT, a, b);

        // Must return within the guard (shardSize iterations), not hang indefinitely.
        long start = System.nanoTime();
        BestNextResult r = g.getBestNextThought(AGENT, ROOT_SLOT, CUR_SESSION);
        long elapsed = System.nanoTime() - start;

        assertTrue(elapsed < 5_000_000_000L,   // 5 seconds — far above the µs target
                "cyclic chain must be bounded; elapsed=" + elapsed + "ns");
        assertTrue(r.bestSlot() == a || r.bestSlot() == b,
                "must return one of the two real children, not a sentinel or garbage");
    }
}
