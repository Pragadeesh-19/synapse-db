package com.synapsedb.bench;

import com.synapsedb.core.BestNextResult;
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
 * Phase 3 exit-gate: Hebbian best-next at degree 5.
 *
 * <p>Target: {@code < 5 µs} average.
 *
 * <pre>
 * Tree shape:
 *   root ← 5 children with varying success scores and different sessions
 *           (prevents JIT from constant-folding the scoring loop)
 *
 * The benchmark exercises the full hot path:
 *   clock.getAsLong() + FCNS walk + 5 × HebbianScorer.score() + winner tracking
 * </pre>
 *
 * <p>See TODOS.md {@code T-BESTNEXT-DEGREE-BENCH} for the deferred degree-50/500
 * O(degree) scaling benchmark (not an exit gate for Phase 3).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class BestNextBenchmark {

    private static final int AGENT       = 0;
    private static final int CUR_SESSION = 1;
    private static final int DEGREE      = 5;

    private SynapseGraph graph;

    @Setup(Level.Trial)
    public void setUp() {
        graph = new SynapseGraph(MemoryConfig.defaults());
        graph.registerAgent(AGENT);
        for (int i = 0; i < DEGREE; i++) {
            // Vary success and session so the JIT cannot elide the scoring loop.
            float success = 0.2f + i * 0.15f;
            int session = (i % 2 == 0) ? CUR_SESSION : CUR_SESSION + 1;
            graph.append(AGENT, SynapseGraph.ROOT_SLOT, i * 1000, success, session);
        }
    }

    /** Phase 3 exit gate: degree-5 Hebbian walk must be {@code < 5 µs}. */
    @Benchmark
    public BestNextResult getBestNext_degree5() {
        return graph.getBestNextThought(AGENT, SynapseGraph.ROOT_SLOT, CUR_SESSION);
    }
}
