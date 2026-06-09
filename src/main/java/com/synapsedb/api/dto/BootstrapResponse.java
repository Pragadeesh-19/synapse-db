package com.synapsedb.api.dto;

import com.synapsedb.persistence.RingFileHeader;

/**
 * 200 response to POST bootstrap. Reports the header state restored from the ring file.
 */
public record BootstrapResponse(boolean reloaded, long writeHead, int activeSession) {

    public static BootstrapResponse from(RingFileHeader.Snapshot snap) {
        return new BootstrapResponse(true, snap.writeHead(), snap.activeSession());
    }
}
