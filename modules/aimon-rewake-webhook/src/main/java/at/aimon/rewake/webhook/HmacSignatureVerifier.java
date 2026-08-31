package at.aimon.rewake.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies HMAC-SHA256 signatures on inbound webhook requests.
 *
 * <p>
 * The verifier compares a hex-encoded signature header against the HMAC-SHA256 of the request body using a shared
 * secret. Comparison is constant-time via {@link MessageDigest#isEqual(byte[], byte[])} so timing-side-channel
 * attacks cannot probe the secret one byte at a time.
 *
 * <p>
 * Hex encoding (lowercase, no separators) is the wire format. An optional {@code "sha256="} prefix on the header
 * value is tolerated so the verifier matches the convention used by GitHub, Slack, and other common webhook
 * sources without forcing a particular header style on senders.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class HmacSignatureVerifier {

    public static final String ALGORITHM = "HmacSHA256";
    public static final String SHA256_PREFIX = "sha256=";

    /**
     * Returns {@code true} when {@code headerValue} matches the HMAC-SHA256 of {@code body} under {@code secret}.
     *
     * @param headerValue
     *            value of the signature header, optionally prefixed with {@code "sha256="} (must not be null)
     * @param body
     *            raw request body bytes (must not be null)
     * @param secret
     *            shared secret (must not be null or empty)
     * @return {@code true} on match, {@code false} on any mismatch (length, encoding, or value)
     * @throws NullPointerException
     *             if any argument is null
     * @throws IllegalArgumentException
     *             if the secret is empty
     */
    public boolean verify(String headerValue, byte[] body, String secret) {
        Objects.requireNonNull(headerValue, "headerValue cannot be null");
        Objects.requireNonNull(body, "body cannot be null");
        Objects.requireNonNull(secret, "secret cannot be null");
        if (secret.isEmpty()) {
            throw new IllegalArgumentException("secret cannot be empty");
        }
        final byte[] provided = decodeHex(stripPrefix(headerValue));
        if (provided == null) {
            return false;
        }
        final byte[] expected = compute(body, secret);
        return MessageDigest.isEqual(expected, provided);
    }

    /**
     * Computes the lowercase hex-encoded HMAC-SHA256 signature of {@code body} under {@code secret}. Useful for
     * test fixtures that need to construct a valid signature.
     *
     * @param body
     *            raw bytes (must not be null)
     * @param secret
     *            shared secret (must not be null or empty)
     * @return lowercase hex-encoded signature
     */
    public String sign(byte[] body, String secret) {
        Objects.requireNonNull(body, "body cannot be null");
        Objects.requireNonNull(secret, "secret cannot be null");
        if (secret.isEmpty()) {
            throw new IllegalArgumentException("secret cannot be empty");
        }
        return encodeHex(compute(body, secret));
    }

    private static byte[] compute(byte[] body, String secret) {
        try {
            final Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(body);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("HmacSHA256 not available on this JVM", e);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("Invalid HMAC secret", e);
        }
    }

    private static String stripPrefix(String header) {
        return header.startsWith(SHA256_PREFIX) ? header.substring(SHA256_PREFIX.length()) : header;
    }

    private static String encodeHex(byte[] bytes) {
        final char[] hex = "0123456789abcdef".toCharArray();
        final char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            final int v = bytes[i] & 0xff;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0f];
        }
        return new String(out);
    }

    private static byte[] decodeHex(String hex) {
        if (hex.length() % 2 != 0) {
            return null;
        }
        final byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            final int hi = digit(hex.charAt(i * 2));
            final int lo = digit(hex.charAt(i * 2 + 1));
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int digit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
