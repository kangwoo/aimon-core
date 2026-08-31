package at.aimon.core.memory.redaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RedactionPolicyTest {

    private final DefaultRedactionPolicy defaultPolicy = new DefaultRedactionPolicy();
    private final StrictRedactionPolicy strictPolicy = new StrictRedactionPolicy();

    @Nested
    @DisplayName("DefaultRedactionPolicy")
    class DefaultPolicyTests {

        @Test
        @DisplayName("masks AWS access keys")
        void masksAwsKey() {
            RedactionResult result = defaultPolicy.redact("key=AKIAIOSFODNN7EXAMPLE end");

            assertThat(result.isModified()).isTrue();
            assertThat(result.getRedactedContent()).doesNotContain("AKIAIOSFODNN7EXAMPLE")
                    .contains("[REDACTED:AWS_KEY]");
            assertThat(result.getMatches()).extracting(RedactionMatch::getPattern)
                    .contains(DefaultRedactionPolicy.CATEGORY_AWS_KEY);
        }

        @Test
        @DisplayName("masks JWT (3-segment) tokens")
        void masksJwt() {
            String jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjMifQ.signature_part";
            RedactionResult result = defaultPolicy.redact("Authorization: Bearer " + jwt);

            assertThat(result.getRedactedContent()).doesNotContain(jwt).contains("[REDACTED:JWT]");
            assertThat(result.getMatches()).anyMatch(m -> DefaultRedactionPolicy.CATEGORY_JWT.equals(m.getPattern()));
        }

        @Test
        @DisplayName("masks RFC1918 private IPv4 addresses")
        void masksPrivateIps() {
            RedactionResult result = defaultPolicy.redact("hosts: 10.0.0.1, 192.168.1.5, 172.20.0.1");

            assertThat(result.getRedactedContent()).doesNotContain("10.0.0.1").doesNotContain("192.168.1.5")
                    .doesNotContain("172.20.0.1");
            assertThat(result.getMatches())
                    .filteredOn(m -> DefaultRedactionPolicy.CATEGORY_PRIVATE_IP.equals(m.getPattern())).hasSize(3);
        }

        @Test
        @DisplayName("does not mask public IPv4 addresses")
        void leavesPublicIp() {
            RedactionResult result = defaultPolicy.redact("dns: 8.8.8.8");

            assertThat(result.isModified()).isFalse();
            assertThat(result.getRedactedContent()).isEqualTo("dns: 8.8.8.8");
        }

        @Test
        @DisplayName("masks e-mail addresses")
        void masksEmail() {
            RedactionResult result = defaultPolicy.redact("contact me at john.doe@example.com please");

            assertThat(result.getRedactedContent()).doesNotContain("john.doe@example.com").contains("[REDACTED:EMAIL]");
            assertThat(result.getMatches()).anyMatch(m -> DefaultRedactionPolicy.CATEGORY_EMAIL.equals(m.getPattern()));
        }

        @Test
        @DisplayName("masks multi-label subdomain e-mail addresses")
        void masksSubdomainEmail() {
            RedactionResult result = defaultPolicy.redact("ping ops@mail.corp.example.com now");

            assertThat(result.getRedactedContent()).doesNotContain("ops@mail.corp.example.com")
                    .contains("[REDACTED:EMAIL]");
        }

        @Test
        @DisplayName("e-mail redaction stays linear on large adversarial input (ReDoS guard)")
        void emailRedactionIsLinear() {
            // Domain-heavy input is the shape that caused catastrophic backtracking before the
            // possessive rewrite; with linear matching it is milliseconds, not seconds. This guards a
            // mandatory, synchronous security gate against a denial-of-service regression.
            String adversarial = "user@" + "ab.".repeat(16_000) + "!";
            long startNanos = System.nanoTime();
            RedactionResult result = defaultPolicy.redact(adversarial);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

            assertThat(result).isNotNull();
            assertThat(elapsedMs).as("redact() must remain linear; took %d ms", elapsedMs).isLessThan(2_000L);
        }

        @Test
        @DisplayName("masks generic key=value secrets")
        void masksGenericSecrets() {
            RedactionResult r1 = defaultPolicy.redact("password=hunter2");
            RedactionResult r2 = defaultPolicy.redact("api_key: abc123XYZ");
            RedactionResult r3 = defaultPolicy.redact("Token = ghp_abcDEF");

            assertThat(r1.getRedactedContent()).contains("[REDACTED:SECRET]").doesNotContain("hunter2");
            assertThat(r2.getRedactedContent()).contains("[REDACTED:SECRET]").doesNotContain("abc123XYZ");
            assertThat(r3.getRedactedContent()).contains("[REDACTED:SECRET]").doesNotContain("ghp_abcDEF");
        }

        @Test
        @DisplayName("returns unchanged for empty input")
        void emptyInputUnchanged() {
            RedactionResult result = defaultPolicy.redact("");

            assertThat(result.isModified()).isFalse();
            assertThat(result.getRedactedContent()).isEmpty();
            assertThat(result.getMatches()).isEmpty();
        }

        @Test
        @DisplayName("returns unchanged for benign text")
        void benignInputUnchanged() {
            String input = "The quick brown fox jumps over the lazy dog.";
            RedactionResult result = defaultPolicy.redact(input);

            assertThat(result.isModified()).isFalse();
            assertThat(result.getRedactedContent()).isEqualTo(input);
            assertThat(result.getMatches()).isEmpty();
        }

        @Test
        @DisplayName("idempotent: applying redaction twice yields the same content")
        void idempotency() {
            String input = "AKIAIOSFODNN7EXAMPLE leaked. Email user@example.com on 10.0.0.5; password=hunter2; "
                    + "JWT=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzMSJ9.sigpart";

            RedactionResult once = defaultPolicy.redact(input);
            RedactionResult twice = defaultPolicy.redact(once.getRedactedContent());

            assertThat(twice.getRedactedContent()).isEqualTo(once.getRedactedContent());
            assertThat(twice.isModified()).isFalse();
        }

        @Test
        @DisplayName("matches are recorded in pass order with correct categories")
        void matchOrderAndCategories() {
            // AWS_KEY runs first, EMAIL after JWT/SECRET, PRIVATE_IP last.
            RedactionResult result = defaultPolicy.redact("AKIAIOSFODNN7EXAMPLE / 10.0.0.1 / a@b.co");

            assertThat(result.getMatches()).extracting(RedactionMatch::getPattern).containsExactly(
                    DefaultRedactionPolicy.CATEGORY_AWS_KEY, DefaultRedactionPolicy.CATEGORY_EMAIL,
                    DefaultRedactionPolicy.CATEGORY_PRIVATE_IP);
            assertThat(result.getCategories()).containsExactly(DefaultRedactionPolicy.CATEGORY_AWS_KEY,
                    DefaultRedactionPolicy.CATEGORY_EMAIL, DefaultRedactionPolicy.CATEGORY_PRIVATE_IP);
        }

        @Test
        @DisplayName("rejects null input")
        void nullInputThrows() {
            assertThatNullPointerException().isThrownBy(() -> defaultPolicy.redact(null));
        }
    }

    @Nested
    @DisplayName("StrictRedactionPolicy")
    class StrictPolicyTests {

        @Test
        @DisplayName("masks fuzzy-matched secret keywords (typo of password)")
        void masksTypoKeyword() {
            RedactionResult result = strictPolicy.redact("passwrd: secret123");

            assertThat(result.isModified()).isTrue();
            assertThat(result.getRedactedContent()).contains("[REDACTED:SECRET]").doesNotContain("secret123")
                    .doesNotContain("passwrd");
            assertThat(result.getMatches())
                    .anyMatch(m -> DefaultRedactionPolicy.CATEGORY_SECRET.equals(m.getPattern()));
        }

        @Test
        @DisplayName("does not mask plain prose")
        void leavesPlainProse() {
            RedactionResult result = strictPolicy.redact("hello world");

            assertThat(result.isModified()).isFalse();
            assertThat(result.getRedactedContent()).isEqualTo("hello world");
        }

        @Test
        @DisplayName("still applies all DefaultRedactionPolicy rules")
        void includesDefaultRules() {
            RedactionResult result = strictPolicy.redact("key AKIAIOSFODNN7EXAMPLE on 10.0.0.1");

            assertThat(result.getRedactedContent()).contains("[REDACTED:AWS_KEY]").contains("[REDACTED:PRIVATE_IP]");
        }

        @Test
        @DisplayName("rejects null input")
        void nullInputThrows() {
            assertThatNullPointerException().isThrownBy(() -> strictPolicy.redact(null));
        }
    }

    @Nested
    @DisplayName("RedactionResult")
    class ResultTests {

        @Test
        @DisplayName("unchanged() returns modified=false and empty matches")
        void unchangedFactory() {
            RedactionResult result = RedactionResult.unchanged("hello");

            assertThat(result.isModified()).isFalse();
            assertThat(result.getMatches()).isEmpty();
            assertThat(result.getRedactedContent()).isEqualTo("hello");
            assertThat(result.getCategories()).isEmpty();
        }

        @Test
        @DisplayName("of() computes modified from match list")
        void ofComputesModified() {
            RedactionMatch match = RedactionMatch.of("EMAIL", 0, 5, "[REDACTED:EMAIL]");
            RedactionResult populated = RedactionResult.of("[REDACTED:EMAIL]", List.of(match));
            RedactionResult empty = RedactionResult.of("plain", List.of());

            assertThat(populated.isModified()).isTrue();
            assertThat(empty.isModified()).isFalse();
        }

        @Test
        @DisplayName("getCategories() returns distinct, alphabetically sorted set")
        void categoriesSorted() {
            RedactionResult result = RedactionResult.of("redacted",
                    List.of(RedactionMatch.of("EMAIL", 0, 5, "[REDACTED:EMAIL]"),
                            RedactionMatch.of("AWS_KEY", 6, 10, "[REDACTED:AWS_KEY]"),
                            RedactionMatch.of("EMAIL", 11, 15, "[REDACTED:EMAIL]"),
                            RedactionMatch.of("PRIVATE_IP", 16, 20, "[REDACTED:PRIVATE_IP]")));

            assertThat(result.getCategories()).containsExactly("AWS_KEY", "EMAIL", "PRIVATE_IP");
        }

        @Test
        @DisplayName("rejects null arguments")
        void nullArgumentsThrow() {
            assertThatNullPointerException().isThrownBy(() -> RedactionResult.unchanged(null));
            assertThatNullPointerException().isThrownBy(() -> RedactionResult.of(null, List.of()));
            assertThatNullPointerException().isThrownBy(() -> RedactionResult.of("x", null));
        }
    }

    @Nested
    @DisplayName("RedactionMatch")
    class MatchTests {

        @Test
        @DisplayName("rejects invalid spans")
        void rejectsBadSpans() {
            assertThat(catchThrown(() -> RedactionMatch.of("X", -1, 5, "[X]")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(catchThrown(() -> RedactionMatch.of("X", 5, 5, "[X]")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(catchThrown(() -> RedactionMatch.of("X", 6, 5, "[X]")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects null and blank patterns")
        void rejectsBadPattern() {
            assertThatNullPointerException().isThrownBy(() -> RedactionMatch.of(null, 0, 1, "[X]"));
            assertThat(catchThrown(() -> RedactionMatch.of("  ", 0, 1, "[X]")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatNullPointerException().isThrownBy(() -> RedactionMatch.of("X", 0, 1, null));
        }

        private Throwable catchThrown(Runnable r) {
            try {
                r.run();
                return null;
            } catch (Throwable t) {
                return t;
            }
        }
    }
}
