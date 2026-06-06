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
 * Phase 1 exit-gate: path-to-root at depth 50.
 *
 * <p>Target: {@code < 10 µs} average.
 *
 * <pre>
 * Setup builds a single linear chain:
 *   root → slot1 → slot2 → … → slot50 (leafSlot)
 *
 * Each benchmark call walks leafSlot → root via parentIds[ptr] — pure
 * array reads, no pointer chasing.  The output buffer is pre-allocated
 * (caller-provided, per eng-review P1) so there is zero allocation on
 * the measured path.
 *
 * If path-to-root exceeds 10 µs at depth 50, the read bottleneck is
 * L2/L3 cache misses: parentIds[] entries for a deep chain are spread
 * across 50 different cache lines.  Expected to be well within target
 * because slots 1–50 are contiguous at the start of the shard.
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class PathToRootBenchmark {

    public static final int DEPTH = 50;

    private SynapseGraph graph;
    private int leafSlot;
    /** Pre-allocated caller buffer — never re-allocated during the benchmark. */
    private int[] pathBuffer;

    @Setup(Level.Trial)
    public void setUp() {
        graph = new SynapseGraph(MemoryConfig.defaults());
        graph.registerAgent(0);
        // Build a linear chain of DEPTH nodes.
        int parent = SynapseGraph.ROOT_SLOT;
        for (int i = 0; i < DEPTH; i++) {
            parent = graph.append(0, parent, i, 0.5f, 1);
        }
        leafSlot = parent;
        pathBuffer = new int[DEPTH + 1]; // +1 so maxDepth never truncates
    }

    @Benchmark
    public int pathToRoot() {
        // Returns the number of slots written — consume it to prevent DCE.
        return graph.getPathToRoot(0, leafSlot, pathBuffer, pathBuffer.length);
    }
}
