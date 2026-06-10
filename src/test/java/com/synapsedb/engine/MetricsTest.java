package com.synapsedb.engine;

import com.synapsedb.core.MemoryConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that Micrometer metrics are recorded at the SynapseEngine boundary.
 */
class MetricsTest {

    @TempDir
    Path tmp;

    private MemoryConfig cfg() {
        return new MemoryConfig(4, 1024, 0.1f, 3_600_000L, 0.1f, 0.5f,
                tmp.toString(), tmp.toString(), 0.7f);
    }

    @Test
    @DisplayName("append timer increments after each appendThought call")
    void appendTimerIncrementsOnAppend() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (SynapseEngine engine = new SynapseEngine(cfg(), registry)) {
            engine.registerExistingAgent(0);
            engine.appendThought(0, 0, 1, 0.5f, 1);
            engine.appendThought(0, 0, 2, 0.5f, 1);
        }
        Timer timer = registry.find("synapse.append.latency").timer();
        assertNotNull(timer, "append timer must be registered");
        assertEquals(2, timer.count(), "timer count must equal number of appends");
    }

    @Test
    @DisplayName("best-next timer increments after each bestNext call")
    void bestNextTimerIncrements() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (SynapseEngine engine = new SynapseEngine(cfg(), registry)) {
            engine.registerExistingAgent(0);
            engine.appendThought(0, 0, 1, 0.8f, 1);
            engine.bestNext(0, 0, 1);
            engine.bestNext(0, 0, 1);
        }
        Timer timer = registry.find("synapse.bestnext.latency").timer();
        assertNotNull(timer, "best-next timer must be registered");
        assertEquals(2, timer.count());
    }

    @Test
    @DisplayName("fill gauge is registered for each agent and reflects stats().fillPercent()")
    void fillGaugeTracksStats() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (SynapseEngine engine = new SynapseEngine(cfg(), registry)) {
            engine.registerExistingAgent(0);
            // No appends yet — fill should be 0%.
            Gauge gauge = registry.find("synapse.shard.fill.percent")
                    .tag("agentId", "0").gauge();
            assertNotNull(gauge, "fill gauge must be registered for agentId=0");
            assertEquals(0.0, gauge.value(), 1e-9, "fill must be 0% before any appends");

            engine.appendThought(0, 0, 1, 0.5f, 1);
            assertTrue(gauge.value() > 0.0, "fill must be > 0 after an append");
        }
    }

    @Test
    @DisplayName("corrupt-skipped counter registers at engine construction (starts at 0)")
    void corruptSkippedCounterExists() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (SynapseEngine engine = new SynapseEngine(cfg(), registry)) {
            engine.registerExistingAgent(0);
            Counter counter = registry.find("synapse.bootstrap.corrupt.skipped").counter();
            assertNotNull(counter, "corrupt-skipped counter must be registered");
            assertEquals(0.0, counter.count(), "counter must start at 0");
        }
    }
}
