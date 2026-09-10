package at.aimon.cli.config;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import at.aimon.cli.exception.ConfigurationException;

/**
 * Drives the decorator directly, without a config class in the way.
 *
 * <p>
 * {@link CliConfigLoaderTest} covers what a deployment observes; this covers what the decorator promises the
 * deserializers on the other side of it — the accessors agree with each other, an untouched token is passed through
 * byte-for-byte, and the level bookkeeping survives the two navigation methods {@code JsonParserDelegate} would
 * otherwise forward past this class.
 */
@DisplayName("PlaceholderExpandingParser Tests")
class PlaceholderExpandingParserTest {

    private static final Function<String, String> STUB = name -> "stub-" + name;

    private static PlaceholderExpandingParser parse(String yaml, Function<String, String> envVarResolver)
            throws IOException {
        return new PlaceholderExpandingParser(new YAMLFactory().createParser(yaml), envVarResolver);
    }

    /** Advances until the token stream reaches a value whose field name is {@code fieldName}. */
    private static <P extends JsonParser> P advanceToValueOf(P parser, String fieldName) throws IOException {
        JsonToken token = parser.nextToken();
        while (token != null) {
            if (token == JsonToken.FIELD_NAME && fieldName.equals(parser.getText())) {
                parser.nextToken();
                return parser;
            }
            token = parser.nextToken();
        }
        throw new IllegalStateException("Field not reached: " + fieldName);
    }

    private static List<JsonToken> tokensOf(JsonParser parser) throws IOException {
        final List<JsonToken> tokens = new ArrayList<>();
        JsonToken token = parser.nextToken();
        while (token != null) {
            tokens.add(token);
            token = parser.nextToken();
        }
        return tokens;
    }

    @Nested
    @DisplayName("Text accessors")
    class TextAccessors {

        @Test
        @DisplayName("Should agree with each other on a rewritten token")
        void everyAccessorAgreesOnARewrittenToken() throws IOException {
            try (PlaceholderExpandingParser parser = advanceToValueOf(parse("key: \"${NAME}\"\n", STUB), "key")) {
                final StringWriter written = new StringWriter();
                final int length = parser.getText(written);

                assertThat(parser.getText()).isEqualTo("stub-NAME");
                assertThat(parser.getValueAsString()).isEqualTo("stub-NAME");
                assertThat(parser.getValueAsString("fallback")).isEqualTo("stub-NAME");
                assertThat(parser.getTextCharacters()).containsExactly("stub-NAME".toCharArray());
                assertThat(parser.getTextLength()).isEqualTo("stub-NAME".length());
                assertThat(parser.getTextOffset()).isZero();
                assertThat(written.toString()).isEqualTo("stub-NAME");
                assertThat(length).isEqualTo("stub-NAME".length());
            }
        }

        @Test
        @DisplayName("Should report no text buffer for a rewritten token")
        void hasTextCharactersIsFalseForARewrittenToken() throws IOException {
            try (PlaceholderExpandingParser parser = advanceToValueOf(parse("key: \"${NAME}\"\n", STUB), "key")) {
                // So a caller that trusts this does not read the delegatee's buffer, which still holds `${NAME}`.
                assertThat(parser.hasTextCharacters()).isFalse();
            }
        }

        @Test
        @DisplayName("Should derive the numeric and boolean accessors from the expansion, not the raw text")
        void valueAccessorsSeeTheExpansion() throws IOException {
            // Nothing in today's config classes reaches these -- String, Integer, both enum shapes, List<String> and
            // both Map shapes all arrive through getText() or currentName(). They are derived anyway because the
            // residual is this project's own failure mode: a future int or boolean key would silently read `${N}`.
            try (PlaceholderExpandingParser parser = advanceToValueOf(parse("key: \"${N}\"\n", name -> "42"), "key")) {
                assertThat(parser.getValueAsInt()).isEqualTo(42);
                assertThat(parser.getValueAsInt(7)).isEqualTo(42);
                assertThat(parser.getValueAsLong()).isEqualTo(42L);
                assertThat(parser.getValueAsLong(7L)).isEqualTo(42L);
                assertThat(parser.getValueAsDouble()).isEqualTo(42.0);
                assertThat(parser.getValueAsDouble(7.0)).isEqualTo(42.0);
            }
            try (PlaceholderExpandingParser parser = advanceToValueOf(parse("key: \"${N}\"\n", name -> "true"),
                    "key")) {
                assertThat(parser.getValueAsBoolean()).isTrue();
                assertThat(parser.getValueAsBoolean(false)).isTrue();
            }
        }

        @Test
        @DisplayName("Should pass an untouched token through byte-for-byte")
        void anUntouchedTokenIsPassedThrough() throws IOException {
            // A scalar with no placeholder never reaches the rewrite path at all: same token type, same text, same
            // answer to hasTextCharacters(). That is what keeps `thinkingMode: off` meaning off.
            for (String written : new String[]{"0755", "off", "1.10", "plain"}) {
                final String yaml = "key: " + written + "\n";
                try (JsonParser raw = new YAMLFactory().createParser(yaml);
                        PlaceholderExpandingParser parser = advanceToValueOf(parse(yaml, STUB), "key")) {
                    advanceToValueOf(raw, "key");

                    assertThat(parser.currentToken()).as("token type of `%s`", written).isEqualTo(raw.currentToken());
                    assertThat(parser.getText()).as("text of `%s`", written).isEqualTo(raw.getText());
                    assertThat(parser.hasTextCharacters()).as("text buffer of `%s`", written)
                            .isEqualTo(raw.hasTextCharacters());
                }
            }
        }

        @Test
        @DisplayName("Should expand a field name and report it as the current name")
        void aFieldNameIsExpanded() throws IOException {
            try (PlaceholderExpandingParser parser = parse("${KEY}: value\n", STUB)) {
                assertThat(parser.nextToken()).isEqualTo(JsonToken.START_OBJECT);
                assertThat(parser.nextToken()).isEqualTo(JsonToken.FIELD_NAME);
                assertThat(parser.getText()).isEqualTo("stub-KEY");
                assertThat(parser.currentName()).isEqualTo("stub-KEY");

                assertThat(parser.nextToken()).isEqualTo(JsonToken.VALUE_STRING);
                // Still the field's name while the value token is current, which is what bean binding reads.
                assertThat(parser.currentName()).isEqualTo("stub-KEY");
                assertThat(parser.getText()).isEqualTo("value");
            }
        }
    }

    @Nested
    @DisplayName("Expansion rules")
    class ExpansionRules {

        @Test
        @DisplayName("Should leave a placeholder with an empty name alone")
        void anEmptyPlaceholderIsLiteral() throws IOException {
            try (PlaceholderExpandingParser parser = advanceToValueOf(parse("key: \"${}\"\n", STUB), "key")) {
                assertThat(parser.getText()).isEqualTo("${}");
            }
        }

        @Test
        @DisplayName("Should expand once, not repeatedly")
        void expansionIsASinglePass() throws IOException {
            try (PlaceholderExpandingParser parser = advanceToValueOf(parse("key: \"${A}\"\n", name -> "${B}"),
                    "key")) {
                assertThat(parser.getText()).isEqualTo("${B}");
            }
        }

        @Test
        @DisplayName("Should keep a $ or a backslash in the expansion")
        void anExpansionCarryingRegexMetacharactersSurvives() throws IOException {
            try (PlaceholderExpandingParser parser = advanceToValueOf(parse("key: \"${A}\"\n", name -> "a$b\\c"),
                    "key")) {
                assertThat(parser.getText()).isEqualTo("a$b\\c");
            }
        }

        @Test
        @DisplayName("Should name the variable and the key path when the variable is not set")
        void anUnsetVariableNamesTheVariableAndTheKey() throws IOException {
            try (PlaceholderExpandingParser parser = parse("""
                    outer:
                      inner:
                        key: "${MISSING}"
                    """, name -> null)) {
                assertThatThrownBy(() -> tokensOf(parser)).isInstanceOf(ConfigurationException.class)
                        .hasMessage("Environment variable not set: MISSING (at outer.inner.key)");
            }
        }

        @Test
        @DisplayName("Should name the key itself when the variable on the key is not set")
        void anUnsetVariableOnAKeyNamesTheWrittenKey() throws IOException {
            try (PlaceholderExpandingParser parser = parse("outer:\n  ${MISSING}: value\n", name -> null)) {
                assertThatThrownBy(() -> tokensOf(parser)).isInstanceOf(ConfigurationException.class)
                        .hasMessage("Environment variable not set: MISSING (at outer.${MISSING})");
            }
        }
    }

    @Nested
    @DisplayName("Sibling key collisions")
    class SiblingKeyCollisions {

        @Test
        @DisplayName("Should refuse two keys that expand to one name, at any depth")
        void aCollisionIsRefusedAtAnyDepth() throws IOException {
            try (PlaceholderExpandingParser parser = parse("""
                    outer:
                      inner:
                        ${A}: one
                        ${B}: two
                    """, name -> "same")) {
                assertThatThrownBy(() -> tokensOf(parser)).isInstanceOf(ConfigurationException.class)
                        .hasMessage("Configuration keys `${A}` and `${B}` both expand to `same`, so one would"
                                + " silently replace the other. Keep one of them under `outer.inner`.");
            }
        }

        @Test
        @DisplayName("Should point at the top level when the colliding keys are the outermost ones")
        void aCollisionAtTheRootSaysSo() throws IOException {
            try (PlaceholderExpandingParser parser = parse("${A}: one\n${B}: two\n", name -> "same")) {
                assertThatThrownBy(() -> tokensOf(parser)).isInstanceOf(ConfigurationException.class)
                        .hasMessageContaining("Keep one of them at the top level.");
            }
        }

        @Test
        @DisplayName("Should not confuse keys of the same name in different objects")
        void thesameNameInTwoObjectsIsNotACollision() throws IOException {
            try (PlaceholderExpandingParser parser = parse("""
                    first:
                      ${A}: one
                    second:
                      ${A}: two
                    """, name -> "same")) {
                assertThat(tokensOf(parser)).containsExactly(JsonToken.START_OBJECT, JsonToken.FIELD_NAME,
                        JsonToken.START_OBJECT, JsonToken.FIELD_NAME, JsonToken.VALUE_STRING, JsonToken.END_OBJECT,
                        JsonToken.FIELD_NAME, JsonToken.START_OBJECT, JsonToken.FIELD_NAME, JsonToken.VALUE_STRING,
                        JsonToken.END_OBJECT, JsonToken.END_OBJECT);
            }
        }

        @Test
        @DisplayName("Should leave a key written twice to yaml's own last-wins")
        void twoLiteralDuplicatesAreNotACollision() throws IOException {
            // Recording every field name is what preserves the refusal of a literal key beside a placeholder that
            // expands onto it. It also makes a plain duplicate visible here for the first time -- a different
            // defect, in a different layer, that expansion did not create and this class does not start failing on.
            try (PlaceholderExpandingParser parser = parse("outer:\n  same: one\n  same: two\n", STUB)) {
                assertThat(tokensOf(parser)).hasSize(9);
            }
        }
    }

    @Nested
    @DisplayName("Stream navigation")
    class StreamNavigation {

        private static final String NESTED = """
                first:
                  ${A}: one
                  child:
                    deep: two
                second: "${B}"
                """;

        @Test
        @DisplayName("Should produce the same token sequence as the parser it wraps")
        void theTokenSequenceIsUnchanged() throws IOException {
            final List<JsonToken> raw = tokensOf(new YAMLFactory().createParser(NESTED));

            try (PlaceholderExpandingParser parser = parse(NESTED, STUB)) {
                assertThat(tokensOf(parser)).isEqualTo(raw);
            }
        }

        @Test
        @DisplayName("Should keep the level bookkeeping consistent across skipChildren")
        void skipChildrenLandsOnTheRightSibling() throws IOException {
            // JsonParserDelegate forwards skipChildren straight to the delegatee, which would advance the raw
            // parser past this class and leave the level stack describing an object that already closed. Nothing
            // in today's config tree reaches it -- FAIL_ON_UNKNOWN_PROPERTIES is on and no config class carries
            // @JsonIgnoreProperties -- so this is where the re-implementation is exercised.
            try (PlaceholderExpandingParser parser = parse(NESTED, STUB)) {
                parser.nextToken();
                parser.nextToken();
                assertThat(parser.getText()).isEqualTo("first");
                parser.nextToken();
                parser.skipChildren();

                assertThat(parser.currentToken()).isEqualTo(JsonToken.END_OBJECT);
                assertThat(parser.nextToken()).isEqualTo(JsonToken.FIELD_NAME);
                assertThat(parser.getText()).isEqualTo("second");
                assertThat(parser.nextToken()).isEqualTo(JsonToken.VALUE_STRING);
                assertThat(parser.getText()).isEqualTo("stub-B");
            }
        }

        @Test
        @DisplayName("Should walk values, and their expansions, through nextValue")
        void nextValueWalksTheStream() throws IOException {
            try (PlaceholderExpandingParser parser = parse("first: \"${A}\"\nsecond: plain\n", STUB)) {
                assertThat(parser.nextValue()).isEqualTo(JsonToken.START_OBJECT);
                assertThat(parser.nextValue()).isEqualTo(JsonToken.VALUE_STRING);
                assertThat(parser.getText()).isEqualTo("stub-A");
                assertThat(parser.nextValue()).isEqualTo(JsonToken.VALUE_STRING);
                assertThat(parser.getText()).isEqualTo("plain");
                assertThat(parser.nextValue()).isEqualTo(JsonToken.END_OBJECT);
            }
        }

        @Test
        @DisplayName("Should return null at end of input rather than failing")
        void endOfInputIsNull() throws IOException {
            // An empty or comment-only file returns null on the very first call. Dereferencing it here would throw
            // a bare NPE past every catch clause in the loader, and the empty-file report would stop being the
            // "Invalid configuration structure" it has always been.
            try (PlaceholderExpandingParser parser = parse("# nothing here\n", STUB)) {
                assertThat(parser.nextToken()).isNull();
                assertThat(parser.nextToken()).isNull();
            }
        }
    }
}
