package com.synapsedb.api.dto;

import com.synapsedb.core.BestNextResult;

/**
 * 200 response to GET best-next. When the node has no scorable children the engine returns
 * {@link BestNextResult#NONE}; this maps to {@code found=false, slot=null, score=null} so
 * the client gets a clear "no next thought" rather than a magic -1.
 */
public record BestNextResponse(boolean found, Integer slot, Float score) {

    public static BestNextResponse from(BestNextResult r) {
        if (r == null || r.bestSlot() < 0) {
            return new BestNextResponse(false, null, null);
        }
        return new BestNextResponse(true, r.bestSlot(), r.bestScore());
    }
}
