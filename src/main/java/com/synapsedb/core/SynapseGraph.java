package com.synapsedb.core;

import java.util.function.LongSupplier;

/**
 * In-memory reasoning graph: eight parallel primitive arrays per agent
 * (Struct-of-Arrays) with a first-child / next-sibling (FCNS) tree and a
 * ring buffer write head.
 *
 * <p>Reflects the v1.2 eng-review decisions:
 * <ul>
 *   <li><b>8 arrays</b>, not 9 — {@code thoughtIds} dropped (slot index IS the id).</li>
 *   <li><b>Per-agent slices</b>, lazily allocated on {@link #registerAgent(int)} —
 *       no global {@code absoluteIndex}/base offset.</li>
 *   <li><b>Slot 0 is a reserved, never-evicted root.</b> Ring writes occupy
 *       {@code [1, shardSize-1]}; the write head skips 0 on wrap.</li>
 *   <li>FCNS arrays filled with {@code -1} at construction; an empty slot is
 *       {@code timestamps[slot] == 0}.</li>
 *   <li>Salience seeds from the parent, then adjusts by {@code successScore}.</li>
 *   <li>The sibling walk is <b>bounded</b> so a stale/cyclic chain from ring
 *       eviction can never hang the read path.</li>
 * </ul>
 *
 * <pre>
 * append(parent=P):
 *   claim slot S = writeHead &amp; mask   (if S==0 → skip root, S=1, writeHead++)
 *   if timestamps[S]!=0  → EVICT: firstChild[S]=nextSibling[S]=-1
 *   write fields; salience[S]=clamp(salience[P]+lr*success)
 *   firstChild[S] = -1                  (new node has no children yet)
 *   nextSibling[S] = firstChild[P]      (FCNS step 1: new → old first child)
 *   firstChild[P]  = S                  (FCNS step 2: parent → new child)
 *
 *      P                       P
 *      └▶ C (firstChild)  ==&gt;  └▶ S ─nextSibling▶ C
 * </pre>
 *
 * <p><b>Concurrency (v1.2):</b> single-writer-per-shard only. No synchronization.
 * The multi-writer recipe is shelved to V2 (see TODOS.md {@code T-MULTIWRITER}).
 */
public final class SynapseGraph {

    /** Root slot id. Reserved, never evicted; terminus of getPathToRoot. */
    public static final int ROOT_SLOT = 0;

    private final MemoryConfig config;
    private final int shardSize;
    private final int shardMask;
    private final float learningRate;
    private final float rootBaseSalience;
    private final LongSupplier clock;

    /** One {@link Shard} per agent; null until {@link #registerAgent(int)}. */
    private final Shard[] shards;

    public SynapseGraph(MemoryConfig config) {
        this(config, System::currentTimeMillis);
    }

    /** Test seam: inject a deterministic clock for timestamp-dependent assertions. */
    SynapseGraph(MemoryConfig config, LongSupplier clock) {
        this.config = config;
        this.shardSize = config.shardSize();
        this.shardMask = config.shardMask();
        this.learningRate = config.learningRate();
        this.rootBaseSalience = config.rootBaseSalience();
        this.clock = clock;
        this.shards = new Shard[config.maxAgents()];
    }

    // ── Agent lifecycle ──────────────────────────────────────────────────────

    /** Lazily allocate an agent's ~36MB slice (eng-review P2). Idempotent. */
    public void registerAgent(int agentId) {
        checkAgentId(agentId);
        if (shards[agentId] == null) {
            shards[agentId] = new Shard(shardSize, clock.getAsLong(), rootBaseSalience);
        }
    }

    public boolean isRegistered(int agentId) {
        return agentId >= 0 && agentId < shards.length && shards[agentId] != null;
    }

    // ── Hot path: append ─────────────────────────────────────────────────────

    /**
     * Append a thought under {@code parentSlot}. O(1). Returns the new slot id.
     *
     * <p>{@code parentSlot} is validated by a debug assert only (eng-review C2):
     * the trust boundary is the API layer (Phase 4); the hot path stays unchecked
     * in production builds. Run tests with {@code -ea} to exercise the asserts.
     */
    public int append(int agentId, int parentSlot, int stateHash, float successScore, int sessionId) {
        Shard s = shard(agentId);

        assert parentSlot >= 0 && parentSlot < shardSize
                : "parentSlot out of range: " + parentSlot;
        assert parentSlot == ROOT_SLOT || s.timestamps[parentSlot] != 0L
                : "parentSlot points to an empty (never-written) slot: " + parentSlot;

        // Claim a ring slot, skipping the reserved root (slot 0) on wrap.
        long wh = s.writeHead;
        int slot = (int) (wh & shardMask);
        if (slot == ROOT_SLOT) {
            wh++;            // step over the reserved root position entirely
            slot = 1;
        }
        s.writeHead = wh + 1;

        assert parentSlot != slot : "self-parent: slot " + slot + " cannot parent itself";

        // Eviction: if this slot was occupied, drop its outgoing FCNS links.
        // (Stale chains pointing TO this slot are a documented V1 limitation; the
        // bounded sibling walk below prevents them from hanging a reader.)
        if (s.timestamps[slot] != 0L) {
            s.firstChild[slot] = -1;
            s.nextSibling[slot] = -1;
        }

        // Write the record fields.
        s.parentIds[slot] = parentSlot;
        s.stateHashes[slot] = stateHash;
        s.sessionIds[slot] = sessionId;
        s.successScores[slot] = successScore;
        s.timestamps[slot] = clock.getAsLong();
        s.salienceScores[slot] = seedSalience(s.salienceScores[parentSlot], successScore);

        // FCNS prepend. Order is load-bearing: link new→old first, THEN parent→new.
        s.firstChild[slot] = -1;                       // new node has no children yet
        s.nextSibling[slot] = s.firstChild[parentSlot]; // step 1
        s.firstChild[parentSlot] = slot;                // step 2

        return slot;
    }

    /** Hebbian seed (eng-review A5): inherit parent salience, adjust by reinforcement, clamp [0,1]. */
    private float seedSalience(float parentSalience, float successScore) {
        float updated = parentSalience + learningRate * successScore;
        if (updated < 0f) return 0f;
        if (updated > 1f) return 1f;
        return updated;
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    /**
     * Backtrack from {@code fromSlot} toward the root, writing slot ids into the
     * caller-provided {@code out} buffer (eng-review P1: zero allocation). The root
     * (slot 0) is the terminus and is NOT included. Returns the number of slots
     * written. Bounded by {@code min(out.length, maxDepth)} so a corrupted parent
     * chain can never loop forever.
     */
    public int getPathToRoot(int agentId, int fromSlot, int[] out, int maxDepth) {
        Shard s = shard(agentId);
        int limit = Math.min(maxDepth, out.length);
        int count = 0;
        int ptr = fromSlot;
        while (ptr != ROOT_SLOT && count < limit) {
            out[count++] = ptr;
            ptr = s.parentIds[ptr];
        }
        return count;
    }

    /**
     * Count the children of {@code parentSlot} via a BOUNDED FCNS sibling walk
     * (eng-review A3 / T4). The guard caps iterations at {@code shardSize}, so even
     * a cyclic chain produced by ring eviction returns a bounded result instead of
     * hanging. This is the walk-safety foundation Phase 3's getBestNextThought
     * (Hebbian scoring) will build on.
     */
    public int countChildren(int agentId, int parentSlot) {
        Shard s = shard(agentId);
        int child = s.firstChild[parentSlot];
        int guard = 0;
        int count = 0;
        while (child != -1 && guard++ < shardSize) {
            count++;
            child = s.nextSibling[child];
        }
        return count;
    }

    /** First child slot of {@code parentSlot}, or -1. */
    public int firstChild(int agentId, int parentSlot) {
        return shard(agentId).firstChild[parentSlot];
    }

    /** Next sibling slot of {@code slot}, or -1. */
    public int nextSibling(int agentId, int slot) {
        return shard(agentId).nextSibling[slot];
    }

    public int parentOf(int agentId, int slot) {
        return shard(agentId).parentIds[slot];
    }

    public float salienceOf(int agentId, int slot) {
        return shard(agentId).salienceScores[slot];
    }

    public boolean isWritten(int agentId, int slot) {
        return shard(agentId).timestamps[slot] != 0L;
    }

    /** Monotonic write head for the agent (used by stats / persistence). */
    public long writeHead(int agentId) {
        return shard(agentId).writeHead;
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private Shard shard(int agentId) {
        checkAgentId(agentId);
        Shard s = shards[agentId];
        if (s == null) {
            throw new IllegalStateException("agent " + agentId + " is not registered");
        }
        return s;
    }

    private void checkAgentId(int agentId) {
        if (agentId < 0 || agentId >= shards.length) {
            throw new IllegalArgumentException(
                    "agentId out of range [0," + (shards.length - 1) + "]: " + agentId);
        }
    }

    /** Test-only hook to inject a corrupted/cyclic sibling chain (T6 walk-guard test). */
    void unsafeSetNextSibling(int agentId, int slot, int value) {
        shard(agentId).nextSibling[slot] = value;
    }

    /** One agent's eight parallel arrays plus its ring write head. */
    private static final class Shard {
        final int[] parentIds;
        final int[] firstChild;
        final int[] nextSibling;
        final float[] successScores;
        final int[] stateHashes;
        final int[] sessionIds;
        final long[] timestamps;
        final float[] salienceScores;

        /** Monotonic ring counter. Starts at 1 so the first write lands on slot 1. */
        long writeHead = 1L;

        Shard(int shardSize, long bornAt, float rootBaseSalience) {
            parentIds = new int[shardSize];
            firstChild = new int[shardSize];
            nextSibling = new int[shardSize];
            successScores = new float[shardSize];
            stateHashes = new int[shardSize];
            sessionIds = new int[shardSize];
            timestamps = new long[shardSize];
            salienceScores = new float[shardSize];

            // FCNS "none" sentinel is -1, but Java zero-inits to 0 (eng-review A1).
            java.util.Arrays.fill(firstChild, -1);
            java.util.Arrays.fill(nextSibling, -1);

            // Reserve slot 0 as the synthetic root (eng-review A2). It is "written"
            // (non-zero timestamp) so empty-slot detection never reclaims it, its
            // parent is itself (path-to-root terminates at it), and it seeds the
            // salience that children inherit.
            parentIds[ROOT_SLOT] = ROOT_SLOT;
            timestamps[ROOT_SLOT] = (bornAt == 0L) ? 1L : bornAt;
            salienceScores[ROOT_SLOT] = rootBaseSalience;
        }
    }

    public MemoryConfig config() {
        return config;
    }
}
