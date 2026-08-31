package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SensitivePatternRedactor}: covers the no-op factory, the built-in default patterns (one
 * assertion per credential type), null/empty handling, and custom pattern composition.
 */
class SensitivePatternRedactorTest {

    @Test
    void noneReturnsInputUnchanged() {
        final SensitivePatternRedactor redactor = SensitivePatternRedactor.none();

        assertThat(redactor.redact("AKIAIOSFODNN7EXAMPLE Bearer abc")).isEqualTo("AKIAIOSFODNN7EXAMPLE Bearer abc");
        assertThat(redactor.isNoop()).isTrue();
        assertThat(redactor.getPatterns()).isEmpty();
    }

    @Test
    void noneIsSingleton() {
        assertThat(SensitivePatternRedactor.none()).isSameAs(SensitivePatternRedactor.none());
    }

    @Test
    void defaultsRedactsAwsAccessKey() {
        final SensitivePatternRedactor redactor = SensitivePatternRedactor.defaults();

        assertThat(redactor.redact("key=AKIAIOSFODNN7EXAMPLE end")).isEqualTo("key=[REDACTED:AWS_ACCESS_KEY] end");
        assertThat(redactor.redact("session=ASIAIOSFODNN7EXAMPLE end"))
                .isEqualTo("session=[REDACTED:AWS_ACCESS_KEY] end");
    }

    @Test
    void defaultsRedactsOpenAiApiKey() {
        final SensitivePatternRedactor redactor = SensitivePatternRedactor.defaults();

        final String input = "token=sk-abcdefghijklmnopqrstuvwxyz0123456789 done";

        assertThat(redactor.redact(input)).isEqualTo("token=[REDACTED:OPENAI_API_KEY] done");
    }

    @Test
    void defaultsRedactsSlackToken() {
        final SensitivePatternRedactor redactor = SensitivePatternRedactor.defaults();

        assertThat(redactor.redact("auth=xoxb-1234567890-abcdef")).isEqualTo("auth=[REDACTED:SLACK_TOKEN]");
        assertThat(redactor.redact("auth=xoxp-1234567890-abcdef")).isEqualTo("auth=[REDACTED:SLACK_TOKEN]");
    }

    @Test
    void defaultsRedactsBearerTokenCaseInsensitively() {
        final SensitivePatternRedactor redactor = SensitivePatternRedactor.defaults();

        assertThat(redactor.redact("Authorization: Bearer abcdefghijklmnop1234"))
                .isEqualTo("Authorization: [REDACTED:BEARER_TOKEN]");
        assertThat(redactor.redact("authorization: bearer abcdefghijklmnop1234"))
                .isEqualTo("authorization: [REDACTED:BEARER_TOKEN]");
    }

    @Test
    void defaultsRedactsJwt() {
        final SensitivePatternRedactor redactor = SensitivePatternRedactor.defaults();

        final String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NSJ9.signaturePart";

        assertThat(redactor.redact("token=" + jwt)).isEqualTo("token=[REDACTED:JWT]");
    }

    @Test
    void defaultsRedactsPemPrivateKeyBlock() {
        final SensitivePatternRedactor redactor = SensitivePatternRedactor.defaults();

        final String pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA...\n-----END RSA PRIVATE KEY-----";

        assertThat(redactor.redact("Key follows:\n" + pem + "\n(end)"))
                .isEqualTo("Key follows:\n[REDACTED:PRIVATE_KEY_BLOCK]\n(end)");
    }

    @Test
    void defaultsRedactsAllOccurrencesAndMultiplePatternsTogether() {
        final SensitivePatternRedactor redactor = SensitivePatternRedactor.defaults();

        final String input = "AKIAIOSFODNN7EXAMPLE and AKIAIOSFODNN7AAAAAAA, plus Bearer abcdefghijklmnopqrstu";

        final String output = redactor.redact(input);

        assertThat(output).doesNotContain("AKIA").doesNotContain("Bearer ");
        assertThat(output).contains("[REDACTED:AWS_ACCESS_KEY]").contains("[REDACTED:BEARER_TOKEN]");
    }

    @Test
    void defaultsLeavesUnrelatedTextUntouched() {
        final SensitivePatternRedactor redactor = SensitivePatternRedactor.defaults();

        final String input = "user=alice rolled out feature flag X to 50% of cluster eu-west-1.";

        assertThat(redactor.redact(input)).isEqualTo(input);
    }

    @Test
    void redactReturnsNullForNullInput() {
        assertThat(SensitivePatternRedactor.defaults().redact(null)).isNull();
    }

    @Test
    void redactReturnsEmptyForEmptyInput() {
        assertThat(SensitivePatternRedactor.defaults().redact("")).isEmpty();
    }

    @Test
    void customPatternsRunInDeclaredOrder() {
        final SensitivePatternRedactor redactor = SensitivePatternRedactor
                .of(List.of(RedactionPattern.of("FIRST", Pattern.compile("foo")),
                        RedactionPattern.of("SECOND", Pattern.compile("REDACTED:FIRST"))));

        // Second pattern observes the output of the first — proves serial composition.
        assertThat(redactor.redact("foo bar")).isEqualTo("[[REDACTED:SECOND]] bar");
    }

    @Test
    void ofWithEmptyListIsNoop() {
        final SensitivePatternRedactor redactor = SensitivePatternRedactor.of(List.of());

        assertThat(redactor.isNoop()).isTrue();
        assertThat(redactor.redact("anything goes")).isEqualTo("anything goes");
    }

    @Test
    void getPatternsIsUnmodifiable() {
        final SensitivePatternRedactor redactor = SensitivePatternRedactor
                .of(List.of(RedactionPattern.of("X", Pattern.compile("x"))));

        assertThatThrownBy(() -> redactor.getPatterns().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void ofRejectsNullList() {
        assertThatThrownBy(() -> SensitivePatternRedactor.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void defaultPatternsListIsImmutable() {
        final List<RedactionPattern> defaults = SensitivePatternRedactor.defaultPatterns();

        assertThat(defaults).hasSize(6);
        assertThatThrownBy(defaults::clear).isInstanceOf(UnsupportedOperationException.class);
    }
}
