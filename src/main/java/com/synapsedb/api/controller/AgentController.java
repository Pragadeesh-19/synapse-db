package com.synapsedb.api.controller;

import com.synapsedb.api.auth.AgentKeyRecord;
import com.synapsedb.api.dto.RegisterAgentRequest;
import com.synapsedb.api.dto.RegisterAgentResponse;
import com.synapsedb.config.ApiKeyConfigLoader;
import com.synapsedb.engine.SynapseEngine;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * POST /api/v1/agents — register a new agent.
 *
 * <p>This endpoint is on the auth filter's allowlist (no key exists before registration).
 * It allocates an agent id + ring file via the engine, mints a raw key, stores ONLY its
 * hash in memory (D3), and returns the raw key exactly once.
 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {

    private final SynapseEngine engine;
    private final ApiKeyConfigLoader keys;

    public AgentController(SynapseEngine engine, ApiKeyConfigLoader keys) {
        this.engine = engine;
        this.keys = keys;
    }

    @PostMapping
    public ResponseEntity<RegisterAgentResponse> register(
            @RequestBody(required = false) RegisterAgentRequest request) {

        int agentId = engine.registerNewAgent(); // opens ring file + shard (or 503 if full)
        String label = (request == null) ? null : request.label();
        String rawKey = "sk_syn_" + UUID.randomUUID().toString().replace("-", "");
        keys.registerRuntimeKey(rawKey, new AgentKeyRecord(agentId, label));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RegisterAgentResponse(agentId, rawKey));
    }
}
