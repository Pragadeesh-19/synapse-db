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
 * Phase 1 exit-gate: FCNS sibling walk at varying degrees.
 *
 * <p>Verifies two things:
 * <ol>
 *   <li>The walk is {@code O(degree)}, not {@code O(n)} — degree-50 should take
 *       roughly 10× longer than degree-5, not 200,000× longer (n=1M).</li>
 *   <li>Degree-5 walk is well under the 5 µs best-next target so Hebbian scoring
 *       (Phase 3) has budget to spend on the score formula.</li>
 * </ol>
 *
 * <pre>
 * Tree shape built in setUp():
 *
 *   root
 *    ├── parentSmall  ←── 5 leaf children
 *    └── parentLarge  ←── 50 leaf children
 *
 * Each benchmark call traverses one sibling chain from firstChild to -1.
 *
 * O(degree) check: degree50_µs / degree5_µs should be ≈ 10 (±5×).
 * If the ratio is ~1 the JIT constant-folded the loop body.
 * If the ratio is ~200,000 the implementation fell back to an O(n) scan.
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class WalkBenchmark {

    private static final int DEGREE_SMALL = 5;
    private static final int DEGREE_LARGE = 50;

    private SynapseGraph graph;
    private int parentSmall;
    private int parentLarge;

    @Setup(Level.Trial)
    public void setUp() {
        graph = new SynapseGraph(MemoryConfig.defaults());
        graph.registerAgent(0);
        // parentSmall is the first child of root; its children immediately follow.
        parentSmall = graph.append(0, SynapseGraph.ROOT_SLOT, 0, 0.5f, 1);
        for (int i = 0; i < DEGREE_SMALL; i++) {
            graph.append(0, parentSmall, i, 0.5f, 1);
        }
        // parentLarge and its children are further along the ring.
        parentLarge = graph.append(0, SynapseGraph.ROOT_SLOT, 1, 0.5f, 1);
        for (int i = 0; i < DEGREE_LARGE; i++) {
            graph.append(0, parentLarge, i, 0.5f, 1);
        }
    }

    /** O(degree) baseline — degree 5. */
    @Benchmark
    public int walkDegree5() {
        return graph.countChildren(0, parentSmall);
    }

    /** O(degree) validation — degree 50 should cost ~10× degree-5, not ~200,000×. */
    @Benchmark
    public int walkDegree50() {
        return graph.countChildren(0, parentLarge);
    }
}
