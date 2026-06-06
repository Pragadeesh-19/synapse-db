package com.synapsedb.bench;

import com.synapsedb.core.MemoryConfig;
import com.synapsedb.core.SynapseGraph;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Phase 1 exit-gate: single-agent append throughput.
 *
 * <p>Target: {@code > 1,000,000 ops/sec} (i.e. each append {@code < 1 µs}).
 *
 * <pre>
 * Steady-state profile:
 *   First SHARD_SIZE-1 appends → no eviction, plain array writes.
 *   Subsequent appends         → ring wrap, FCNS eviction reset, then plain writes.
 * Both phases are covered as soon as the ring wraps (~1 second at target throughput).
 *
 * If append exceeds 1 µs, diagnose in this order:
 *   1. FCNS lock contention (single-writer V1 has none — should be a NOP)
 *   2. mmap force() being called (must not happen on the hot path)
 *   3. Heap allocation in the hot path (check with -prof gc)
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class AppendBenchmark {

    private static final int AGENT = 0;

    private SynapseGraph graph;
    // Vary stateHash every call so the JIT cannot constant-fold the entire body.
    private int counter;

    @Setup(Level.Trial)
    public void setUp() {
        graph = new SynapseGraph(MemoryConfig.defaults());
        graph.registerAgent(AGENT);
        counter = 0;
    }

    /**
     * Hot path: always append under root.  Measures the ring-buffer write,
     * FCNS prepend, and salience seed with the minimum overhead of tree depth.
     */
    @Benchmark
    public int appendToRoot() {
        return graph.append(AGENT, SynapseGraph.ROOT_SLOT, counter++, 0.5f, 1);
    }
}
