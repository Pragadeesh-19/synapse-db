package com.synapsedb.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that a v1 ring file (VERSION=1) is rejected with a clear, actionable error
 * message when opened with the v2 reader.
 */
class FormatVersionRejectTest {

    private static final int SHARD = 8;
    private static final int AGENT = 0;

    @Test
    @DisplayName("v1 ring file (VERSION=1) throws with message naming v2 and delete advice")
    void v1FileRejectedWithClearMessage(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("agent-0.bin");

        // Create a valid v2 file, then overwrite VERSION field (offset 8) with 1.
        try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) { /* create v2 */ }
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
            raf.seek(8); // version field in header
            raf.writeInt(1);
        }

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> AgentRingFile.open(p, AGENT, SHARD).close());

        String msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("v2"), "message must reference the expected version v2: " + ex.getMessage());
        assertTrue(msg.contains("delete") || msg.contains("incompatible"),
                "message must advise deletion or mention incompatibility: " + ex.getMessage());
    }

    @Test
    @DisplayName("v2 file (VERSION=2) opens successfully — no regression")
    void v2FileOpensSuccessfully(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("agent-0.bin");
        assertDoesNotThrow(() -> {
            try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) { /* ok */ }
        });
    }
}
