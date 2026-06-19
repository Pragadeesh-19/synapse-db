package com.synapsedb.core;

import java.util.function.LongSupplier;

/**
 * In-memory reasoning graph: eight parallel primitive arrays per agent
 * (Struct-of-Arrays) with a first-child / next-sibling (FCNS) tree and a
 * ring buffer write head. Single-writer-per-shard; multi-writer is V2
 * (see TODOS.md {@code T-MULTIWRITER}).
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

    // null until registerAgent(int)
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

    /** Lazily allocate an agent's ~36MB slice. Idempotent. */
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
     * <p>{@code parentSlot} is validated by a debug assert only: the trust boundary
     * is the API layer; the hot path stays unchecked in production builds.
     */
    public int append(int agentId, int parentSlot, int stateHash, float successScore, int sessionId) {
        Shard s = shard(agentId);

        assert parentSlot >= 0 && parentSlot < shardSize
                : "parentSlot out of range: " + parentSlot;
        assert parentSlot == ROOT_SLOT || s.timestamps[parentSlot] != 0L
                : "parentSlot points to an empty (never-written) slot: " + parentSlot;

        long wh = s.writeHead;
        int slot = (int) (wh & shardMask);
        if (slot == ROOT_SLOT) {
            wh++;            // skip the reserved root slot on wrap
            slot = 1;
        }
        s.writeHead = wh + 1;

        assert parentSlot != slot : "self-parent: slot " + slot + " cannot parent itself";

        // Eviction: drop outgoing FCNS links. Stale chains pointing TO this slot are a
        // V1 known limitation; the bounded sibling walk prevents them from hanging a reader.
        if (s.timestamps[slot] != 0L) {
            s.firstChild[slot] = -1;
            s.nextSibling[slot] = -1;
        }

        s.parentIds[slot] = parentSlot;
        s.stateHashes[slot] = stateHash;
        s.sessionIds[slot] = sessionId;
        s.successScores[slot] = successScore;
        s.timestamps[slot] = clock.getAsLong();
        s.salienceScores[slot] = seedSalience(s.salienceScores[parentSlot], successScore);

        // FCNS prepend — order is load-bearing: link new→old first, THEN parent→new.
        s.firstChild[slot] = -1;
        s.nextSibling[slot] = s.firstChild[parentSlot]; // step 1
        s.firstChild[parentSlot] = slot;                // step 2

        return slot;
    }

    private float seedSalience(float parentSalience, float successScore) {
        float updated = parentSalience + learningRate * successScore;
        if (updated < 0f) return 0f;
        if (updated > 1f) return 1f;
        return updated;
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    /**
     * Backtrack from {@code fromSlot} toward the root into the caller-provided {@code out}
     * buffer (zero allocation). Root slot 0 is the terminus and is not included. Returns
     * the count written; bounded by {@code min(out.length, maxDepth)}.
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
     * Count the children of {@code parentSlot} via a bounded FCNS sibling walk.
     * Guard caps at {@code shardSize} so a cyclic chain from ring eviction cannot hang.
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

    /**
     * Walk the FCNS sibling chain from {@code currentSlot}'s first child and return
     * the child with the highest Hebbian score. O(degree). Walk is bounded at
     * {@code shardSize} — not a smaller cap — so high-degree nodes are never silently
     * truncated. Returns {@link BestNextResult#NONE} when no valid child exists.
     */
    public BestNextResult getBestNextThought(int agentId, int currentSlot, int currentSessionId) {
        Shard s = shard(agentId);
        long now = clock.getAsLong();           // read once so all children score against the same instant
        float lambda = config.lambda();
        long decayUnitMs = config.decayUnitMs();
        int bestSlot = -1;
        float bestScore = Float.NEGATIVE_INFINITY;
        int child = s.firstChild[currentSlot];
        int guard = 0;
        while (child != -1 && guard++ < shardSize) {
            if (s.timestamps[child] != 0L) {          // timestamps[slot] == 0 is the empty-slot predicate
                float sc = HebbianScorer.score(
                        s.successScores[child], s.salienceScores[child],
                        s.timestamps[child], s.sessionIds[child],
                        now, currentSessionId, lambda, decayUnitMs);
                if (sc > bestScore) {
                    bestScore = sc;
                    bestSlot = child;
                }
            }
            child = s.nextSibling[child];
        }
        return bestSlot == -1 ? BestNextResult.NONE : new BestNextResult(bestSlot, bestScore);
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

    public long timestampOf(int agentId, int slot) {
        return shard(agentId).timestamps[slot];
    }

    public int sessionIdOf(int agentId, int slot) {
        return shard(agentId).sessionIds[slot];
    }

    public int stateHashOf(int agentId, int slot) {
        return shard(agentId).stateHashes[slot];
    }

    public float successScoreOf(int agentId, int slot) {
        return shard(agentId).successScores[slot];
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

    // ── Persistence support ──────────────────────────────────────────────────

    /**
     * Raw-load one persisted record into the agent's arrays without recomputing any derived
     * field. Salience and timestamp are restored as-is. FCNS arrays are left at -1 until
     * {@link #rebuildFcns} is called after all slots are loaded.
     */
    public void loadSlot(int agentId, int slot, int parentSlot, int stateHash,
                         int sessionId, float successScore, float salienceScore,
                         long timestamp) {
        Shard s = shard(agentId);
        s.parentIds[slot]      = parentSlot;
        s.stateHashes[slot]    = stateHash;
        s.sessionIds[slot]     = sessionId;
        s.successScores[slot]  = successScore;
        s.salienceScores[slot] = salienceScore;
        s.timestamps[slot]     = timestamp;
        // firstChild and nextSibling stay -1 until rebuildFcns()
    }

    /**
     * Rebuild {@code firstChild} and {@code nextSibling} from {@code parentIds} after all
     * {@link #loadSlot} calls complete. Ascending slot order means the newest written slot
     * ends up as {@code firstChild}, matching live-engine prepend order.
     */
    public void rebuildFcns(int agentId) {
        Shard s = shard(agentId);
        // Reset first so no stale FCNS state leaks from a prior in-memory session.
        java.util.Arrays.fill(s.firstChild,  -1);
        java.util.Arrays.fill(s.nextSibling, -1);
        for (int slot = 1; slot < shardSize; slot++) {
            if (s.timestamps[slot] == 0L) continue;
            int parent = s.parentIds[slot];
            s.nextSibling[slot] = s.firstChild[parent]; // FCNS step 1
            s.firstChild[parent] = slot;                 // FCNS step 2
        }
    }

    /** Restore the ring write head from the persisted header so the next append continues at the right slot. */
    public void restoreWriteHead(int agentId, long writeHead) {
        shard(agentId).writeHead = writeHead;
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

            // FCNS sentinel is -1, but Java zero-inits int[] to 0 — fill explicitly.
            java.util.Arrays.fill(firstChild, -1);
            java.util.Arrays.fill(nextSibling, -1);

            // Slot 0 is the synthetic root: always "written" (non-zero timestamp) so
            // empty-slot detection never reclaims it, and the path-to-root walk terminates
            // at it. Its salience seeds the Hebbian weight that children inherit.
            parentIds[ROOT_SLOT] = ROOT_SLOT;
            timestamps[ROOT_SLOT] = (bornAt == 0L) ? 1L : bornAt;
            salienceScores[ROOT_SLOT] = rootBaseSalience;
        }
    }

    public MemoryConfig config() {
        return config;
    }
}
