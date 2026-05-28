import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.security.interfaces.ECPrivateKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.ECNamedCurveTable;

public class PrintPubKey {
    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        String b64 = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgv2u6k5b1h7L5+v9jWn9b9u8X2Rk1T1D6yGzX2l9hJqGhRANCAAQcKtqaMXoEbEQCsoppuIW5bdl8ieaUZ1NVk0bcYJlqAQWsYjD8Xx0NpqJs6GWhtBKYNArs+RU1sXbnreZWiQKP";
        byte[] pkcs8 = Base64.getDecoder().decode(b64);
        KeyFactory kf = KeyFactory.getInstance("EC", "BC");
        PrivateKey pk = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        
        // Calculate Public Key
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("P-256");
        org.bouncycastle.math.ec.ECPoint Q = spec.getG().multiply(((org.bouncycastle.jce.interfaces.ECPrivateKey)pk).getD());
        byte[] uncompressed = Q.getEncoded(false); // 65 bytes
        
        // Skip first byte (0x04) to get 64 byte X||Y
        byte[] raw64 = new byte[64];
        System.arraycopy(uncompressed, 1, raw64, 0, 64);
        System.out.println("iOS format (64 bytes): " + Base64.getEncoder().encodeToString(raw64));
    }
}
