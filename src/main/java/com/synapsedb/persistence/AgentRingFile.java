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
 * Record layout v2 (32 bytes, offsets relative to record start):
 *   [+0]   crc32c        int    CRC32C over bytes [+4..+31] — WRITTEN LAST (commit bit)
 *   [+4]   parentSlot    int
 *   [+8]   stateHash     int
 *   [+12]  sessionId     int
 *   [+16]  successScore  float
 *   [+20]  salienceScore float
 *   [+24]  timestamp     long
 *
 * Commit-bit layering:
 *   timestamp == 0          → empty/never-written slot — skip (pre-check, avoids false CRC flag)
 *   timestamp != 0, bad CRC → torn write (crash between ts write and CRC write) — skip+count
 *   timestamp != 0, good CRC → valid record — loadSlot
 * </pre>
 *
 * <p>Hot-path write: {@link #writeRecord} uses absolute {@code putXXX()} calls —
 * no position tracking, no syscall, writes flow to the OS page cache only.
 * Never call {@code force()} on the hot path.
 *
 * <p>Bootstrap: {@link #bootstrapInto} streams raw primitives into the graph via
 * {@code graph.loadSlot()} (zero allocation), checks CRC per slot, then triggers one
 * {@code rebuildFcns()} pass. Returns a {@link BootstrapResult} carrying the header
 * snapshot and the count of records skipped due to CRC mismatch.
 *
 * <p>Close/unmap: {@link #close()} calls {@code sun.misc.Unsafe.invokeCleaner()} so
 * Windows releases the file lock immediately rather than waiting for GC. Falls back
 * to GC-unmap silently if the JVM blocks the reflective call (V2 cure: FFM Arena).
 */
public final class AgentRingFile implements Closeable {

    public static final int HEADER_SIZE = RingFileHeader.SIZE; // 64
    public static final int RECORD_SIZE = 32;

    // Record field offsets (relative to record start).
    // [+0] was slotIndex (v1, redundant — always derivable from offset).
    // v2: repurposed as CRC32C over bytes [+4..+31] — written LAST as the commit bit.
    private static final int REC_CRC       =  0; // CRC32C commit bit (v2)
    private static final int REC_PARENT    =  4;
    private static final int REC_HASH      =  8;
    private static final int REC_SESSION   = 12;
    private static final int REC_SUCCESS   = 16;
    private static final int REC_SALIENCE  = 20;
    private static final int REC_TIMESTAMP = 24;

    // Length of the CRC-covered region: bytes [+4..+31] = 28 bytes.
    private static final int CRC_DATA_OFFSET = REC_PARENT;          // 4
    private static final int CRC_DATA_LENGTH = RECORD_SIZE - REC_PARENT; // 28

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
     * <p>Fails fast if {@code shardSize} would cause the record-offset int arithmetic
     * in {@link #writeRecord} to overflow (see T-SHARD-INT-OVERFLOW).
     *
     * @param path      full path to the {@code .bin} file
     * @param agentId   the owning agent
     * @param shardSize must match {@link com.synapsedb.core.MemoryConfig#shardSize()}
     */
    public static AgentRingFile open(Path path, int agentId, int shardSize) throws IOException {
        long size = HEADER_SIZE + (long) shardSize * RECORD_SIZE;
        // Belt-and-suspenders: MemoryConfig already guards this, but protect direct callers too.
        if (size > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "Ring file size " + size + " B exceeds Integer.MAX_VALUE; "
                            + "reduce SHARD_SIZE to ≤ " + (1 << 25));
        }
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        MappedByteBuffer buf;
        boolean isNew;
        try (FileChannel ch = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long actualSize = ch.size();
            isNew = (actualSize == 0L);
            if (isNew) {
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
     * <p>Write order (D4 / v1.3 updated for v2 CRC commit bit):
     * <ol>
     *   <li>Write data fields [+4..+31] (parentSlot → timestamp inclusive)</li>
     *   <li>Compute CRC32C over bytes [+4..+31]</li>
     *   <li>Write CRC32C at [+0] LAST — new commit bit</li>
     *   <li>Update header.writeHead</li>
     * </ol>
     *
     * <p>A crash between step 1 and step 3 leaves {@code crc == 0} or garbage. Bootstrap
     * detects this as corrupt (ts != 0 but crc mismatch) and skips the slot. A crash
     * before timestamp is written leaves ts == 0, caught by the pre-check first.
     *
     * @param writeHead the monotonic counter value AFTER claiming this slot
     */
    public void writeRecord(int slot, int parentSlot, int stateHash, int sessionId,
                            float successScore, float salienceScore, long timestamp,
                            long writeHead) {
        int base = HEADER_SIZE + slot * RECORD_SIZE;
        // Step 1: write all data fields [+4..+31].
        buffer.putInt  (base + REC_PARENT,    parentSlot);
        buffer.putInt  (base + REC_HASH,      stateHash);
        buffer.putInt  (base + REC_SESSION,   sessionId);
        buffer.putFloat(base + REC_SUCCESS,   successScore);
        buffer.putFloat(base + REC_SALIENCE,  salienceScore);
        buffer.putLong (base + REC_TIMESTAMP, timestamp);
        // Step 2+3: compute and write CRC32C LAST (commit bit).
        int crc = CrcChecksum.compute(buffer, base + CRC_DATA_OFFSET, CRC_DATA_LENGTH);
        buffer.putInt  (base + REC_CRC, crc);
        RingFileHeader.updateWriteHead(buffer, writeHead);
    }

    // ── Bootstrap ────────────────────────────────────────────────────────────

    /**
     * Reload this agent's shard from the ring file into {@code graph}.
     *
     * <pre>
     * Phase 1 — raw load with CRC verification (zero allocation):
     *   for slot in [1, shardSize):
     *     ts = buffer[slot + REC_TIMESTAMP]
     *     if ts == 0: skip (empty/never-written)
     *     if CRC32C([+4..+31]) != stored CRC at [+0]: skip + corruptSkipped++
     *     else: graph.loadSlot(...)
     *
     * Phase 2 — FCNS rebuild (one O(n) ascending-slot pass):
     *   graph.rebuildFcns(agentId)
     *
     * Phase 3 — restore write head:
     *   graph.restoreWriteHead(agentId, header.writeHead)
     * </pre>
     *
     * @return {@link BootstrapResult} carrying the header snapshot and the count of
     *         records skipped due to CRC mismatch (0 = clean, &gt;0 = partial writes detected)
     */
    public BootstrapResult bootstrapInto(SynapseGraph graph) {
        RingFileHeader.Snapshot snap = RingFileHeader.readAndValidate(buffer, agentId, fileSize);

        graph.registerAgent(agentId);

        int corruptSkipped = 0;

        for (int slot = 1; slot < shardSize; slot++) {
            int  base = HEADER_SIZE + slot * RECORD_SIZE;
            long ts   = buffer.getLong(base + REC_TIMESTAMP);
            if (ts == 0L) continue; // empty slot or torn write — pre-check

            // CRC verification: detects torn writes where timestamp was written but CRC was not.
            int storedCrc   = buffer.getInt(base + REC_CRC);
            int computedCrc = CrcChecksum.compute(buffer, base + CRC_DATA_OFFSET, CRC_DATA_LENGTH);
            if (storedCrc != computedCrc) {
                corruptSkipped++;
                continue;
            }

            int   parent  = buffer.getInt  (base + REC_PARENT);
            int   hash    = buffer.getInt  (base + REC_HASH);
            int   sess    = buffer.getInt  (base + REC_SESSION);
            float success = buffer.getFloat(base + REC_SUCCESS);
            float sal     = buffer.getFloat(base + REC_SALIENCE);
            graph.loadSlot(agentId, slot, parent, hash, sess, success, sal, ts);
        }

        graph.rebuildFcns(agentId);
        graph.restoreWriteHead(agentId, snap.writeHead());
        return new BootstrapResult(snap, corruptSkipped);
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

    // ── Result type ──────────────────────────────────────────────────────────

    /**
     * Result of {@link #bootstrapInto}: the header snapshot plus the count of records
     * skipped due to CRC mismatch.
     */
    public record BootstrapResult(RingFileHeader.Snapshot snap, int corruptSkipped) {}
}
