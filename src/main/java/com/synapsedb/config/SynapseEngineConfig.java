package com.synapsedb.config;

import com.synapsedb.core.MemoryConfig;
import com.synapsedb.engine.SynapseEngine;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean wiring for the engine layer.
 *
 * <pre>
 *   MemoryConfig.fromEnv()  ──▶  SynapseEngine (owns graph + ring-file registry + locks)
 *                                      ▲
 *   ApiKeyConfigLoader.seededAgentIds() ── seedAgents runner registers pre-seeded shards
 * </pre>
 */
@Configuration
public class SynapseEngineConfig {

    private static final Logger log = LoggerFactory.getLogger(SynapseEngineConfig.class);

    @Bean
    public MemoryConfig memoryConfig() {
        return MemoryConfig.fromEnv();
    }

    /** {@code close()} unmaps every ring file on shutdown (Windows file-lock release). */
    @Bean(destroyMethod = "close")
    public SynapseEngine synapseEngine(MemoryConfig memoryConfig, MeterRegistry meterRegistry) {
        return new SynapseEngine(memoryConfig, meterRegistry);
    }

    /**
     * Open ring files + register shards for every agent pre-seeded from api-keys.yml. Data
     * is NOT auto-replayed — call POST /bootstrap to reload an agent's thoughts from disk.
     */
    @Bean
    public ApplicationRunner seedAgents(SynapseEngine engine, ApiKeyConfigLoader keys) {
        return args -> {
            int seeded = 0;
            for (Integer agentId : keys.seededAgentIds()) {
                engine.registerExistingAgent(agentId);
                seeded++;
            }
            if (seeded > 0) {
                log.info("Registered {} pre-seeded agent shard(s). Call POST /bootstrap to reload persisted thoughts.",
                        seeded);
            }
        };
    }
}
