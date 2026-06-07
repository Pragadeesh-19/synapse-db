package com.synapsedb.bench;

import org.junit.jupiter.api.Test;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Collection;

/**
 * Phase 1 exit-gate runner.  Invoked via:
 *
 * <pre>
 *   mvn test -Dtest=SynapseGraphBenchmarkTest
 * </pre>
 *
 * <p>Override the fork count for quick iteration without sacrificing JVM isolation
 * for the gate run:
 *
 * <pre>
 *   # Quick smoke (same JVM, ~30s)
 *   mvn test -Dtest=SynapseGraphBenchmarkTest -Djmh.forks=0
 *
 *   # Accurate gate run (fresh JVM per benchmark, ~3–5 min)
 *   mvn test -Dtest=SynapseGraphBenchmarkTest          # forks=1 default
 * </pre>
 *
 * Results are also written to {@code target/jmh-phase1-results.txt}.
 *
 * <h2>Phase 1 targets</h2>
 * <ul>
 *   <li>append throughput : {@code > 1,000,000 ops/sec}</li>
 *   <li>path-to-root (50) : {@code < 10 µs} average</li>
 *   <li>walk degree-5     : {@code < 5 µs} average (leaves budget for Phase 3 scoring)</li>
 *   <li>walk degree-50    : {@code ≈ 10×} degree-5 (confirms O(degree), not O(n))</li>
 * </ul>
 *
 * <p>This test does NOT assert on results because JMH numbers depend on hardware.
 * Review the printed table manually against the targets above.
 */
class SynapseGraphBenchmarkTest {

    @Test
    void runPhase1Benchmarks() throws Exception {
        int forks = Integer.getInteger("jmh.forks", 1);

        Options opts = new OptionsBuilder()
                .include(AppendBenchmark.class.getSimpleName())
                .include(PathToRootBenchmark.class.getSimpleName())
                .include(WalkBenchmark.class.getSimpleName())
                .include(BootstrapBenchmark.class.getSimpleName())
                .forks(forks)
                .resultFormat(ResultFormatType.TEXT)
                .result("target/jmh-phase1-results.txt")
                .build();

        Collection<RunResult> results = new Runner(opts).run();

        // Print a compact summary table so gate pass/fail is visible at a glance.
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║          Synapse-DB Phase 1 Benchmark Results                    ║");
        System.out.println("╠═══════════════════════════════════╦═══════════════╦══════════════╣");
        System.out.println("║ Benchmark                         ║     Score     ║    Target    ║");
        System.out.println("╠═══════════════════════════════════╬═══════════════╬══════════════╣");

        for (RunResult r : results) {
            String full = r.getParams().getBenchmark();
            String name = full.substring(full.lastIndexOf('.') + 1);
            double score = r.getPrimaryResult().getScore();
            String unit = r.getPrimaryResult().getScoreUnit();
            String target = targetFor(name);
            System.out.printf("║ %-33s ║ %9.2f %-3s ║ %-12s ║%n", name, score, unit, target);
        }

        System.out.println("╚═══════════════════════════════════╩═══════════════╩══════════════╝");
        System.out.println("Full results → target/jmh-phase1-results.txt");

        double degree5 = scoreFor(results, "walkDegree5");
        double degree50 = scoreFor(results, "walkDegree50");
        if (degree5 > 0 && degree50 > 0) {
            double ratio = degree50 / degree5;
            System.out.printf("%nO(degree) ratio  degree50/degree5 = %.1fx  (expect ~10x, not ~200000x)%n", ratio);
        }
    }

    private static String targetFor(String name) {
        return switch (name) {
            case "appendToRoot"  -> ">1M ops/s";
            case "pathToRoot"    -> "<10 µs";
            case "walkDegree5"   -> "<5 µs";
            case "walkDegree50"  -> "~10x deg5";
            case "bootstrap"     -> "<200 ms";
            default              -> "—";
        };
    }

    private static double scoreFor(Collection<RunResult> results, String suffix) {
        return results.stream()
                .filter(r -> r.getParams().getBenchmark().endsWith(suffix))
                .mapToDouble(r -> r.getPrimaryResult().getScore())
                .findFirst()
                .orElse(0.0);
    }
}
