package at.aimon.core.tools.code.shell;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The authenticated line protocol between the shell engine and its Python runtime, host side
 * ({@code docs/design/tool/programmatic-tool-calling-shell.md} §7).
 *
 * <p>
 * A request line is {@code <TYPE> <seq> <payload> <hex(HMAC-SHA256(K, "<TYPE> <seq> <payload>"))>}; a reply file is
 * {@code hex(HMAC-SHA256(K, "R" ‖ seq ‖ 0x00 ‖ payload))}, a line feed, and the payload. The host re-enforces every
 * rule: the runtime and the model's code are one process, and the channel is a spool file a co-resident process can
 * append to.
 *
 * <p>
 * Stateless apart from the key; thread-safe.
 */
final class PtcProtocol {

    /** A line, terminator included, is shorter than this many UTF-8 bytes (the local shell splits at 64 KiB). */
    static final int MAX_LINE_BYTES = 64 * 1024;

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MAC_HEX_LENGTH = 64;

    private final SecretKeySpec key;

    PtcProtocol(byte[] key) {
        this.key = new SecretKeySpec(Objects.requireNonNull(key, "key cannot be null"), ALGORITHM);
    }

    /** One verified line. */
    static final class Line {
        private final String type;
        private final long seq;
        private final String payload;

        Line(String type, long seq, String payload) {
            this.type = type;
            this.seq = seq;
            this.payload = payload;
        }

        String type() {
            return type;
        }

        long seq() {
            return seq;
        }

        String payload() {
            return payload;
        }
    }

    /**
     * Verifies a line's MAC and form. Says nothing about its seq or type — the caller checks the sequence.
     *
     * @return the line, or empty when it is malformed, too long or does not authenticate
     */
    Optional<Line> verify(String line) {
        if (line.length() + 1 >= MAX_LINE_BYTES || line.getBytes(StandardCharsets.UTF_8).length + 1 >= MAX_LINE_BYTES) {
            return Optional.empty();
        }
        final int macStart = line.lastIndexOf(' ');
        if (macStart < 0 || line.length() - macStart - 1 != MAC_HEX_LENGTH) {
            return Optional.empty();
        }
        final String body = line.substring(0, macStart);
        final byte[] presented;
        try {
            presented = HexFormat.of().parseHex(line, macStart + 1, line.length());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(presented, mac(body.getBytes(StandardCharsets.UTF_8)))) {
            return Optional.empty();
        }
        final int typeEnd = body.indexOf(' ');
        if (typeEnd <= 0) {
            return Optional.empty();
        }
        final int seqEnd = body.indexOf(' ', typeEnd + 1);
        if (seqEnd < 0) {
            return Optional.empty();
        }
        final String seqText = body.substring(typeEnd + 1, seqEnd);
        if (seqText.isEmpty() || seqText.length() > 18 || seqText.charAt(0) == '0'
                || !seqText.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return Optional.empty();
        }
        return Optional.of(new Line(body.substring(0, typeEnd), Long.parseLong(seqText), body.substring(seqEnd + 1)));
    }

    /** The line for {@code type}/{@code seq}/{@code payload} with its MAC — what a well-behaved runtime writes. */
    String line(String type, long seq, String payload) {
        final String body = type + " " + seq + " " + payload;
        return body + " " + HexFormat.of().formatHex(mac(body.getBytes(StandardCharsets.UTF_8)));
    }

    /** The bytes of the reply file for {@code seq}: the MAC in hex, a line feed, the payload. */
    byte[] reply(long seq, byte[] payload) {
        final byte[] header = (HexFormat.of().formatHex(replyMac(seq, payload)) + "\n")
                .getBytes(StandardCharsets.US_ASCII);
        final byte[] file = new byte[header.length + payload.length];
        System.arraycopy(header, 0, file, 0, header.length);
        System.arraycopy(payload, 0, file, header.length, payload.length);
        return file;
    }

    private byte[] replyMac(long seq, byte[] payload) {
        final byte[] prefix = ("R" + seq).getBytes(StandardCharsets.US_ASCII);
        final byte[] message = new byte[prefix.length + 1 + payload.length];
        System.arraycopy(prefix, 0, message, 0, prefix.length);
        message[prefix.length] = 0;
        System.arraycopy(payload, 0, message, prefix.length + 1, payload.length);
        return mac(message);
    }

    private byte[] mac(byte[] message) {
        try {
            final Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
