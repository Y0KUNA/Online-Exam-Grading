package vn.edu.examgrading.common.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HmacSigner {
    private HmacSigner() { }
    public static String sign(String secret, String canonicalRequest) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException("Unable to sign request", ex); }
    }
    public static boolean verify(String secret, String canonicalRequest, String suppliedSignature) {
        if (suppliedSignature == null || suppliedSignature.isBlank()) return false;
        return MessageDigest.isEqual(sign(secret, canonicalRequest).getBytes(StandardCharsets.US_ASCII),
            suppliedSignature.getBytes(StandardCharsets.US_ASCII));
    }
}
