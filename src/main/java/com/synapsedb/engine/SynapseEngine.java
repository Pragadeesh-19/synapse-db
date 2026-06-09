package com.synapsedb.engine;

import com.synapsedb.core.BestNextResult;
import com.synapsedb.core.MemoryConfig;
import com.synapsedb.core.SynapseGraph;
import com.synapsedb.engine.exception.CapacityReachedException;
import com.synapsedb.engine.exception.InvalidParentException;
import com.synapsedb.engine.exception.InvalidRequestException;
import com.synapsedb.engine.exception.ThoughtNotFoundException;
import com.synapsedb.engine.exception.UnknownAgentException;
import com.synapsedb.persistence.AgentRingFile;
import com.synapsedb.persistence.RingFileHeader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestration facade over the lock-free core (Phase 4 eng-review D1). The ONLY place
 * that combines an in-memory {@code append()} with its on-disk {@code writeRecord()} —
 * controllers never touch {@link SynapseGraph} or {@link AgentRingFile} directly.
 *
 * <pre>
 * Append orchestration (under the per-agent lock):
 *
 *   graph.append() ──▶ slot
 *        │  (append is decoupled: it generates ts + salience internally,
 *        │   Phase 2 D1, and does NOT persist)
 *        ▼
 *   read back graph.timestampOf(slot), salienceOf(slot), writeHead(agent)
 *        │  (must be the EXACT values append produced, else the ring file
 *        ▼   diverges from memory and bootstrap replays wrong data)
 *   ringFile.writeRecord(slot, …, ts, salience, writeHead)
 * </pre>
 *
 * <h2>Concurrency (D2)</h2>
 * Tomcat is multi-threaded; the core is lock-free single-writer. Every mutation
 * ({@link #appendThought}, {@link #bootstrap}, {@link #registerNewAgent}) takes a
 * <b>per-agent</b> {@link ReentrantLock}, so different agents never block each other and
 * the lock is uncontended under normal single-writer-per-agent traffic. The lock lives
 * here, never in {@code SynapseGraph} (the core stays lock-free). Reads do NOT take the
 * lock (V1 single-client-per-agent contract; full safety is TODO {@code T-MULTIWRITER}).
 *
 * <h2>Validation (D4)</h2>
 * The core's {@code assert} guards are OFF in production. This facade is the trust
 * boundary: it validates every agent id / parent / slot BEFORE calling the core and
 * throws typed exceptions the API maps to clean 4xx responses.
 */
public final class SynapseEngine implements AutoCloseable {

    private final SynapseGraph graph;
    private final MemoryConfig config;
    private final Path dataDir;
    private final int shardSize;

    /**
     * Hard ceiling on a path-to-root request's buffer, independent of shardSize. With the
     * default 1M shard, an unbounded maxDepth would let one authenticated request allocate a
     * 4MB int[] even for a depth-3 path — a GC-pressure / DoS vector. A reasoning chain
     * deeper than this is implausible; the cap bounds the allocation to 256KB.
     */
    private static final int MAX_PATH_DEPTH = 65_536;

    private final Map<Integer, AgentRingFile> ringFiles = new ConcurrentHashMap<>();
    private final Map<Integer, ReentrantLock> locks = new ConcurrentHashMap<>();

    public SynapseEngine(MemoryConfig config) {
        this.config = config;
        this.graph = new SynapseGraph(config);
        this.dataDir = Path.of(config.dataDir());
        this.shardSize = config.shardSize();
    }

    // ── Agent lifecycle ──────────────────────────────────────────────────────

    /**
     * Open the ring file and register the shard for a known agent id (pre-seeded from
     * {@code api-keys.yml} at startup). Idempotent. Opens the ring file FIRST so a mmap
     * failure leaves no half-created agent (eng-review critical gap).
     */
    public void registerExistingAgent(int agentId) {
        ReentrantLock lock = lockFor(agentId);
        lock.lock();
        try {
            if (ringFiles.containsKey(agentId)) {
                return; // already registered
            }
            AgentRingFile rf = openRingFile(agentId); // FIRST — fail before publishing state
            graph.registerAgent(agentId);
            ringFiles.put(agentId, rf);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Allocate the next free agent id in {@code [0, maxAgents)}, open its ring file, and
     * register the shard. Returns the new id.
     *
     * @throws IllegalStateException if all {@code maxAgents} slots are occupied (server full)
     */
    public synchronized int registerNewAgent() {
        int agentId = -1;
        for (int id = 0; id < config.maxAgents(); id++) {
            if (!ringFiles.containsKey(id)) {
                agentId = id;
                break;
            }
        }
        if (agentId < 0) {
            throw new CapacityReachedException(config.maxAgents());
        }
        // openRingFile first, then publish — same fail-safe ordering as registerExistingAgent.
        AgentRingFile rf = openRingFile(agentId);
        graph.registerAgent(agentId);
        ringFiles.put(agentId, rf);
        return agentId;
    }

    public boolean isRegistered(int agentId) {
        return ringFiles.containsKey(agentId);
    }

    // ── Hot path: append + persist ─────────────────────────────────────────────

    /**
     * Append a thought and persist it atomically (per-agent lock). See class javadoc for
     * the read-back orchestration.
     */
    public AppendResult appendThought(int agentId, int parentId, int stateHash,
                                      float successScore, int sessionId) {
        ensureRegistered(agentId);
        validateSuccessScore(successScore);
        validateParentForAppend(agentId, parentId);

        ReentrantLock lock = lockFor(agentId);
        lock.lock();
        try {
            int slot = graph.append(agentId, parentId, stateHash, successScore, sessionId);
            // Read back the values append GENERATED — never recompute them here.
            long timestamp = graph.timestampOf(agentId, slot);
            float salience = graph.salienceOf(agentId, slot);
            long writeHead = graph.writeHead(agentId);

            ringFiles.get(agentId).writeRecord(
                    slot, parentId, stateHash, sessionId, successScore, salience, timestamp, writeHead);

            return new AppendResult(slot, salience, true);
        } finally {
            lock.unlock();
        }
    }

    // ── Reads (no lock — V1) ───────────────────────────────────────────────────

    /** Best next thought among {@code currentSlot}'s children. {@code currentSlot} may be the root (0). */
    public BestNextResult bestNext(int agentId, int currentSlot, int currentSessionId) {
        ensureRegistered(agentId);
        validateReadSlot(agentId, currentSlot, /*rootAllowed=*/true);
        return graph.getBestNextThought(agentId, currentSlot, currentSessionId);
    }

    /**
     * Backtrack from {@code fromSlot} toward the root into a freshly allocated buffer.
     * The root (slot 0) is the terminus and is NOT included. Returns {@code {path, depth}}.
     */
    public PathResult pathToRoot(int agentId, int fromSlot, int maxDepth) {
        ensureRegistered(agentId);
        int cap = Math.min(shardSize, MAX_PATH_DEPTH);
        if (maxDepth <= 0 || maxDepth > cap) {
            throw new InvalidRequestException(
                    "maxDepth must be in [1, " + cap + "]; got " + maxDepth);
        }
        validateReadSlot(agentId, fromSlot, /*rootAllowed=*/true);
        int[] buf = new int[maxDepth];
        int depth = graph.getPathToRoot(agentId, fromSlot, buf, maxDepth);
        int[] path = new int[depth];
        System.arraycopy(buf, 0, path, 0, depth);
        return new PathResult(path, depth);
    }

    /** Result of {@link #pathToRoot}. */
    public record PathResult(int[] path, int depth) {}

    public MemoryStats stats(int agentId) {
        ensureRegistered(agentId);
        long writeHead = graph.writeHead(agentId);
        long capacity = (long) shardSize - 1; // slot 0 reserved
        long appended = Math.max(0L, writeHead - 1L);
        long used = Math.min(appended, capacity);
        boolean wrapped = appended > capacity;
        double fill = capacity == 0 ? 0.0 : (100.0 * used) / capacity;
        return new MemoryStats(agentId, writeHead, used, capacity, fill, wrapped);
    }

    // ── Bootstrap (under lock) ─────────────────────────────────────────────────

    /** Reload the agent's shard from its ring file. Holds the per-agent write lock. */
    public RingFileHeader.Snapshot bootstrap(int agentId) {
        ensureRegistered(agentId);
        ReentrantLock lock = lockFor(agentId);
        lock.lock();
        try {
            return ringFiles.get(agentId).bootstrapInto(graph);
        } finally {
            lock.unlock();
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /** Unmap every ring file (Windows file-lock release). Called on context shutdown. */
    @Override
    public void close() {
        for (AgentRingFile rf : ringFiles.values()) {
            try {
                rf.close();
            } catch (Exception ignored) {
                // best-effort unmap on shutdown; nothing useful to do on failure
            }
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private AgentRingFile openRingFile(int agentId) {
        Path path = dataDir.resolve("agent-" + agentId + ".bin");
        try {
            return AgentRingFile.open(path, agentId, shardSize);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open ring file for agent " + agentId, e);
        }
    }

    private ReentrantLock lockFor(int agentId) {
        return locks.computeIfAbsent(agentId, k -> new ReentrantLock());
    }

    private void ensureRegistered(int agentId) {
        if (!isRegistered(agentId)) {
            throw new UnknownAgentException(agentId);
        }
    }

    /** successScore is the reinforcement signal; must be finite and within [-1, 1]. */
    private void validateSuccessScore(float successScore) {
        if (!Float.isFinite(successScore) || successScore < -1.0f || successScore > 1.0f) {
            throw new InvalidRequestException(
                    "successScore must be finite and within [-1.0, 1.0]; got " + successScore);
        }
    }

    /** Parent must be the root (0) or an in-range, already-written slot. */
    private void validateParentForAppend(int agentId, int parentId) {
        if (parentId < 0 || parentId >= shardSize) {
            throw new InvalidParentException(
                    "parent slot " + parentId + " out of range [0, " + (shardSize - 1) + "]");
        }
        if (parentId != SynapseGraph.ROOT_SLOT && !graph.isWritten(agentId, parentId)) {
            throw new InvalidParentException(
                    "parent slot " + parentId + " is empty (never-written) for agent " + agentId);
        }
    }

    /** Read slot must be in range; if not the root, it must hold a written thought. */
    private void validateReadSlot(int agentId, int slot, boolean rootAllowed) {
        if (slot < 0 || slot >= shardSize) {
            throw new InvalidRequestException(
                    "slot " + slot + " out of range [0, " + (shardSize - 1) + "]");
        }
        if (slot == SynapseGraph.ROOT_SLOT) {
            if (!rootAllowed) {
                throw new InvalidRequestException("root slot 0 is not a valid target here");
            }
            return;
        }
        if (!graph.isWritten(agentId, slot)) {
            throw new ThoughtNotFoundException(agentId, slot);
        }
    }
}
