package com.synapsedb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Synapse-DB entrypoint. In-memory agent-reasoning graph over memory-mapped ring files —
 * no external database. See CLAUDE.md for the architecture.
 */
@SpringBootApplication
public class SynapseDbApplication {

    public static void main(String[] args) {
        SpringApplication.run(SynapseDbApplication.class, args);
    }
}
