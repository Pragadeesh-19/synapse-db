package com.synapsedb.bench;

import com.synapsedb.core.MemoryConfig;
import com.synapsedb.core.SynapseGraph;
import com.synapsedb.persistence.AgentRingFile;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Phase 2 exit-gate: bootstrap 1M records from a ring file into a fresh graph.
 *
 * <p>Target: {@code < 200 ms} (CLAUDE.md Phase 2 exit gate).
 *
 * <p>Benchmark structure:
 * <ul>
 *   <li>Trial setup: pre-fill a temp ring file with {@code SHARD_SIZE - 1 = 1,048,575}
 *       records (one full ring minus the reserved root).</li>
 *   <li>Iteration setup: open a fresh graph + the pre-filled ring file.</li>
 *   <li>Benchmark: call {@link AgentRingFile#bootstrapInto} — measures the raw-load +
 *       FCNS rebuild + writeHead-restore path cold.</li>
 *   <li>Iteration teardown: close the ring file.</li>
 *   <li>Trial teardown: delete the temp file.</li>
 * </ul>
 *
 * <p>The benchmark uses {@link Mode#SingleShotTime} (milliseconds) because bootstrap
 * is a once-per-startup operation, not a throughput path.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
public class BootstrapBenchmark {

    private static final int AGENT = 0;

    private MemoryConfig config;
    private Path tempFile;

    private SynapseGraph graph;
    private AgentRingFile ringFile;

    @Setup(Level.Trial)
    public void setUpTrial() throws Exception {
        config   = MemoryConfig.defaults();
        tempFile = Files.createTempFile("synapse-bootstrap-bench-", ".bin");
        Files.delete(tempFile); // createTempFile leaves a zero-byte file; delete so open() creates it fresh

        // Pre-fill the ring file: a linear chain root → 1 → 2 → … → SHARD_SIZE-1.
        SynapseGraph fill = new SynapseGraph(config);
        fill.registerAgent(AGENT);
        try (AgentRingFile rf = AgentRingFile.open(tempFile, AGENT, config.shardSize())) {
            int parent = SynapseGraph.ROOT_SLOT;
            long baseTs = System.currentTimeMillis();
            for (int i = 1; i < config.shardSize(); i++) {
                int slot = fill.append(AGENT, parent, i, 0.5f, 1);
                rf.writeRecord(slot,
                        fill.parentOf(AGENT, slot),
                        i, 1,
                        fill.successScoreOf(AGENT, slot),
                        fill.salienceOf(AGENT, slot),
                        baseTs + i,
                        fill.writeHead(AGENT));
                parent = slot;
            }
        }
    }

    @Setup(Level.Iteration)
    public void setUpIteration() throws Exception {
        graph    = new SynapseGraph(config);          // fresh, empty shard
        ringFile = AgentRingFile.open(tempFile, AGENT, config.shardSize());
    }

    @Benchmark
    public long bootstrap() throws Exception {
        ringFile.bootstrapInto(graph);
        return graph.writeHead(AGENT); // consume result to prevent DCE
    }

    @TearDown(Level.Iteration)
    public void tearDownIteration() throws Exception {
        if (ringFile != null) {
            ringFile.close();
            ringFile = null;
        }
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() throws Exception {
        Files.deleteIfExists(tempFile);
    }
}
