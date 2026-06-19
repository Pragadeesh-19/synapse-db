package com.synapsedb.persistence;

import java.nio.ByteBuffer;

/**
 * The 64-byte ring-file header: read, write, and fail-fast validation.
 *
 * <pre>
 * Offset  Field          Type   Notes
 *  [0]    magic          long   0x53594E4150534544L  ("SYNAPSED")
 *  [8]    version        int    1
 *  [12]   agentId        int    owning agent
 *  [16]   writeHead      long   monotonic ring counter (mirrors SynapseGraph.writeHead)
 *  [24]   activeSession  int    most recent session id written
 *  [28]   reserved       36B   zeroes (pre-allocated file is zero-filled)
 * </pre>
 *
 * <p>All gets/puts use absolute offsets — the buffer's position is never changed.
 */
public final class RingFileHeader {

    static final long MAGIC   = 0x53594E4150534544L;
    static final int  VERSION = 2;  // v2: record[+0] repurposed from slotIndex to CRC32C commit bit
    static final int  SIZE    = 64;

    private static final int OFF_MAGIC          =  0;
    private static final int OFF_VERSION        =  8;
    private static final int OFF_AGENT_ID       = 12;
    static final         int OFF_WRITE_HEAD     = 16;
    private static final int OFF_ACTIVE_SESSION = 24;

    /** Write a fresh header at the start of {@code buf} (absolute puts). */
    static void write(ByteBuffer buf, int agentId, long writeHead, int activeSession) {
        buf.putLong(OFF_MAGIC,          MAGIC);
        buf.putInt (OFF_VERSION,        VERSION);
        buf.putInt (OFF_AGENT_ID,       agentId);
        buf.putLong(OFF_WRITE_HEAD,     writeHead);
        buf.putInt (OFF_ACTIVE_SESSION, activeSession);
        // reserved bytes [28..63] stay zero (pre-allocated file is zero-filled)
    }

    /** Update only the write-head field (absolute put, called after every record write). */
    static void updateWriteHead(ByteBuffer buf, long writeHead) {
        buf.putLong(OFF_WRITE_HEAD, writeHead);
    }

    /** Update only the active-session field (absolute put). */
    static void updateActiveSession(ByteBuffer buf, int activeSession) {
        buf.putInt(OFF_ACTIVE_SESSION, activeSession);
    }

    /**
     * Read and validate the header. Throws {@link IllegalStateException} on any
     * mismatch — mirrors {@link com.synapsedb.core.MemoryConfig}'s fail-fast posture.
     *
     * @param buf           buffer over the full file (absolute gets); capacity = file size
     * @param expectedAgent agentId the file should belong to
     * @param expectedSize  expected total file size in bytes (header + all records)
     * @return an immutable {@link Snapshot} of the header fields
     */
    static Snapshot readAndValidate(ByteBuffer buf, int expectedAgent, long expectedSize) {
        long magic = buf.getLong(OFF_MAGIC);
        if (magic != MAGIC) {
            throw new IllegalStateException(String.format(
                    "Ring file magic mismatch: expected 0x%X, got 0x%X", MAGIC, magic));
        }
        int version = buf.getInt(OFF_VERSION);
        if (version != VERSION) {
            throw new IllegalStateException(
                    "Ring file version mismatch: expected v" + VERSION + ", got v" + version
                            + " — delete ./data/agent-*.bin and re-register (v1 records have no CRC)."
                            + " Note: runtime-registered API keys are in-memory only (T-KEY-PERSIST);"
                            + " agents registered at runtime must be re-registered and new keys issued.");
        }
        int agentId = buf.getInt(OFF_AGENT_ID);
        if (agentId != expectedAgent) {
            throw new IllegalStateException(
                    "Ring file agentId mismatch: expected " + expectedAgent + ", got " + agentId);
        }
        long fileSize = buf.capacity();
        if (fileSize != expectedSize) {
            throw new IllegalStateException(
                    "Ring file size mismatch: expected " + expectedSize + " B, got " + fileSize + " B");
        }
        return new Snapshot(agentId, buf.getLong(OFF_WRITE_HEAD), buf.getInt(OFF_ACTIVE_SESSION));
    }

    /** Immutable snapshot of the header fields, returned by {@link #readAndValidate}. */
    public record Snapshot(int agentId, long writeHead, int activeSession) {}

    private RingFileHeader() {}
}
