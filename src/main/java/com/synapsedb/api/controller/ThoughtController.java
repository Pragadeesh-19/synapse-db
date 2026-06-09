package com.synapsedb.api.controller;

import com.synapsedb.api.dto.AppendThoughtRequest;
import com.synapsedb.api.dto.AppendThoughtResponse;
import com.synapsedb.api.dto.BestNextResponse;
import com.synapsedb.api.dto.PathToRootResponse;
import com.synapsedb.engine.SynapseEngine;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thought-scoped endpoints under /api/v1/agents/{id}/thoughts. Thin: every call delegates
 * to {@link SynapseEngine}, which owns validation + the append+persist+lock invariant. The
 * {@code {id}} path variable is already proven == the authenticated agent by the auth filter.
 */
@RestController
@RequestMapping("/api/v1/agents/{id}/thoughts")
public class ThoughtController {

    private final SynapseEngine engine;

    public ThoughtController(SynapseEngine engine) {
        this.engine = engine;
    }

    @PostMapping
    public ResponseEntity<AppendThoughtResponse> append(
            @PathVariable int id,
            @Valid @RequestBody AppendThoughtRequest req) {
        var result = engine.appendThought(
                id, req.parentId(), req.stateHash(), req.successScore(), req.sessionId());
        return ResponseEntity.status(HttpStatus.CREATED).body(AppendThoughtResponse.from(result));
    }

    @GetMapping("/best-next")
    public BestNextResponse bestNext(
            @PathVariable int id,
            @RequestParam(name = "fromSlot", defaultValue = "0") int fromSlot,
            @RequestParam(name = "sessionId") int sessionId) {
        return BestNextResponse.from(engine.bestNext(id, fromSlot, sessionId));
    }

    @GetMapping("/path-to-root")
    public PathToRootResponse pathToRoot(
            @PathVariable int id,
            @RequestParam(name = "fromSlot") int fromSlot,
            @RequestParam(name = "maxDepth", defaultValue = "1024") int maxDepth) {
        var r = engine.pathToRoot(id, fromSlot, maxDepth);
        return new PathToRootResponse(r.path(), r.depth());
    }
}
