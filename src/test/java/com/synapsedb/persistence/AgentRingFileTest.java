package com.synapsedb.persistence;

import com.synapsedb.core.MemoryConfig;
import com.synapsedb.core.SynapseGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.*;

class AgentRingFileTest {

    private static final int SHARD = 8; // tiny shard: ring wraps after 7 appends
    private static final int AGENT = 0;

    private static MemoryConfig cfg() {
        return new MemoryConfig(2, SHARD, 0.1f, 3_600_000L, 0.1f, 0.5f, "./data", "./config", 0.7f);
    }

    private static long expectedFileSize() {
        return AgentRingFile.HEADER_SIZE + (long) SHARD * AgentRingFile.RECORD_SIZE;
    }

    // ── File creation and validation ─────────────────────────────────────────

    @Nested
    class FileLifecycle {
        @Test
        @DisplayName("open new file creates it with the correct size and valid header")
        void openNewFile(@TempDir Path dir) throws Exception {
            Path p = dir.resolve("agent-0.bin");
            try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) {
                assertTrue(Files.exists(p));
                assertEquals(expectedFileSize(), Files.size(p));
            }
        }

        @Test
        @DisplayName("re-opening an existing file validates the header successfully")
        void reopenExistingFile(@TempDir Path dir) throws Exception {
            Path p = dir.resolve("agent-0.bin");
            try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) {
                // just create it
            }
            // second open must not throw
            assertDoesNotThrow(() -> {
                try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) { /* ok */ }
            });
        }

        @Test
        @DisplayName("opening a file with wrong magic throws IllegalStateException")
        void openCorruptedMagic(@TempDir Path dir) throws Exception {
            Path p = dir.resolve("agent-0.bin");
            try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) { /* create */ }
            // corrupt the magic
            try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
                raf.seek(0);
                raf.writeLong(0xDEADL);
            }
            assertThrows(IllegalStateException.class,
                    () -> AgentRingFile.open(p, AGENT, SHARD).close());
        }

        @Test
        @DisplayName("opening an existing file with the wrong size throws (stale SHARD_SIZE guard)")
        void openWrongSizeFile(@TempDir Path dir) throws Exception {
            Path p = dir.resolve("agent-0.bin");
            // Create a non-empty file whose length != expected (simulates a leftover from
            // a different SHARD_SIZE). 100 bytes is neither 0 nor the expected file size.
            try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
                raf.setLength(100L);
            }
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> AgentRingFile.open(p, AGENT, SHARD).close());
            assertTrue(ex.getMessage().toLowerCase().contains("size"), ex.getMessage());
        }

        @Test
        @DisplayName("close() unmaps the buffer so the temp file can be deleted on Windows")
        void closeAllowsDeletion(@TempDir Path dir) throws Exception {
            Path p = dir.resolve("agent-0.bin");
            AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD);
            rf.close();
            // If invokeCleaner() failed (falls back to GC), this may flake on Windows.
            // The test documents the contract; CI confirms it works on the target platform.
            assertTrue(Files.deleteIfExists(p),
                    "ring file must be deletable immediately after close()");
        }
    }

    // ── Record write ─────────────────────────────────────────────────────────

    @Nested
    class RecordWrite {
        @Test
        @DisplayName("writeRecord stores all fields at the correct byte offsets")
        void fieldsAtCorrectOffsets(@TempDir Path dir) throws Exception {
            Path p = dir.resolve("agent-0.bin");
            long ts = 1_700_000_000_000L;
            try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) {
                rf.writeRecord(1, 0, 42, 7, 0.8f, 0.55f, ts, 2L);
            }
            // Read the raw bytes to verify layout.
            byte[] raw = Files.readAllBytes(p);
            ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
            int base = AgentRingFile.HEADER_SIZE + AgentRingFile.RECORD_SIZE; // slot 1

            // v2 layout: [+0] is CRC32C over bytes [+4..+31]; [+0..+3] is no longer slotIndex.
            assertEquals(0,     b.getInt  (base + 4),  "parentSlot");
            assertEquals(42,    b.getInt  (base + 8),  "stateHash");
            assertEquals(7,     b.getInt  (base + 12), "sessionId");
            assertEquals(0.8f,  b.getFloat(base + 16), 1e-6f, "successScore");
            assertEquals(0.55f, b.getFloat(base + 20), 1e-6f, "salienceScore");
            assertEquals(ts,    b.getLong (base + 24), "timestamp");
            // Verify CRC32C at [+0] matches a recomputed checksum over bytes [+4..+31].
            CRC32C crc = new CRC32C();
            crc.update(raw, base + 4, 28);
            assertEquals((int) crc.getValue(), b.getInt(base + 0), "crc32c");
        }

        @Test
        @DisplayName("timestamp zeroed after write simulates torn write: bootstrap must skip that slot")
        void timestampIsLastCommitBit(@TempDir Path dir) throws Exception {
            // We can't intercept a mid-write crash, but we CAN simulate one by writing
            // a complete record then zeroing only the timestamp bytes — confirming the
            // commit-bit contract (D4 / v1.3).
            Path p = dir.resolve("agent-0.bin");
            int slot = 1;
            long ts = 1_700_000_000_000L;

            try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) {
                rf.writeRecord(slot, SynapseGraph.ROOT_SLOT, 10, 1, 0.5f, 0.55f, ts, 2L);
            }
            // Zero out only the timestamp (bytes [+24..+31] of the record).
            try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
                raf.seek(AgentRingFile.HEADER_SIZE + (long) slot * AgentRingFile.RECORD_SIZE + 24);
                raf.writeLong(0L);
            }

            SynapseGraph restored = new SynapseGraph(cfg());
            try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) {
                rf.bootstrapInto(restored);
            }
            assertFalse(restored.isWritten(AGENT, slot),
                    "slot with zeroed timestamp must be treated as empty by bootstrap");
        }

        @Test
        @DisplayName("writeRecord updates header.writeHead after each record")
        void writeRecordUpdatesWriteHead(@TempDir Path dir) throws Exception {
            Path p = dir.resolve("agent-0.bin");
            try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) {
                rf.writeRecord(1, 0, 0, 1, 0.5f, 0.5f, 1_000L, 2L);
                rf.writeRecord(2, 1, 0, 1, 0.5f, 0.5f, 2_000L, 3L);
            }
            // Reopen and check the header's writeHead is the last written value.
            byte[] raw = Files.readAllBytes(p);
            ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
            long writeHead = b.getLong(RingFileHeader.OFF_WRITE_HEAD);
            assertEquals(3L, writeHead, "header.writeHead must reflect the last written value");
        }
    }

    // ── Bootstrap skips empty / torn records ─────────────────────────────────

    @Nested
    class Bootstrap {
        @Test
        @DisplayName("bootstrap skips slots where timestamp == 0 (never-written)")
        void skipsZeroTimestampSlots(@TempDir Path dir) throws Exception {
            Path p = dir.resolve("agent-0.bin");
            // Write only slot 1; slots 2-7 remain timestamp=0 in the file.
            try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) {
                rf.writeRecord(1, 0, 55, 3, 0.7f, 0.57f, 9_999L, 2L);
            }
            SynapseGraph g = new SynapseGraph(cfg());
            try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) {
                rf.bootstrapInto(g);
            }
            assertTrue (g.isWritten(AGENT, 1), "slot 1 must be loaded");
            assertFalse(g.isWritten(AGENT, 2), "slot 2 was never written");
            assertFalse(g.isWritten(AGENT, 3), "slot 3 was never written");
        }

        @Test
        @DisplayName("bootstrap on empty file (no records written) produces only the root")
        void emptyFileBootstrap(@TempDir Path dir) throws Exception {
            Path p = dir.resolve("agent-0.bin");
            try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) { /* fresh, no writes */ }
            SynapseGraph g = new SynapseGraph(cfg());
            try (AgentRingFile rf = AgentRingFile.open(p, AGENT, SHARD)) {
                rf.bootstrapInto(g);
            }
            assertTrue(g.isWritten(AGENT, SynapseGraph.ROOT_SLOT), "root slot must be written");
            assertEquals(0, g.countChildren(AGENT, SynapseGraph.ROOT_SLOT),
                    "root must have no children on empty bootstrap");
        }
    }
}
