package com.synapsedb.persistence;

import com.synapsedb.core.SynapseGraph;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Memory-mapped binary ring file for one agent's shard.
 *
 * <pre>
 * File layout  (BIG_ENDIAN throughout):
 * ┌─────────────────────────────────────┐
 * │  [0..63]    64-byte header           │  see RingFileHeader
 * ├─────────────────────────────────────┤
 * │  [64..]     SHARD_SIZE × 32B records │  slot k at HEADER_SIZE + k*RECORD_SIZE
 * └─────────────────────────────────────┘
 *
 * Record layout (32 bytes, offsets relative to record start):
 *   [+0]   slotIndex     int    (redundant — derivable from offset; kept for hex-dump)
 *   [+4]   parentSlot    int
 *   [+8]   stateHash     int
 *   [+12]  sessionId     int
 *   [+16]  successScore  float
 *   [+20]  salienceScore float
 *   [+24]  timestamp     long   ← WRITTEN LAST (commit bit — see D4)
 *                                  A torn write leaves timestamp==0; bootstrap skips it.
 * </pre>
 *
 * <p>Hot-path write: {@link #writeRecord} uses absolute {@code putXXX()} calls —
 * no position tracking, no syscall, writes flow to the OS page cache only.
 * Never call {@code force()} on the hot path.
 *
 * <p>Bootstrap: {@link #bootstrapInto} streams raw primitives into the graph via
 * {@code graph.loadSlot()} (zero allocation), then triggers one {@code rebuildFcns()}
 * pass. FCNS logic stays in {@code SynapseGraph} (one copy of the prepend invariant).
 *
 * <p>Close/unmap: {@link #close()} calls {@code sun.misc.Unsafe.invokeCleaner()} so
 * Windows releases the file lock immediately rather than waiting for GC. Falls back
 * to GC-unmap silently if the JVM blocks the reflective call (V2 cure: FFM Arena).
 */
public final class AgentRingFile implements Closeable {

    public static final int HEADER_SIZE = RingFileHeader.SIZE; // 64
    public static final int RECORD_SIZE = 32;

    // Record field offsets (relative to record start).
    private static final int REC_SLOT      =  0;
    private static final int REC_PARENT    =  4;
    private static final int REC_HASH      =  8;
    private static final int REC_SESSION   = 12;
    private static final int REC_SUCCESS   = 16;
    private static final int REC_SALIENCE  = 20;
    private static final int REC_TIMESTAMP = 24; // commit bit — always written last

    private final int  agentId;
    private final int  shardSize;
    private final long fileSize;

    private MappedByteBuffer buffer; // null after close()

    private AgentRingFile(int agentId, int shardSize, long fileSize, MappedByteBuffer buffer) {
        this.agentId   = agentId;
        this.shardSize = shardSize;
        this.fileSize  = fileSize;
        this.buffer    = buffer;
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    /**
     * Open (or create) the ring file for {@code agentId}.
     *
     * <ul>
     *   <li>If the file does not exist: pre-allocate it to the exact size and write a
     *       fresh header.</li>
     *   <li>If the file exists: validate the header (magic, version, agentId, size);
     *       throws {@link IllegalStateException} on any mismatch.</li>
     * </ul>
     *
     * @param path      full path to the {@code .bin} file
     * @param agentId   the owning agent
     * @param shardSize must match {@link com.synapsedb.core.MemoryConfig#shardSize()}
     */
    public static AgentRingFile open(Path path, int agentId, int shardSize) throws IOException {
        long size = HEADER_SIZE + (long) shardSize * RECORD_SIZE;
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        MappedByteBuffer buf;
        boolean isNew;
        try (FileChannel ch = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long actualSize = ch.size();
            // A brand-new (or externally-created zero-length) file gets pre-allocated.
            // An EXISTING file whose length != expected is a hard fail-fast: a leftover
            // from a different SHARD_SIZE would put every record at the wrong offset.
            isNew = (actualSize == 0L);
            if (isNew) {
                // Pre-allocate to exactly `size` by writing one byte at the last position.
                ch.write(java.nio.ByteBuffer.allocate(1), size - 1);
            } else if (actualSize != size) {
                throw new IllegalStateException(
                        "Ring file size mismatch for agent " + agentId + ": expected " + size
                                + " B, got " + actualSize + " B (stale file from a different SHARD_SIZE?)");
            }
            buf = ch.map(FileChannel.MapMode.READ_WRITE, 0, size);
        }
        buf.order(ByteOrder.BIG_ENDIAN); // explicit — never rely on the default (D2 / v1.3)

        AgentRingFile rf = new AgentRingFile(agentId, shardSize, size, buf);
        if (isNew) {
            RingFileHeader.write(buf, agentId, 1L /*writeHead starts at 1*/, 0);
        } else {
            RingFileHeader.readAndValidate(buf, agentId, size);
        }
        return rf;
    }

    // ── Hot-path write ───────────────────────────────────────────────────────

    /**
     * Persist one record to the ring file (hot path).
     *
     * <p>Field write order is a contract (D4 / v1.3): timestamp is written LAST so a
     * mid-write crash leaves {@code timestamp == 0} and bootstrap skips the slot.
     * Do NOT reorder the field writes.
     *
     * <p>Immediately updates {@code header.writeHead} after the record so that a
     * subsequent bootstrap restores the exact head position and the next {@code append()}
     * continues at the correct slot.
     *
     * @param writeHead the monotonic counter value AFTER claiming this slot
     *                  (i.e. {@code SynapseGraph.writeHead(agentId)} post-append)
     */
    public void writeRecord(int slot, int parentSlot, int stateHash, int sessionId,
                            float successScore, float salienceScore, long timestamp,
                            long writeHead) {
        int base = HEADER_SIZE + slot * RECORD_SIZE;
        buffer.putInt  (base + REC_SLOT,      slot);
        buffer.putInt  (base + REC_PARENT,    parentSlot);
        buffer.putInt  (base + REC_HASH,      stateHash);
        buffer.putInt  (base + REC_SESSION,   sessionId);
        buffer.putFloat(base + REC_SUCCESS,   successScore);
        buffer.putFloat(base + REC_SALIENCE,  salienceScore);
        buffer.putLong (base + REC_TIMESTAMP, timestamp);  // commit bit — always last
        RingFileHeader.updateWriteHead(buffer, writeHead);
    }

    // ── Bootstrap ────────────────────────────────────────────────────────────

    /**
     * Reload this agent's shard from the ring file into {@code graph}.
     *
     * <pre>
     * Phase 1 — raw load (zero allocation, D5 / v1.3):
     *   for slot in [1, shardSize):
     *     ts = buffer[slot + REC_TIMESTAMP]
     *     if ts != 0:
     *       graph.loadSlot(agentId, slot, parent, hash, sess, succ, sal, ts)
     *
     * Skipping ts==0 handles both never-written slots AND torn writes (D4).
     *
     * Phase 2 — FCNS rebuild (one O(n) ascending-slot pass, D3 / v1.3):
     *   graph.rebuildFcns(agentId)
     *   FCNS logic lives in SynapseGraph — one copy of the prepend invariant.
     *
     * Phase 3 — restore write head:
     *   graph.restoreWriteHead(agentId, header.writeHead)
     * </pre>
     *
     * @return the header {@link RingFileHeader.Snapshot} (writeHead + activeSession)
     */
    public RingFileHeader.Snapshot bootstrapInto(SynapseGraph graph) {
        RingFileHeader.Snapshot snap = RingFileHeader.readAndValidate(buffer, agentId, fileSize);

        // registerAgent is idempotent — ensures a fresh shard exists before raw-loading.
        graph.registerAgent(agentId);

        // Phase 1: stream primitives → engine arrays (zero object allocation).
        for (int slot = 1; slot < shardSize; slot++) {
            int  base = HEADER_SIZE + slot * RECORD_SIZE;
            long ts   = buffer.getLong(base + REC_TIMESTAMP);
            if (ts == 0L) continue; // empty slot or torn write — skip
            int   parent  = buffer.getInt  (base + REC_PARENT);
            int   hash    = buffer.getInt  (base + REC_HASH);
            int   sess    = buffer.getInt  (base + REC_SESSION);
            float success = buffer.getFloat(base + REC_SUCCESS);
            float sal     = buffer.getFloat(base + REC_SALIENCE);
            graph.loadSlot(agentId, slot, parent, hash, sess, success, sal, ts);
        }

        // Phase 2: rebuild FCNS (one O(n) pass, ascending slot order).
        graph.rebuildFcns(agentId);

        // Phase 3: restore write head so next append continues at the right slot.
        graph.restoreWriteHead(agentId, snap.writeHead());
        return snap;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Unmap the file and release the OS file lock.
     *
     * <p>Uses {@code sun.misc.Unsafe.invokeCleaner()} for deterministic unmap on
     * Windows (D2 / v1.3). Without this, the file stays locked until GC, and any
     * test that creates then deletes the temp file will fail with "file in use."
     * Falls back to GC-unmap silently if the JVM blocks the reflective call.
     */
    @Override
    public void close() {
        MappedByteBuffer b = buffer;
        if (b == null) return;
        buffer = null;
        tryUnmap(b);
    }

    @SuppressWarnings("sunapi")
    private static void tryUnmap(MappedByteBuffer b) {
        try {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
            unsafe.invokeCleaner(b);
        } catch (Exception ignored) {
            // Falls back to GC-based unmap (V2 cure: FFM MemorySegment.map(Arena))
        }
    }
}
