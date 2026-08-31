package at.aimon.rewake.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class HmacSignatureVerifierTest {

    private final HmacSignatureVerifier verifier = new HmacSignatureVerifier();

    @Test
    void roundTripSignAndVerifySucceeds() {
        final byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        final String sig = verifier.sign(body, "s3cret");

        assertThat(verifier.verify(sig, body, "s3cret")).isTrue();
    }

    @Test
    void verifyAcceptsSha256PrefixedHeader() {
        final byte[] body = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
        final String sig = "sha256=" + verifier.sign(body, "secret");

        assertThat(verifier.verify(sig, body, "secret")).isTrue();
    }

    @Test
    void verifyRejectsTamperedBody() {
        final String sig = verifier.sign("a".getBytes(StandardCharsets.UTF_8), "secret");
        assertThat(verifier.verify(sig, "b".getBytes(StandardCharsets.UTF_8), "secret")).isFalse();
    }

    @Test
    void verifyRejectsWrongSecret() {
        final byte[] body = "x".getBytes(StandardCharsets.UTF_8);
        final String sig = verifier.sign(body, "right");
        assertThat(verifier.verify(sig, body, "wrong")).isFalse();
    }

    @Test
    void verifyRejectsMalformedHex() {
        assertThat(verifier.verify("not-hex!", "x".getBytes(StandardCharsets.UTF_8), "s")).isFalse();
        assertThat(verifier.verify("abc", "x".getBytes(StandardCharsets.UTF_8), "s")).isFalse(); // odd length
    }

    @Test
    void verifyRejectsTooShortHexEvenIfPrefixedCorrectly() {
        assertThat(verifier.verify("sha256=ab", "x".getBytes(StandardCharsets.UTF_8), "s")).isFalse();
    }

    @Test
    void rejectsNullArgs() {
        final byte[] body = "x".getBytes(StandardCharsets.UTF_8);
        assertThatNullPointerException().isThrownBy(() -> verifier.verify(null, body, "s"));
        assertThatNullPointerException().isThrownBy(() -> verifier.verify("abcd", null, "s"));
        assertThatNullPointerException().isThrownBy(() -> verifier.verify("abcd", body, null));
    }

    @Test
    void rejectsEmptySecret() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> verifier.verify("abcd", "x".getBytes(StandardCharsets.UTF_8), ""));
        assertThatIllegalArgumentException().isThrownBy(() -> verifier.sign("x".getBytes(StandardCharsets.UTF_8), ""));
    }
}
