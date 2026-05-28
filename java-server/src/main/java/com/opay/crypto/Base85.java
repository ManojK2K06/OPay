package com.opay.crypto;

import java.io.ByteArrayOutputStream;

/**
 * Z85 / Base85 codec — exact Java mirror of the Swift Base85 implementation.
 * Alphabet: 0-9 a-z A-Z .-:+=^!/*?&<>()[]{}@%$#  (85 characters)
 * 4 binary bytes → 5 printable ASCII chars (all in GSM 7-bit basic set)
 */
public final class Base85 {

    private Base85() {}

    private static final char[] ENCODE_TABLE =
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-:+=^!/*?&<>()[]{}@%$#"
            .toCharArray();

    private static final byte[] DECODE_TABLE = new byte[256];
    static {
        java.util.Arrays.fill(DECODE_TABLE, (byte) -1);
        for (int i = 0; i < ENCODE_TABLE.length; i++) {
            DECODE_TABLE[ENCODE_TABLE[i]] = (byte) i;
        }
    }

    // ── Encode ───────────────────────────────────────────────

    public static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length + 3) / 4 * 5 + 8);
        int i = 0;
        while (i < data.length) {
            int remaining = Math.min(4, data.length - i);
            long block = 0;
            for (int j = 0; j < remaining; j++) {
                block = (block << 8) | (data[i + j] & 0xFFL);
            }
            if (remaining < 4) {
                block <<= (long)(4 - remaining) * 8;
            }
            char[] chars = new char[5];
            for (int k = 4; k >= 0; k--) {
                chars[k] = ENCODE_TABLE[(int)(block % 85)];
                block /= 85;
            }
            int charsToAppend = (remaining < 4) ? remaining + 1 : 5;
            sb.append(chars, 0, charsToAppend);
            i += 4;
        }
        return sb.toString();
    }

    // ── Decode ───────────────────────────────────────────────

    public static byte[] decode(String encoded) {
        byte[] input = encoded.getBytes();
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length / 5 * 4);
        int i = 0;
        while (i < input.length) {
            int remaining = Math.min(5, input.length - i);
            long block = 0;
            for (int j = 0; j < remaining; j++) {
                byte v = DECODE_TABLE[input[i + j] & 0xFF];
                if (v < 0) throw new IllegalArgumentException(
                    "Invalid Base85 char: " + (char)input[i + j]);
                block = block * 85 + v;
            }
            // Pad with 84 ('u' equivalent) if short block
            for (int j = remaining; j < 5; j++) {
                block = block * 85 + 84;
            }
            int outputBytes = (remaining < 5) ? remaining - 1 : 4;
            for (int k = (outputBytes - 1) * 8; k >= 0; k -= 8) {
                out.write((int)((block >> k) & 0xFF));
            }
            i += 5;
        }
        return out.toByteArray();
    }
}
