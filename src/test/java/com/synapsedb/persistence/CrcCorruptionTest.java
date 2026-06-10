package com.synapsedb.persistence;

import com.synapsedb.core.MemoryConfig;
import com.synapsedb.core.SynapseGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that CRC32C mismatch detection in {@link AgentRingFile#bootstrapInto} skips
 * corrupt records and counts them, while leaving valid neighboring slots intact.
 */
class CrcCorruptionTest {

    private static final int SHARD = 8;
    private static final int AGENT = 0;

    private static MemoryConfig cfg(@TempDir Path dir) {
        return new MemoryConfig(2, SHARD, 0.1f, 3_600_000L, 0.1f, 0.5f,
                dir.toString(), dir.toString(), 0.7f);
    }

    @Test
    @DisplayName("corrupt data byte: slot skipped, corruptSkipped==1, neighbors intact")
    void corruptDataByteSkipped(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("agent-0.bin");

        try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) {
            rf.writeRecord(1, 0, 11, 1, 0.8f, 0.4f, 1_000L, 2L);  // slot 1 — will be corrupted
            rf.writeRecord(2, 0, 22, 1, 0.6f, 0.3f, 2_000L, 3L);  // slot 2 — must survive
        }

        // Flip a byte in the data region [+4..+31] of slot 1.
        int corruptOffset = AgentRingFile.HEADER_SIZE + 1 * AgentRingFile.RECORD_SIZE + 8; // stateHash byte
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
            raf.seek(corruptOffset);
            raf.write(raf.read() ^ 0xFF); // flip all bits of one byte
        }

        SynapseGraph g = new SynapseGraph(cfg(dir));
        AgentRingFile.BootstrapResult result;
        try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) {
            result = rf.bootstrapInto(g);
        }

        assertEquals(1, result.corruptSkipped(), "exactly one corrupt record");
        assertFalse(g.isWritten(AGENT, 1), "corrupt slot 1 must not be loaded");
        assertTrue (g.isWritten(AGENT, 2), "clean slot 2 must be loaded");
    }

    @Test
    @DisplayName("zeroed CRC field (crash after timestamp, before CRC write): skipped as corrupt")
    void zeroedCrcAfterTimestamp(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("agent-0.bin");

        try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) {
            rf.writeRecord(1, 0, 55, 3, 0.7f, 0.5f, 9_000L, 2L);
        }

        // Simulate crash between timestamp write and CRC write: zero out the CRC at [+0].
        int crcOffset = AgentRingFile.HEADER_SIZE + 1 * AgentRingFile.RECORD_SIZE; // slot 1, [+0]
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
            raf.seek(crcOffset);
            raf.writeInt(0);
        }

        SynapseGraph g = new SynapseGraph(cfg(dir));
        AgentRingFile.BootstrapResult result;
        try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) {
            result = rf.bootstrapInto(g);
        }

        assertEquals(1, result.corruptSkipped(), "torn CRC must be counted as corrupt");
        assertFalse(g.isWritten(AGENT, 1), "slot with zeroed CRC must not be loaded");
    }

    @Test
    @DisplayName("clean file: corruptSkipped == 0 for all valid slots")
    void cleanFileZeroCorrupt(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("agent-0.bin");

        try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) {
            rf.writeRecord(1, 0, 1, 1, 0.5f, 0.5f, 1_000L, 2L);
            rf.writeRecord(2, 1, 2, 1, 0.4f, 0.4f, 2_000L, 3L);
            rf.writeRecord(3, 2, 3, 1, 0.3f, 0.3f, 3_000L, 4L);
        }

        SynapseGraph g = new SynapseGraph(cfg(dir));
        AgentRingFile.BootstrapResult result;
        try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) {
            result = rf.bootstrapInto(g);
        }

        assertEquals(0, result.corruptSkipped(), "no corrupt records in a clean file");
        assertTrue(g.isWritten(AGENT, 1));
        assertTrue(g.isWritten(AGENT, 2));
        assertTrue(g.isWritten(AGENT, 3));
    }
}
