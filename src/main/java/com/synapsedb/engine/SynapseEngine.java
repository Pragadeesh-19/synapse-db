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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestration facade over the lock-free core. The only place that combines an in-memory
 * {@code append()} with its on-disk {@code writeRecord()} — controllers never touch
 * {@link SynapseGraph} or {@link AgentRingFile} directly.
 *
 * <h2>Concurrency</h2>
 * Tomcat is multi-threaded; the core is lock-free single-writer. Every mutation takes a
 * <b>per-agent</b> {@link ReentrantLock}, so different agents never block each other.
 * The lock lives here, never in {@code SynapseGraph}.
 *
 * <h2>Metrics</h2>
 * Timers and counters are registered at this boundary, not inside {@code SynapseGraph.append()}
 * — the pure core stays allocation-free.
 */
public final class SynapseEngine implements AutoCloseable {

    private final SynapseGraph graph;
    private final MemoryConfig config;
    private final Path dataDir;
    private final int shardSize;
    private final MeterRegistry meterRegistry;

    /**
     * Hard ceiling on a path-to-root request's buffer, independent of shardSize. With the
     * default 1M shard, an unbounded maxDepth would let one authenticated request allocate a
     * 4MB int[] even for a depth-3 path — a GC-pressure / DoS vector. A reasoning chain
     * deeper than this is implausible; the cap bounds the allocation to 256KB.
     */
    private static final int MAX_PATH_DEPTH = 65_536;

    private final Map<Integer, AgentRingFile> ringFiles = new ConcurrentHashMap<>();
    private final Map<Integer, ReentrantLock> locks = new ConcurrentHashMap<>();

    // Metrics
    private final Timer appendTimer;
    private final Timer bestNextTimer;
    private final Counter corruptSkippedCounter;
    private final Counter ringfileOpenFailureCounter;

    /** Injects a {@link MeterRegistry} from Spring Boot's Micrometer auto-configuration. */
    public SynapseEngine(MemoryConfig config, MeterRegistry meterRegistry) {
        this.config = config;
        this.graph = new SynapseGraph(config);
        this.dataDir = Path.of(config.dataDir());
        this.shardSize = config.shardSize();
        this.meterRegistry = meterRegistry;

        this.appendTimer = Timer.builder("synapse.append.latency")
                .description("Time to append a thought and persist to ring file (lock + core + mmap)")
                .register(meterRegistry);
        this.bestNextTimer = Timer.builder("synapse.bestnext.latency")
                .description("Time to find best next thought (FCNS walk + Hebbian scoring)")
                .register(meterRegistry);
        this.corruptSkippedCounter = Counter.builder("synapse.bootstrap.corrupt.skipped")
                .description("Records skipped during bootstrap due to CRC32C mismatch (torn writes)")
                .register(meterRegistry);
        this.ringfileOpenFailureCounter = Counter.builder("synapse.ringfile.open.failures")
                .description("Failures to open or mmap an agent ring file at startup or registration")
                .register(meterRegistry);
    }

    /** Constructor for tests and benchmarks — uses a no-op {@link SimpleMeterRegistry}. */
    public SynapseEngine(MemoryConfig config) {
        this(config, new SimpleMeterRegistry());
    }

    // ── Agent lifecycle ──────────────────────────────────────────────────────

    /** Open the ring file and register the shard for a known agent id. Idempotent. */
    public void registerExistingAgent(int agentId) {
        ReentrantLock lock = lockFor(agentId);
        lock.lock();
        try {
            if (ringFiles.containsKey(agentId)) {
                return;
            }
            AgentRingFile rf = openRingFile(agentId);
            graph.registerAgent(agentId);
            ringFiles.put(agentId, rf);
            registerFillGauge(agentId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Allocate the next free agent id, open its ring file, and register the shard.
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
        AgentRingFile rf = openRingFile(agentId);
        graph.registerAgent(agentId);
        ringFiles.put(agentId, rf);
        registerFillGauge(agentId);
        return agentId;
    }

    public boolean isRegistered(int agentId) {
        return ringFiles.containsKey(agentId);
    }

    // ── Hot path: append + persist ─────────────────────────────────────────────

    /** Append a thought and persist it atomically under the per-agent lock. */
    public AppendResult appendThought(int agentId, int parentId, int stateHash,
                                      float successScore, int sessionId) {
        ensureRegistered(agentId);
        validateSuccessScore(successScore);
        validateParentForAppend(agentId, parentId);

        return appendTimer.record(() -> {
            ReentrantLock lock = lockFor(agentId);
            lock.lock();
            try {
                int slot = graph.append(agentId, parentId, stateHash, successScore, sessionId);
                long timestamp = graph.timestampOf(agentId, slot);
                float salience = graph.salienceOf(agentId, slot);
                long writeHead = graph.writeHead(agentId);

                ringFiles.get(agentId).writeRecord(
                        slot, parentId, stateHash, sessionId, successScore, salience, timestamp, writeHead);

                return new AppendResult(slot, salience, true);
            } finally {
                lock.unlock();
            }
        });
    }

    // ── Reads (no lock — V1) ───────────────────────────────────────────────────

    /** Best next thought among {@code currentSlot}'s children. */
    public BestNextResult bestNext(int agentId, int currentSlot, int currentSessionId) {
        ensureRegistered(agentId);
        validateReadSlot(agentId, currentSlot, /*rootAllowed=*/true);
        return bestNextTimer.record(() ->
                graph.getBestNextThought(agentId, currentSlot, currentSessionId));
    }

    /** Backtrack from {@code fromSlot} toward the root. */
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
        long capacity = (long) shardSize - 1;
        long appended = Math.max(0L, writeHead - 1L);
        long used = Math.min(appended, capacity);
        boolean wrapped = appended > capacity;
        double fill = capacity == 0 ? 0.0 : (100.0 * used) / capacity;
        return new MemoryStats(agentId, writeHead, used, capacity, fill, wrapped);
    }

    // ── Bootstrap (under lock) ─────────────────────────────────────────────────

    /** Reload the agent's shard from its ring file. */
    public RingFileHeader.Snapshot bootstrap(int agentId) {
        ensureRegistered(agentId);
        ReentrantLock lock = lockFor(agentId);
        lock.lock();
        try {
            AgentRingFile.BootstrapResult result = ringFiles.get(agentId).bootstrapInto(graph);
            if (result.corruptSkipped() > 0) {
                corruptSkippedCounter.increment(result.corruptSkipped());
            }
            return result.snap();
        } finally {
            lock.unlock();
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    public void close() {
        for (AgentRingFile rf : ringFiles.values()) {
            try {
                rf.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private AgentRingFile openRingFile(int agentId) {
        Path path = dataDir.resolve("agent-" + agentId + ".bin");
        try {
            return AgentRingFile.open(path, agentId, shardSize);
        } catch (IOException e) {
            ringfileOpenFailureCounter.increment();
            throw new UncheckedIOException("failed to open ring file for agent " + agentId, e);
        } catch (RuntimeException e) {
            ringfileOpenFailureCounter.increment();
            throw e;
        }
    }

    private void registerFillGauge(int agentId) {
        Gauge.builder("synapse.shard.fill.percent", () -> {
                    if (!isRegistered(agentId)) return 0.0;
                    try {
                        return stats(agentId).fillPercent();
                    } catch (Exception ignored) {
                        return 0.0;
                    }
                })
                .description("Current fill percentage of agent's ring shard (0–100)")
                .tag("agentId", String.valueOf(agentId))
                .register(meterRegistry);
    }

    private ReentrantLock lockFor(int agentId) {
        return locks.computeIfAbsent(agentId, k -> new ReentrantLock());
    }

    private void ensureRegistered(int agentId) {
        if (!isRegistered(agentId)) {
            throw new UnknownAgentException(agentId);
        }
    }

    private void validateSuccessScore(float successScore) {
        if (!Float.isFinite(successScore) || successScore < -1.0f || successScore > 1.0f) {
            throw new InvalidRequestException(
                    "successScore must be finite and within [-1.0, 1.0]; got " + successScore);
        }
    }

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
