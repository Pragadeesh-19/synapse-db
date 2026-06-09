package com.synapsedb.api.dto;

import com.synapsedb.engine.AppendResult;

/**
 * 201 response to an append. {@code thoughtId == slotIndex} — the slot index IS the
 * thought id (there is no separate id array in the core).
 */
public record AppendThoughtResponse(int thoughtId, int slotIndex, float salienceScore, boolean persisted) {

    public static AppendThoughtResponse from(AppendResult r) {
        return new AppendThoughtResponse(r.slot(), r.slot(), r.salience(), r.persisted());
    }
}
