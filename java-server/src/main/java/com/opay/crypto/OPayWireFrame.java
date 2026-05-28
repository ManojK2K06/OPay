package com.opay.crypto;

import java.nio.ByteBuffer;

/**
 * Mirrors the Swift OPayWireFrame serialisation exactly.
 *
 * Wire layout (24 bytes pre-encryption):
 *  Offset  Size  Field
 *  ──────  ────  ────────────────────────────────────────────
 *   0       5    Sender account  (BCD packed, 10 decimal digits)
 *   5       5    Receiver account (BCD packed)
 *  10       4    Timestamp (UInt32 big-endian, Unix seconds)
 *  14       6    TxnID (lower 48 bits, big-endian)
 *  20       4    Amount in paise (UInt32 big-endian)
 *  ──────  ────  ────────────────────────────────────────────
 *  Total: 24 bytes
 */
public record OPayWireFrame(
        long senderAccount,
        long receiverAccount,
        long timestamp,
        long txnId,
        long amountPaise
) {
    public static final int WIRE_SIZE = 24;

    /** Deserialise 24-byte plaintext into a wire frame. */
    public static OPayWireFrame deserialise(byte[] plaintext) {
        if (plaintext.length != WIRE_SIZE) {
            throw new IllegalArgumentException(
                    "Expected " + WIRE_SIZE + " bytes, got " + plaintext.length);
        }
        long sender   = unpackBCD(plaintext, 0, 5);
        long receiver = unpackBCD(plaintext, 5, 5);

        ByteBuffer buf = ByteBuffer.wrap(plaintext);
        buf.position(10);
        long timestamp = Integer.toUnsignedLong(buf.getInt());

        // 6-byte TxnID
        long txnId = 0;
        for (int i = 14; i < 20; i++) {
            txnId = (txnId << 8) | (plaintext[i] & 0xFFL);
        }

        buf.position(20);
        long amount = Integer.toUnsignedLong(buf.getInt());

        return new OPayWireFrame(sender, receiver, timestamp, txnId, amount);
    }

    // ── BCD Helpers ──────────────────────────────────────────

    private static long unpackBCD(byte[] data, int offset, int length) {
        long result = 0;
        for (int i = 0; i < length; i++) {
            int b = data[offset + i] & 0xFF;
            result = result * 100 + ((b >> 4) & 0x0F) * 10 + (b & 0x0F);
        }
        return result;
    }

    /** Serialise for test round-trips. */
    public byte[] serialise() {
        byte[] out = new byte[WIRE_SIZE];
        packBCD(out, 0, senderAccount, 5);
        packBCD(out, 5, receiverAccount, 5);

        ByteBuffer buf = ByteBuffer.wrap(out);
        buf.position(10);
        buf.putInt((int) (timestamp & 0xFFFFFFFFL));

        // 6-byte TxnID
        for (int i = 5; i >= 0; i--) {
            out[14 + i] = (byte) (txnId >> ((5 - i) * 8));
        }
        buf.position(20);
        buf.putInt((int) (amountPaise & 0xFFFFFFFFL));
        return out;
    }

    private static void packBCD(byte[] out, int offset, long number, int bytes) {
        long n = number;
        for (int i = bytes - 1; i >= 0; i--) {
            int lo = (int) (n % 10); n /= 10;
            int hi = (int) (n % 10); n /= 10;
            out[offset + i] = (byte) ((hi << 4) | lo);
        }
    }
}
