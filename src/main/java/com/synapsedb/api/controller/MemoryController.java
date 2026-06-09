package com.synapsedb.api.controller;

import com.synapsedb.api.dto.BootstrapResponse;
import com.synapsedb.api.dto.StatsResponse;
import com.synapsedb.engine.SynapseEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Memory-management endpoints under /api/v1/agents/{id}: reload from the ring file and read
 * occupancy stats. Thin delegation to {@link SynapseEngine}.
 */
@RestController
@RequestMapping("/api/v1/agents/{id}")
public class MemoryController {

    private final SynapseEngine engine;

    public MemoryController(SynapseEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/bootstrap")
    public BootstrapResponse bootstrap(@PathVariable int id) {
        return BootstrapResponse.from(engine.bootstrap(id));
    }

    @GetMapping("/memory/stats")
    public StatsResponse stats(@PathVariable int id) {
        return StatsResponse.from(engine.stats(id));
    }
}
