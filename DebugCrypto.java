import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import com.opay.crypto.Base85;

public class DebugCrypto {
    private static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) {
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(ikm, salt, info));
        byte[] out = new byte[length];
        hkdf.generateBytes(out, 0, length);
        return out;
    }

    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        String privB64 = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgv2u6k5b1h7L5+v9jWn9b9u8X2Rk1T1D6yGzX2l9hJqGhRANCAAQcKtqaMXoEbEQCsoppuIW5bdl8ieaUZ1NVk0bcYJlqAQWsYjD8Xx0NpqJs6GWhtBKYNArs+RU1sXbnreZWiQKP";
        KeyFactory kf = KeyFactory.getInstance("EC", "BC");
        PrivateKey serverPrivateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privB64)));

        String payloadStr = new String(Files.readAllBytes(Paths.get("test_payload.txt"))).trim().substring(9);
        byte[] binary = Base85.decode(payloadStr);

        int ctLen         = binary.length - 12 - 16 - 33 - 64;
        byte[] nonce      = Arrays.copyOfRange(binary, 0, 12);
        byte[] ciphertext = Arrays.copyOfRange(binary, 12, 12 + ctLen);
        byte[] tag        = Arrays.copyOfRange(binary, 12 + ctLen, 12 + ctLen + 16);
        byte[] ephPubComp = Arrays.copyOfRange(binary, 12 + ctLen + 16, 12 + ctLen + 16 + 33);

        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("prime256v1");
        ECPoint ephPoint = spec.getCurve().decodePoint(ephPubComp);
        ECPublicKeySpec ecPubSpec = new ECPublicKeySpec(ephPoint, spec);
        PublicKey ephPublicKey = kf.generatePublic(ecPubSpec);

        KeyAgreement ka = KeyAgreement.getInstance("ECDH", "BC");
        ka.init(serverPrivateKey);
        ka.doPhase(ephPublicKey, true);
        byte[] sharedSecret = ka.generateSecret();
        
        long baseEpoch = System.currentTimeMillis() / 1000 / 3600;
        boolean found = false;
        for (long timeEpoch = baseEpoch - 100; timeEpoch <= baseEpoch + 100; timeEpoch++) {
            byte[] epochBytes = new byte[4];
            epochBytes[0] = (byte)((timeEpoch >> 24) & 0xFF);
            epochBytes[1] = (byte)((timeEpoch >> 16) & 0xFF);
            epochBytes[2] = (byte)((timeEpoch >> 8)  & 0xFF);
            epochBytes[3] = (byte)(timeEpoch & 0xFF);

            byte[] aesKey = hkdf(sharedSecret, epochBytes, "OPAY-AES256GCM".getBytes(), 32);
            
            byte[] ctWithTag = new byte[ciphertext.length + tag.length];
            System.arraycopy(ciphertext, 0, ctWithTag, 0, ciphertext.length);
            System.arraycopy(tag, 0, ctWithTag, ciphertext.length, tag.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", "BC");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(128, nonce);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), gcmSpec);
            
            try {
                byte[] pt = cipher.doFinal(ctWithTag);
                System.out.println("SUCCESS! Plaintext len: " + pt.length + ", Epoch: " + timeEpoch);
                found = true;
                break;
            } catch (Exception e) {}
        }
        if (!found) {
            System.out.println("MAC CHECK FAILED FOR ALL EPOCHS.");
        }
    }
}
