package com.synapsedb.persistence;

import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/**
 * CRC32C helper for ring file record integrity.
 *
 * <p>CRC32C is hardware-accelerated on x86/ARM via the JDK's intrinsified {@link java.util.zip.CRC32C}
 * (Java 9+). Cost: ~5-20 ns for a 28-byte record.
 *
 * <p>Uses {@link ByteBuffer#slice(int, int)} (absolute-position, Java 13+) so the source
 * buffer's position and limit are never disturbed — safe on the hot write path.
 */
final class CrcChecksum {

    private CrcChecksum() {}

    /**
     * Compute CRC32C over {@code length} bytes at absolute {@code offset} in {@code buf}.
     * Does not modify {@code buf}'s position or limit.
     */
    static int compute(ByteBuffer buf, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(buf.slice(offset, length));
        return (int) crc.getValue();
    }
}
