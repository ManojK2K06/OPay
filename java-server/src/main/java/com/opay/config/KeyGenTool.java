package com.opay.config;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * Run this ONCE to generate the server P-256 keypair.
 * Copy the output into application.properties.
 *
 * Usage: mvn exec:java -Dexec.mainClass=com.opay.config.KeyGenTool
 */
public class KeyGenTool {

    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "BC");
        kpg.initialize(new ECGenParameterSpec("P-256"), new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        String privB64 = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        String pubB64  = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());

        System.out.println("# Paste into application.properties:");
        System.out.println("opay.crypto.server-private-key=" + privB64);
        System.out.println("opay.crypto.server-public-key="  + pubB64);
        System.out.println();
        System.out.println("# Paste the public key bytes (hex) into the iOS app bundle:");

        // Extract raw 64-byte pubkey (x||y) for iOS
        byte[] enc = kp.getPublic().getEncoded();
        // Last 64 bytes of the X.509 encoded key are x||y for P-256
        byte[] raw = java.util.Arrays.copyOfRange(enc, enc.length - 64, enc.length);
        System.out.println("Server public key (raw base64 for iOS): "
                + Base64.getEncoder().encodeToString(raw));
    }
}
