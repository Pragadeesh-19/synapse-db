package com.synapsedb.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class RingFileHeaderTest {

    private static ByteBuffer buf(long fileSize) {
        ByteBuffer b = ByteBuffer.allocate((int) fileSize);
        b.order(ByteOrder.BIG_ENDIAN);
        return b;
    }

    // ── Roundtrip ────────────────────────────────────────────────────────────

    @Nested
    class Roundtrip {
        @Test
        @DisplayName("write then readAndValidate returns all fields unchanged")
        void writeAndRead() {
            long size = RingFileHeader.SIZE + 10L * AgentRingFile.RECORD_SIZE;
            ByteBuffer b = buf(size);
            RingFileHeader.write(b, 7, 42_000L, 99);

            RingFileHeader.Snapshot snap = RingFileHeader.readAndValidate(b, 7, size);
            assertEquals(7,       snap.agentId());
            assertEquals(42_000L, snap.writeHead());
            assertEquals(99,      snap.activeSession());
        }

        @Test
        @DisplayName("updateWriteHead changes only the writeHead field")
        void updateWriteHead_isolatedField() {
            long size = RingFileHeader.SIZE + 4L * AgentRingFile.RECORD_SIZE;
            ByteBuffer b = buf(size);
            RingFileHeader.write(b, 3, 1L, 5);

            RingFileHeader.updateWriteHead(b, 999L);

            RingFileHeader.Snapshot snap = RingFileHeader.readAndValidate(b, 3, size);
            assertEquals(999L, snap.writeHead(),       "writeHead must be updated");
            assertEquals(5,    snap.activeSession(),   "activeSession must be unchanged");
            assertEquals(3,    snap.agentId(),         "agentId must be unchanged");
        }

        @Test
        @DisplayName("updateActiveSession changes only the activeSession field")
        void updateActiveSession_isolatedField() {
            long size = RingFileHeader.SIZE + 4L * AgentRingFile.RECORD_SIZE;
            ByteBuffer b = buf(size);
            RingFileHeader.write(b, 2, 50L, 1);

            RingFileHeader.updateActiveSession(b, 77);

            RingFileHeader.Snapshot snap = RingFileHeader.readAndValidate(b, 2, size);
            assertEquals(50L, snap.writeHead(),       "writeHead must be unchanged");
            assertEquals(77,  snap.activeSession(),   "activeSession must be updated");
        }
    }

    // ── Validation failures ───────────────────────────────────────────────────

    @Nested
    class Validation {
        @Test
        @DisplayName("wrong magic → IllegalStateException naming 'magic'")
        void wrongMagic() {
            long size = RingFileHeader.SIZE + 4L * AgentRingFile.RECORD_SIZE;
            ByteBuffer b = buf(size);
            RingFileHeader.write(b, 0, 1L, 0);
            b.putLong(0, 0xDEADBEEFCAFEL); // corrupt magic

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> RingFileHeader.readAndValidate(b, 0, size));
            assertTrue(ex.getMessage().toLowerCase().contains("magic"), ex.getMessage());
        }

        @Test
        @DisplayName("wrong version → IllegalStateException naming 'version'")
        void wrongVersion() {
            long size = RingFileHeader.SIZE + 4L * AgentRingFile.RECORD_SIZE;
            ByteBuffer b = buf(size);
            RingFileHeader.write(b, 0, 1L, 0);
            b.putInt(8, 99); // corrupt version

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> RingFileHeader.readAndValidate(b, 0, size));
            assertTrue(ex.getMessage().toLowerCase().contains("version"), ex.getMessage());
        }

        @Test
        @DisplayName("wrong agentId → IllegalStateException naming 'agentId'")
        void wrongAgentId() {
            long size = RingFileHeader.SIZE + 4L * AgentRingFile.RECORD_SIZE;
            ByteBuffer b = buf(size);
            RingFileHeader.write(b, 5, 1L, 0); // wrote agentId=5

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> RingFileHeader.readAndValidate(b, 99 /*expected=99*/, size));
            assertTrue(ex.getMessage().toLowerCase().contains("agentid"), ex.getMessage());
        }

        @Test
        @DisplayName("wrong file size → IllegalStateException naming 'size'")
        void wrongFileSize() {
            long size = RingFileHeader.SIZE + 4L * AgentRingFile.RECORD_SIZE;
            ByteBuffer b = buf(size);
            RingFileHeader.write(b, 1, 1L, 0);

            long wrongSize = size + AgentRingFile.RECORD_SIZE; // claim larger
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> RingFileHeader.readAndValidate(b, 1, wrongSize));
            assertTrue(ex.getMessage().toLowerCase().contains("size"), ex.getMessage());
        }
    }
}
