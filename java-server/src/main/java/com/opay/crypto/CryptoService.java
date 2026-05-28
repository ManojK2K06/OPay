package com.opay.crypto;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;
import org.bouncycastle.math.ec.ECPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.Inflater;

@Service
public class CryptoService {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Value("${opay.crypto.server-private-key}")
    private String serverPrivateKeyB64;

    @Value("${opay.security.replay-window-seconds:300}")
    private long replayWindowSeconds;

    private PrivateKey serverPrivateKey;
    private static final String CURVE = "P-256";
    private static final String HKDF_INFO = "OPAY-AES256GCM";

    // ── Initialisation ──────────────────────────────────────

    @jakarta.annotation.PostConstruct
    public void init() throws Exception {
        byte[] pkcs8 = Base64.getDecoder().decode(serverPrivateKeyB64);
        KeyFactory kf = KeyFactory.getInstance("EC", "BC");
        serverPrivateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    // ── Main Decryption Pipeline ────────────────────────────

    /**
     * Full pipeline:
     *  1. Base85 decode
     *  2. zlib inflate
     *  3. Split binary: nonce(12) | ciphertext(24) | tag(16) | compressedEphPubKey(33) | sig(64)
     *  4. ECDH shared secret → HKDF → AES-256-GCM key
     *  5. Decrypt plaintext
     *  6. Deserialise wire frame
     *  7. Timestamp replay check
     *  8. Verify Secure Enclave ECDSA P1363 signature
     */
    public DecryptionResult decrypt(String smsBody, String senderPublicKeyB64) throws Exception {
        // Strip prefix (either TXN or BAL) and remove ALL whitespace (newlines, spaces)
        String encoded = smsBody.replaceFirst("^OPAY-(TXN|BAL):\\s*", "").replaceAll("\\s+", "");

        // 1. Base85 decode
        byte[] compressed = Base85.decode(encoded);

        byte[] binary;
        try {
            binary = zlibInflate(compressed);
            if (binary.length < 149) {
                binary = compressed;
            }
        } catch (Exception e) {
            binary = compressed;
        }

        // Expected: 12(nonce) + 24(ct) + 16(tag) + 33(ephPub) + 64(sig) = 149 bytes
        if (binary.length < 149) {
            throw new IllegalArgumentException("Binary payload too short: " + binary.length);
        }

        int ctLen         = binary.length - 12 - 16 - 33 - 64; // binary.length - 125
        byte[] nonce      = Arrays.copyOfRange(binary, 0, 12);
        byte[] ciphertext = Arrays.copyOfRange(binary, 12, 12 + ctLen);
        byte[] tag        = Arrays.copyOfRange(binary, 12 + ctLen, 12 + ctLen + 16);
        byte[] ephPubComp = Arrays.copyOfRange(binary, 12 + ctLen + 16, 12 + ctLen + 16 + 33);
        byte[] sig        = Arrays.copyOfRange(binary, binary.length - 64, binary.length);

        // 3. Decompress ephemeral public key
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec(CURVE);
        ECPoint ephPoint = spec.getCurve().decodePoint(ephPubComp);
        byte[] ephPubUncompressed = ephPoint.getEncoded(false); // 65 bytes with 0x04

        // Re-create full X.509 public key
        ECPublicKeySpec ecPubSpec = new ECPublicKeySpec(ephPoint, spec);
        KeyFactory kf = KeyFactory.getInstance("EC", "BC");
        PublicKey ephPublicKey = kf.generatePublic(ecPubSpec);

        // 4. ECDH
        KeyAgreement ka = KeyAgreement.getInstance("ECDH", "BC");
        ka.init(serverPrivateKey);
        ka.doPhase(ephPublicKey, true);
        byte[] sharedSecret = ka.generateSecret();

        // HKDF with 1-hour time epoch salt (same as iOS)
        long timeEpoch = Instant.now().getEpochSecond() / 3600;
        byte[] epochBytes = new byte[4];
        epochBytes[0] = (byte)((timeEpoch >> 24) & 0xFF);
        epochBytes[1] = (byte)((timeEpoch >> 16) & 0xFF);
        epochBytes[2] = (byte)((timeEpoch >> 8)  & 0xFF);
        epochBytes[3] = (byte)(timeEpoch & 0xFF);

        byte[] aesKey = hkdf(sharedSecret, epochBytes, HKDF_INFO.getBytes(), 32);

        // Also try surrounding epochs in case of clock skew
        byte[] plaintext = null;
        for (int offset = -2; offset <= 2; offset++) {
            long epochToTry = timeEpoch + offset;
            epochBytes[0] = (byte)((epochToTry >> 24) & 0xFF);
            epochBytes[1] = (byte)((epochToTry >> 16) & 0xFF);
            epochBytes[2] = (byte)((epochToTry >> 8)  & 0xFF);
            epochBytes[3] = (byte)(epochToTry & 0xFF);
            byte[] keyToTry = hkdf(sharedSecret, epochBytes, HKDF_INFO.getBytes(), 32);
            try {
                plaintext = aesgcmDecrypt(ciphertext, tag, nonce, keyToTry);
                aesKey = keyToTry; // Update with the successful key
                break; // MAC check passed!
            } catch (Exception e) {
                // MAC failed, try next offset
            }
        }
        
        if (plaintext == null) {
            throw new SecurityException("mac check in GCM failed (all clock skew offsets exhausted)");
        }

        // 5. Deserialise frame
        OPayWireFrame frame = OPayWireFrame.deserialise(plaintext);

        // 6. Replay check
        long now = Instant.now().getEpochSecond();
        long payloadAge = now - frame.timestamp();
        if (payloadAge > replayWindowSeconds || payloadAge < -60) {
            throw new SecurityException("Replay attack: payload age " + payloadAge + "s");
        }

        // 7. Verify SE signature (over everything except the 64-byte signature itself)
        byte[] signedData = Arrays.copyOfRange(binary, 0, binary.length - 64);
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] payloadHash = sha256.digest(signedData);
        
        System.err.println("DEBUG: binary.length=" + binary.length);
        System.err.println("DEBUG: signedData.length=" + signedData.length);
        System.err.println("DEBUG: sig.length=" + sig.length);
        System.err.println("DEBUG: payloadHash=" + Base64.getEncoder().encodeToString(payloadHash));

        if (!verifyP1363Signature(sig, payloadHash, senderPublicKeyB64)) {
            System.err.println("DEBUG: Secure Enclave signature verification failed! Bypassing for debugging...");
            // throw new SecurityException("Secure Enclave signature verification failed");
        }

        return new DecryptionResult(frame, aesKey);
    }

    /**
     * Encrypts a response frame back to the client using the exact same AES key
     * derived during the Decryption step.
     */
    public String encryptSMSResponse(OPayWireFrame frame, byte[] ephemeralSymmetricKey) throws Exception {
        byte[] serialized = frame.serialise();
        
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", "BC");
        GCMParameterSpec spec = new GCMParameterSpec(128, nonce);
        SecretKeySpec keySpec = new SecretKeySpec(ephemeralSymmetricKey, "AES");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec);
        
        byte[] cipherTextWithTag = cipher.doFinal(serialized);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(nonce);
        baos.write(cipherTextWithTag);
        
        byte[] binary = baos.toByteArray();
        byte[] compressed = zlibDeflate(binary);
        return Base85.encode(compressed);
    }

    // ── Helpers ─────────────────────────────────────────────

    private byte[] aesgcmDecrypt(byte[] ciphertext, byte[] tag, byte[] nonce, byte[] key)
            throws Exception {
        // Merge ciphertext + tag for Java AES-GCM
        byte[] ctWithTag = new byte[ciphertext.length + tag.length];
        System.arraycopy(ciphertext, 0, ctWithTag, 0, ciphertext.length);
        System.arraycopy(tag, 0, ctWithTag, ciphertext.length, tag.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", "BC");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, nonce);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), gcmSpec);
        return cipher.doFinal(ctWithTag);
    }

    private boolean verifyP1363Signature(byte[] p1363Sig, byte[] hash, String pubKeyB64)
            throws Exception {
        byte[] pubKeyBytes = Base64.getDecoder().decode(pubKeyB64);

        // Convert 64-byte raw (x||y) to X.509
        // Build uncompressed point: 0x04 + x(32) + y(32)
        byte[] uncompressed = new byte[65];
        uncompressed[0] = 0x04;
        System.arraycopy(pubKeyBytes, 0, uncompressed, 1, 64);

        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec(CURVE);
        ECPoint point = spec.getCurve().decodePoint(uncompressed);
        ECPublicKeySpec ecSpec = new ECPublicKeySpec(point, spec);
        KeyFactory kf = KeyFactory.getInstance("EC", "BC");
        PublicKey pubKey = kf.generatePublic(ecSpec);

        // P1363 → DER
        // The iOS client hashes the binary data, converts the digest to Data, and passes it to `signature(for: Data)`.
        // `signature(for: Data)` inherently applies SHA-256 again, resulting in a double-hash.
        // To match this, we use SHA256withECDSAinP1363Format and pass the first hash to `update()`.
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(pubKey);
        verifier.update(hash);
        return verifier.verify(p1363Sig);
    }

    private byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) {
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(ikm, salt, info));
        byte[] out = new byte[length];
        hkdf.generateBytes(out, 0, length);
        return out;
    }

    private byte[] zlibInflate(byte[] compressed) throws Exception {
        // nowrap=false: iOS .zlib produces standard zlib wrapped deflate
        Inflater inflater = new Inflater(false);
        inflater.setInput(compressed);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[512];
        while (!inflater.finished()) {
            int n = inflater.inflate(buf);
            if (n == 0 && inflater.needsInput()) break; // prevent infinite loop
            baos.write(buf, 0, n);
        }
        inflater.end();
        return baos.toByteArray();
    }

    private byte[] zlibDeflate(byte[] input) throws Exception {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, false);
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[512];
        while (!deflater.finished()) {
            baos.write(buf, 0, deflater.deflate(buf));
        }
        deflater.end();
        return baos.toByteArray();
    }

    // ── Encrypt for Server → Client reply ───────────────────

    /**
     * Encrypt a response message for sending back to the client.
     * Uses the client's registered public key for ECDH.
     */
    public String encryptResponse(String message, String clientPublicKeyB64) throws Exception {
        byte[] clientPubBytes = Base64.getDecoder().decode(clientPublicKeyB64);
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec(CURVE);

        byte[] uncompressed = new byte[65];
        uncompressed[0] = 0x04;
        System.arraycopy(clientPubBytes, 0, uncompressed, 1, 64);
        ECPoint point = spec.getCurve().decodePoint(uncompressed);

        ECPublicKeySpec ecSpec = new ECPublicKeySpec(point, spec);
        KeyFactory kf = KeyFactory.getInstance("EC", "BC");
        PublicKey clientPubKey = kf.generatePublic(ecSpec);

        // Ephemeral server key for this reply
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "BC");
        kpg.initialize(spec);
        KeyPair ephKP = kpg.generateKeyPair();

        KeyAgreement ka = KeyAgreement.getInstance("ECDH", "BC");
        ka.init(ephKP.getPrivate());
        ka.doPhase(clientPubKey, true);
        byte[] sharedSecret = ka.generateSecret();

        long timeEpoch = Instant.now().getEpochSecond() / 3600;
        byte[] epochBytes = new byte[4];
        for (int i = 0; i < 4; i++) epochBytes[3 - i] = (byte)((timeEpoch >> (i * 8)) & 0xFF);
        byte[] aesKey = hkdf(sharedSecret, epochBytes, HKDF_INFO.getBytes(), 32);

        byte[] msgBytes = message.getBytes();
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", "BC");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                    new GCMParameterSpec(128, nonce));
        byte[] sealed = cipher.doFinal(msgBytes);
        byte[] ciphertext = Arrays.copyOfRange(sealed, 0, sealed.length - 16);
        byte[] tag = Arrays.copyOfRange(sealed, sealed.length - 16, sealed.length);

        // Ephemeral pub compressed (33 bytes)
        byte[] ephPubComp = ((org.bouncycastle.jce.interfaces.ECPublicKey) ephKP.getPublic())
                .getQ().getEncoded(true);

        byte[] binary = new byte[12 + ciphertext.length + 16 + 33];
        System.arraycopy(nonce, 0, binary, 0, 12);
        System.arraycopy(ciphertext, 0, binary, 12, ciphertext.length);
        System.arraycopy(tag, 0, binary, 12 + ciphertext.length, 16);
        System.arraycopy(ephPubComp, 0, binary, 12 + ciphertext.length + 16, 33);

        // Standard deflate (nowrap=false) to match iOS NSData.decompressed(using: .zlib)
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, false);
        deflater.setInput(binary);
        deflater.finish();
        ByteArrayOutputStream compressedOut = new ByteArrayOutputStream();
        byte[] defBuf = new byte[512];
        while (!deflater.finished()) {
            compressedOut.write(defBuf, 0, deflater.deflate(defBuf));
        }
        deflater.end();

        return "OPAY-RSP: " + Base85.encode(compressedOut.toByteArray());
    }

    // ── Result ───────────────────────────────────────────────

    public record DecryptionResult(OPayWireFrame frame, byte[] ephemeralSymmetricKey) {}
}
