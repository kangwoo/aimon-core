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
    @DisplayName("Literal placeholders")
    class LiteralPlaceholders {

        /** Fails the test if the decorator looks a variable up: an escaped placeholder names no variable. */
        private final Function<String, String> noLookup = name -> {
            throw new AssertionError("Looked up " + name);
        };

        private String valueOf(String written, Function<String, String> envVarResolver) throws IOException {
            // Single-quoted yaml: nothing in it is an escape, so the scalar is exactly `written`.
            try (PlaceholderExpandingParser parser = advanceToValueOf(parse("key: '" + written + "'\n", envVarResolver),
                    "key")) {
                return parser.getText();
            }
        }

        @Test
        @DisplayName("Should write a literal placeholder for $${NAME}, without reading the variable")
        void aDoubledDollarIsALiteralPlaceholder() throws IOException {
            assertThat(valueOf("$${NAME}", noLookup)).isEqualTo("${NAME}");
        }

        @Test
        @DisplayName("Should start whether or not the variable an escaped placeholder names is set")
        void anEscapedPlaceholderNeedsNoVariable() throws IOException {
            assertThat(valueOf("$${MISSING}", name -> null)).isEqualTo("${MISSING}");
        }

        @Test
        @DisplayName("Should keep the text around an escaped placeholder and expand its neighbours")
        void escapedAndExpandedPlaceholdersShareAValue() throws IOException {
            assertThat(valueOf("a-$${LEFT}-${MID}-$${RIGHT}-z", STUB)).isEqualTo("a-${LEFT}-stub-MID-${RIGHT}-z");
        }

        @Test
        @DisplayName("Should read $$${NAME} as a literal $ followed by the expansion")
        void threeDollarsAreADollarAndAnExpansion() throws IOException {
            // `$${NAME}` used to mean this -- a `$`, then the variable. The escape took that spelling, so the
            // meaning needs another one or a value like `$5` built from a variable could no longer be written.
            assertThat(valueOf("$$${NAME}", STUB)).isEqualTo("$stub-NAME");
        }

        @Test
        @DisplayName("Should read $$$${NAME} as a literal $ followed by a literal placeholder")
        void fourDollarsAreADollarAndALiteralPlaceholder() throws IOException {
            assertThat(valueOf("$$$${NAME}", noLookup)).isEqualTo("$${NAME}");
        }

        @Test
        @DisplayName("Should leave $$ alone when no placeholder follows it")
        void aDoubledDollarElsewhereIsUntouched() throws IOException {
            assertThat(valueOf("pa$$word", noLookup)).isEqualTo("pa$$word");
            assertThat(valueOf("$$", noLookup)).isEqualTo("$$");
            assertThat(valueOf("$${", noLookup)).isEqualTo("$${");
            assertThat(valueOf("$${}", noLookup)).isEqualTo("$${}");
            assertThat(valueOf("$$ ${NAME}", STUB)).isEqualTo("$$ stub-NAME");
        }

        @Test
        @DisplayName("Should not pass a token carrying only an escape through as untouched")
        void aTokenWithOnlyAnEscapeIsStillRewritten() throws IOException {
            try (PlaceholderExpandingParser parser = advanceToValueOf(parse("key: '$${NAME}'\n", noLookup), "key")) {
                assertThat(parser.hasTextCharacters()).isFalse();
                assertThat(parser.getTextCharacters()).containsExactly("${NAME}".toCharArray());
                assertThat(parser.getValueAsString()).isEqualTo("${NAME}");
            }
        }

        @Test
        @DisplayName("Should keep shell default syntax meant for a child process")
        void aShellDefaultSurvivesEscaped() throws IOException {
            // There is no `${NAME:default}` here: unescaped, the whole of `HOME:-/tmp` is the variable name.
            assertThat(valueOf("$${HOME:-/tmp}/cache", noLookup)).isEqualTo("${HOME:-/tmp}/cache");
            try (PlaceholderExpandingParser parser = parse("key: '${HOME:-/tmp}'\n", name -> null)) {
                assertThatThrownBy(() -> tokensOf(parser)).isInstanceOf(ConfigurationException.class)
                        .hasMessage("Environment variable not set: HOME:-/tmp (at key)");
            }
        }

        @Test
        @DisplayName("Should end an escaped placeholder at the first closing brace, like an expanded one")
        void anEscapedPlaceholderIsNotNested() throws IOException {
            // `${A${B}` is one placeholder to this class -- the name runs to the first `}` -- so the escape covers
            // exactly that much and the inner `${B}` is not expanded on its own.
            assertThat(valueOf("$${A${B}}", noLookup)).isEqualTo("${A${B}}");
        }

        @Test
        @DisplayName("Should not rescan what a variable expanded to")
        void anExpansionCarryingAnEscapeStaysAsItIs() throws IOException {
            assertThat(valueOf("${A}", name -> "$${B}")).isEqualTo("$${B}");
        }

        @Test
        @DisplayName("Should write a literal placeholder in a mapping key")
        void anEscapedKeyIsALiteralKey() throws IOException {
            try (PlaceholderExpandingParser parser = parse("outer:\n  $${NAME}: value\n", noLookup)) {
                parser.nextToken();
                parser.nextToken();
                parser.nextToken();
                assertThat(parser.nextToken()).isEqualTo(JsonToken.FIELD_NAME);
                assertThat(parser.getText()).isEqualTo("${NAME}");
                assertThat(parser.currentName()).isEqualTo("${NAME}");
            }
        }

        @Test
        @DisplayName("Should not report an escaped key as colliding with the same placeholder expanded")
        void anEscapedKeyDoesNotCollideWithItsExpansion() throws IOException {
            final List<String> names = new ArrayList<>();
            try (PlaceholderExpandingParser parser = parse("outer:\n  $${P}: one\n  ${P}: two\n", name -> "prod")) {
                JsonToken token = parser.nextToken();
                while (token != null) {
                    if (token == JsonToken.FIELD_NAME) {
                        names.add(parser.currentName());
                    }
                    token = parser.nextToken();
                }
            }

            assertThat(names).containsExactly("outer", "${P}", "prod");
        }

        @Test
        @DisplayName("Should refuse an escaped key beside a variable whose value is that literal text")
        void anEscapedKeyCollidesWithAKeyThatExpandsToTheSameText() throws IOException {
            try (PlaceholderExpandingParser parser = parse("outer:\n  $${P}: one\n  ${Q}: two\n", name -> "${P}")) {
                assertThatThrownBy(() -> tokensOf(parser)).isInstanceOf(ConfigurationException.class)
                        .hasMessage("Configuration keys `$${P}` and `${Q}` both expand to `${P}`, so one would"
                                + " silently replace the other. Keep one of them under `outer`.");
            }
        }
    }

    @Nested
    @DisplayName("Cost of a scan")
    class ScanCost {

        /**
         * How many characters of the input the scan looked at. This, not a clock, is what the tests below bound: the
         * pattern this scan replaced re-read the whole run of {@code $} from every position in it, which is a number
         * of reads (about n²/2), and a machine that is ten times slower reads exactly as many characters. A bound on
         * elapsed time would have had to guess at the machine; this one cannot be flaky.
         */
        private static final class CountingText implements CharSequence {
            private final String text;
            private long reads;

            CountingText(String text) {
                this.text = text;
            }

            @Override
            public int length() {
                return text.length();
            }

            @Override
            public char charAt(int index) {
                reads++;
                return text.charAt(index);
            }

            @Override
            public CharSequence subSequence(int start, int end) {
                reads += end - start;
                return text.subSequence(start, end);
            }

            @Override
            public String toString() {
                reads += text.length();
                return text;
            }
        }

        private static final int N = 100_000;

        /** A few reads per character: once to scan, once to copy, and slack. n²/2 is 50,000 reads per character. */
        private static final long READS_PER_CHARACTER = 8;

        private void assertLinear(String written, String expected) {
            final CountingText text = new CountingText(written);

            final String expanded = PlaceholderExpandingParser.expand(text, STUB);

            assertThat(expanded.length()).isEqualTo(expected.length());
            assertThat(expanded.equals(expected)).as("expands as before").isTrue();
            assertThat(text.reads).as("characters read for %d characters of input", written.length())
                    .isLessThanOrEqualTo(READS_PER_CHARACTER * written.length());
        }

        @Test
        @DisplayName("Should read a long run of $ that no placeholder follows a bounded number of times")
        void aRunOfDollarsBeforeSomethingElseIsLinear() {
            final String run = "$".repeat(N);
            assertLinear(run + " ${X}", run + " stub-X");
            assertLinear(run + "{", run + "{");
            assertLinear(run + "{}", run + "{}");
            assertLinear(run, run);
        }

        @Test
        @DisplayName("Should read a long run of $ in front of a placeholder a bounded number of times")
        void aRunOfDollarsBeforeAPlaceholderIsLinear() {
            // N + 1 dollars: N / 2 literal ones and the one that opens the placeholder.
            assertLinear("$".repeat(N + 1) + "{X}", "$".repeat(N / 2) + "stub-X");
            assertLinear("$".repeat(N) + "{X}", "$".repeat(N / 2 - 1) + "${X}");
        }

        @Test
        @DisplayName("Should read many unclosed placeholders a bounded number of times")
        void unclosedPlaceholdersAreLinear() {
            // The same shape one level in: every `${a` used to be followed to the end of the text looking for `}`.
            final String unclosed = "${a".repeat(N / 3);
            assertLinear(unclosed, unclosed);
            assertLinear("${}".repeat(N / 3), "${}".repeat(N / 3));
        }

        @Test
        @DisplayName("Should expand every short text exactly as the pattern it replaced did")
        void theScanAgreesWithThePatternItReplaced() {
            // The pattern the scan replaced, kept here as the definition of what it must still do. Every text of up
            // to eight characters over the five characters that matter to it: 488,280 of them, which covers every
            // way a run of `$`, a `{`, a `}` and a name can meet.
            final java.util.regex.Pattern replaced = java.util.regex.Pattern.compile("(\\$*)\\$\\{([^}]+)}");
            final char[] alphabet = {'$', '{', '}', 'a', ' '};
            final Function<String, String> value = name -> "<" + name + "$$>";
            final char[] text = new char[8];
            long compared = 0;
            for (int length = 0; length <= text.length; length++) {
                final int[] digits = new int[length];
                boolean more = true;
                while (more) {
                    for (int i = 0; i < length; i++) {
                        text[i] = alphabet[digits[i]];
                    }
                    final String written = new String(text, 0, length);
                    final java.util.regex.Matcher matcher = replaced.matcher(written);
                    final StringBuilder expected = new StringBuilder();
                    while (matcher.find()) {
                        final int dollars = matcher.group(1).length() + 1;
                        final String body = dollars % 2 == 0
                                ? "{" + matcher.group(2) + "}"
                                : value.apply(matcher.group(2));
                        matcher.appendReplacement(expected,
                                java.util.regex.Matcher.quoteReplacement("$".repeat(dollars / 2) + body));
                    }
                    matcher.appendTail(expected);

                    if (!PlaceholderExpandingParser.expand(written, value).contentEquals(expected)) {
                        fail("`%s` expands to `%s`, and the pattern gave `%s`", written,
                                PlaceholderExpandingParser.expand(written, value), expected);
                    }
                    compared++;
                    int position = length - 1;
                    while (position >= 0 && ++digits[position] == alphabet.length) {
                        digits[position--] = 0;
                    }
                    more = position >= 0;
                }
            }
            assertThat(compared).isEqualTo(488_281L);
        }

        @Test
        @DisplayName("Should read a value made of placeholders a bounded number of times")
        void manyPlaceholdersAreLinear() {
            assertLinear("${A}-".repeat(N / 5), "stub-A-".repeat(N / 5));
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

        @Test
        @DisplayName("Should say which key was written twice, and that the earlier value is gone")
        void aLiteralDuplicateIsReported() throws IOException {
            // CE-2. Still not a failure -- see above -- but no longer silent either.
            final List<String> warnings = warningsWhile(() -> {
                try (PlaceholderExpandingParser parser = parse(
                        "outer:\n  inner:\n    same: one\n    other: x\n    same: two\n    same: three\n", STUB)) {
                    tokensOf(parser);
                }
            });

            assertThat(warnings).containsExactly("Configuration key `outer.inner.same` is written more than once;"
                    + " the earlier value is discarded and the last one is used.");
        }

        @Test
        @DisplayName("Should report a duplicate at the top level and inside an array element by its own path")
        void aLiteralDuplicateIsReportedWhereItIs() throws IOException {
            final List<String> warnings = warningsWhile(() -> {
                try (PlaceholderExpandingParser parser = parse(
                        "top: 1\ntop: 2\nservers:\n  - name: a\n    name: b\n  - name: c\n", STUB)) {
                    tokensOf(parser);
                }
            });

            assertThat(warnings).containsExactly(
                    "Configuration key `top` is written more than once;"
                            + " the earlier value is discarded and the last one is used.",
                    "Configuration key `servers[].name` is written more than once;"
                            + " the earlier value is discarded and the last one is used.");
        }

        @Test
        @DisplayName("Should stay silent when no key is written twice")
        void aFileWithoutDuplicatesIsSilent() throws IOException {
            final List<String> warnings = warningsWhile(() -> {
                try (PlaceholderExpandingParser parser = parse(
                        "first:\n  name: a\nsecond:\n  name: b\n  ${A}: c\n  $${A}: d\n", STUB)) {
                    tokensOf(parser);
                }
            });

            assertThat(warnings).isEmpty();
        }

        @Test
        @DisplayName("Should keep refusing, not warn, when expansion is what made two keys collide")
        void anExpansionCollisionIsStillRefusedWithItsOwnMessage() throws IOException {
            final List<String> warnings = warningsWhile(() -> {
                try (PlaceholderExpandingParser parser = parse("outer:\n  prod: one\n  ${P}: two\n", name -> "prod")) {
                    assertThatThrownBy(() -> tokensOf(parser)).isInstanceOf(ConfigurationException.class)
                            .hasMessage("Configuration keys `prod` and `${P}` both expand to `prod`, so one would"
                                    + " silently replace the other. Keep one of them under `outer`.");
                }
            });

            assertThat(warnings).isEmpty();
        }

        @Test
        @DisplayName("Should keep refusing the same placeholder key written twice")
        void theSamePlaceholderKeyTwiceIsStillRefused() throws IOException {
            // Pinned because it is the shape nearest the warning: both spellings are identical, yet neither is the
            // name they expand to, so this was a refusal before CE-2 and stays one.
            try (PlaceholderExpandingParser parser = parse("outer:\n  ${P}: one\n  ${P}: two\n", name -> "prod")) {
                assertThatThrownBy(() -> tokensOf(parser)).isInstanceOf(ConfigurationException.class)
                        .hasMessageContaining("Configuration keys `${P}` and `${P}` both expand to `prod`");
            }
        }

        private interface IoAction {
            void run() throws IOException;
        }

        private List<String> warningsWhile(IoAction action) throws IOException {
            final ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                    .getLogger(PlaceholderExpandingParser.class);
            final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                action.run();
                return appender.list.stream().filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            } finally {
                logger.detachAppender(appender);
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
