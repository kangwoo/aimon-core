package at.aimon.core.tracing.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.tracing.SpanRedactor;

@DisplayName("KeyPatternSpanRedactor (TRACE-02)")
class KeyPatternSpanRedactorTest {

    private final SpanRedactor redactor = SpanRedactor.defaultRedactor();

    @Test
    @DisplayName("masks values under sensitive keys, keeps the rest")
    void masksSensitiveKeys() {
        final Map<String, Object> input = Map.of("token", "abc123", "api_key", "k-1", "Authorization", "Bearer x",
                "command", "git status");

        @SuppressWarnings("unchecked")
        final Map<String, Object> out = (Map<String, Object>) redactor.redact(input);

        assertThat(out.get("token")).isEqualTo(KeyPatternSpanRedactor.REDACTED);
        assertThat(out.get("api_key")).isEqualTo(KeyPatternSpanRedactor.REDACTED);
        assertThat(out.get("Authorization")).isEqualTo(KeyPatternSpanRedactor.REDACTED);
        assertThat(out.get("command")).isEqualTo("git status");
    }

    @Test
    @DisplayName("a separator inside the word does not hide it — api-key is masked like api_key")
    void masksAcrossSeparators() {
        // The fragment list carried two spellings of one word, apikey and api_key, which is the shape of a list
        // that enumerates separators by hand and will keep missing one. The dashed spelling is not exotic: it is
        // the header Azure OpenAI authenticates with and the property name this project publishes
        // (aimon.llm.api-key), so it is the spelling most likely to arrive in a tool argument.
        final Map<String, Object> input = Map.of("api-key", "k-1", "x-api-key", "k-2", "Api Key", "k-3", "file_path",
                "/tmp/x");

        @SuppressWarnings("unchecked")
        final Map<String, Object> out = (Map<String, Object>) redactor.redact(input);

        assertThat(out.get("api-key")).isEqualTo(KeyPatternSpanRedactor.REDACTED);
        assertThat(out.get("x-api-key")).isEqualTo(KeyPatternSpanRedactor.REDACTED);
        assertThat(out.get("Api Key")).isEqualTo(KeyPatternSpanRedactor.REDACTED);
        assertThat(out.get("file_path")).isEqualTo("/tmp/x");
    }

    @Test
    @DisplayName("recurses into nested maps and lists")
    void recursesIntoNestedStructures() {
        final Map<String, Object> nested = Map.of("config", Map.of("password", "p", "host", "db"), "items",
                List.of(Map.of("secret", "s"), Map.of("name", "ok")));

        @SuppressWarnings("unchecked")
        final Map<String, Object> out = (Map<String, Object>) redactor.redact(nested);

        @SuppressWarnings("unchecked")
        final Map<String, Object> config = (Map<String, Object>) out.get("config");
        assertThat(config.get("password")).isEqualTo(KeyPatternSpanRedactor.REDACTED);
        assertThat(config.get("host")).isEqualTo("db");

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertThat(items.get(0).get("secret")).isEqualTo(KeyPatternSpanRedactor.REDACTED);
        assertThat(items.get(1).get("name")).isEqualTo("ok");
    }

    @Test
    @DisplayName("non-map / non-list payloads pass through unchanged (key-based, not free-text)")
    void passesThroughScalars() {
        assertThat(redactor.redact("a token string with secret")).isEqualTo("a token string with secret");
        assertThat(redactor.redact(42)).isEqualTo(42);
        assertThat(redactor.redact(null)).isNull();
    }

    @Test
    @DisplayName("does not mutate the original payload")
    void doesNotMutateInput() {
        final Map<String, Object> input = new LinkedHashMap<>();
        input.put("token", "secret-value");
        redactor.redact(input);
        assertThat(input.get("token")).isEqualTo("secret-value");
    }

    @Test
    @DisplayName("noop redactor passes everything through")
    void noopPassesThrough() {
        final Map<String, Object> input = Map.of("token", "abc");
        assertThat(SpanRedactor.noop().redact(input)).isSameAs(input);
    }
}
