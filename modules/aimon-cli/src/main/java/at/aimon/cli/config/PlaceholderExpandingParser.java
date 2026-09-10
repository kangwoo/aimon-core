package at.aimon.cli.config;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.Base64Variant;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.io.NumberInput;
import com.fasterxml.jackson.core.util.JsonParserDelegate;

import at.aimon.cli.exception.ConfigurationException;

/**
 * Expands {@code ${NAME}} in the CLI configuration file, on the token stream, before Jackson binds anything.
 *
 * <p>
 * The rule this class implements is the whole of it, and it is one sentence: <b>every scalar value and every mapping
 * key in the configuration file is expanded — {@code ${NAME}} is replaced by the environment variable {@code NAME},
 * and a variable that is not set fails startup naming the variable and the key it was written on.</b> There is no list
 * of participating fields. The list is what produced issue #53: it held three {@code llm} fields plus the capability
 * map keys, the {@code memory} block gained a credential of its own, and nothing connected the two edits — so the
 * CLI's own shipped {@code default-config.yaml} demonstrated {@code apiKey: "${OPENAI_KEY}"} in a block the loader
 * never visited, and a deployment that followed the example handed the literal seven characters to its embedding
 * provider.
 *
 * <p>
 * <b>Why a parser decorator and not a walk over the bound object.</b> Expansion used to run after binding, which is
 * why it could only ever reach {@code String}-typed fields: {@code llm.timeout} is an {@code Integer} and
 * {@code llm.anthropic.thinkingMode} an enum, so a {@code ${VAR}} written on either failed at bind time. A post-bind
 * walk would leave the documented rule as "every key whose Java type happens to be {@code String}", which is a
 * smaller version of the same defect — a rule the reader cannot evaluate without opening the config classes.
 *
 * <p>
 * <b>Why the token stream and not a {@link com.fasterxml.jackson.databind.JsonNode} tree.</b> Re-serialising a bound
 * tree loses the scalar the YAML parser actually read, and one field depends on it by design:
 * {@link AnthropicProviderConfig.ThinkingModeDeserializer} reads {@code parser.getText()} precisely because
 * {@code off} is a YAML 1.1 boolean that arrives as {@code VALUE_FALSE}, and only the written text distinguishes it
 * from {@code no} and {@code false}, which the same enum must keep refusing. A tree round trip collapses all three to
 * {@code false} — for every file, including files with no placeholder in them. This decorator rewrites the text of
 * {@code VALUE_STRING} and {@code FIELD_NAME} tokens and passes every other token through untouched: same token type,
 * same text, same numeric decoding. A {@code ${…}} can never resolve to a YAML boolean, number or null, so a
 * placeholder always arrives as {@code VALUE_STRING} and nothing is missed; a scalar carrying no placeholder is never
 * touched at all.
 *
 * <p>
 * Expansion is a single pass: a variable whose value is itself {@code ${OTHER}} stays literal, as it always has.
 * There is no escape syntax for a literal placeholder; nothing in the tree needs one.
 *
 * <p>
 * <b>Two sibling keys that end up with the same name are refused</b> rather than letting the later one silently
 * replace the earlier. That guard used to live in the loader and cover the capability map alone (#46); here it is a
 * property of every mapping. It fires only when expansion is what made the two collide — two keys written the same
 * way twice are yaml's own last-wins and are left alone, because expansion did not create that collision and this
 * class is not the place to start failing on it.
 */
final class PlaceholderExpandingParser extends JsonParserDelegate {
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final Function<String, String> envVarResolver;

    /** One entry per open object or array, outermost first — the source of both the key path and the sibling check. */
    private final List<Level> levels = new ArrayList<>();

    /**
     * Written spelling to expanded spelling, for every field name this stream rewrote. Read by {@link #currentName()}.
     */
    private final Map<String, String> expandedNames = new HashMap<>();

    /** The expansion of the current token, or null when the current token was passed through untouched. */
    private String rewritten;

    PlaceholderExpandingParser(JsonParser delegate, Function<String, String> envVarResolver) {
        super(Objects.requireNonNull(delegate, "Delegate parser cannot be null"));
        this.envVarResolver = Objects.requireNonNull(envVarResolver, "Environment variable resolver cannot be null");
    }

    /**
     * Finds the placeholder failure inside a Jackson wrapper, if that is what went wrong.
     *
     * <p>
     * Jackson folds a {@link RuntimeException} thrown from inside a property's deserialization into a
     * {@code JsonMappingException}, so without this the loader would report its generic
     * "Invalid configuration structure" and lose the variable name.
     */
    static Optional<ConfigurationException> placeholderFailureIn(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof ConfigurationException configurationFailure) {
                return Optional.of(configurationFailure);
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return Optional.empty();
    }

    @Override
    public JsonToken nextToken() throws IOException {
        final JsonToken token = delegate.nextToken();
        rewritten = null;
        if (token == null) {
            // End of input. Every caller below dereferences the token, and an empty or comment-only file reaches
            // here on the very first call.
            return null;
        }
        switch (token) {
            case START_OBJECT -> levels.add(new Level(false));
            case START_ARRAY -> levels.add(new Level(true));
            case END_OBJECT, END_ARRAY -> popLevel();
            case FIELD_NAME -> rewriteFieldName(delegate.getText());
            case VALUE_STRING -> rewriteValue(delegate.getText());
            default -> {
                // Every other token passes through untouched, which is what keeps `thinkingMode: off` meaning off.
            }
        }
        return token;
    }

    /**
     * Re-implemented on top of this class's {@link #nextToken()}.
     *
     * <p>
     * {@link JsonParserDelegate} forwards it straight to the delegatee, which would advance the raw parser past this
     * class and desynchronise the level bookkeeping. Insurance rather than a fix: no config class reaches it today.
     */
    @Override
    public JsonToken nextValue() throws IOException {
        JsonToken token = nextToken();
        if (token == JsonToken.FIELD_NAME) {
            token = nextToken();
        }
        return token;
    }

    /** Re-implemented on top of this class's {@link #nextToken()}, for the reason {@link #nextValue()} gives. */
    @Override
    public JsonParser skipChildren() throws IOException {
        final JsonToken start = delegate.currentToken();
        if (start != JsonToken.START_OBJECT && start != JsonToken.START_ARRAY) {
            return this;
        }
        int depth = 1;
        while (depth > 0) {
            final JsonToken token = nextToken();
            if (token == null) {
                return this;
            }
            if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                depth++;
            } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                depth--;
            }
        }
        return this;
    }

    /**
     * The only name accessor this class overrides, deliberately.
     *
     * <p>
     * The deprecated {@code getCurrentName()} — which {@link JsonParserDelegate} does forward — and
     * {@code getParsingContext().getCurrentName()} keep returning the <b>written</b> spelling. Measured on databind
     * 2.22.2: neither is read on the deserialize path, so the only visible effect is that a Jackson reference chain
     * in an error message can name {@code ${VAR}} rather than what it expanded to. Left alone rather than
     * overridden, because a deprecated method is not a surface to grow; {@code resolvesEnvironmentVariablesInTheKey}
     * is the pin that goes red if a Jackson upgrade ever routes map-key binding through one of them.
     */
    @Override
    public String currentName() throws IOException {
        final String written = delegate.currentName();
        final String expanded = expandedNames.get(written);
        return expanded != null ? expanded : written;
    }

    @Override
    public String getText() throws IOException {
        return rewritten != null ? rewritten : delegate.getText();
    }

    @Override
    public int getText(Writer writer) throws IOException {
        if (rewritten == null) {
            return delegate.getText(writer);
        }
        writer.write(rewritten);
        return rewritten.length();
    }

    @Override
    public char[] getTextCharacters() throws IOException {
        return rewritten != null ? rewritten.toCharArray() : delegate.getTextCharacters();
    }

    @Override
    public int getTextLength() throws IOException {
        return rewritten != null ? rewritten.length() : delegate.getTextLength();
    }

    @Override
    public int getTextOffset() throws IOException {
        return rewritten != null ? 0 : delegate.getTextOffset();
    }

    @Override
    public boolean hasTextCharacters() {
        // False for a rewritten token, so a caller that trusts this does not read the delegatee's buffer.
        return rewritten == null && delegate.hasTextCharacters();
    }

    @Override
    public String getValueAsString() throws IOException {
        return rewritten != null ? rewritten : delegate.getValueAsString();
    }

    @Override
    public String getValueAsString(String defaultValue) throws IOException {
        return rewritten != null ? rewritten : delegate.getValueAsString(defaultValue);
    }

    @Override
    public int getValueAsInt() throws IOException {
        return rewritten != null ? NumberInput.parseAsInt(rewritten, 0) : delegate.getValueAsInt();
    }

    @Override
    public int getValueAsInt(int defaultValue) throws IOException {
        return rewritten != null
                ? NumberInput.parseAsInt(rewritten, defaultValue)
                : delegate.getValueAsInt(defaultValue);
    }

    @Override
    public long getValueAsLong() throws IOException {
        return rewritten != null ? NumberInput.parseAsLong(rewritten, 0L) : delegate.getValueAsLong();
    }

    @Override
    public long getValueAsLong(long defaultValue) throws IOException {
        return rewritten != null
                ? NumberInput.parseAsLong(rewritten, defaultValue)
                : delegate.getValueAsLong(defaultValue);
    }

    @Override
    public double getValueAsDouble() throws IOException {
        return rewritten != null ? NumberInput.parseAsDouble(rewritten, 0.0) : delegate.getValueAsDouble();
    }

    @Override
    public double getValueAsDouble(double defaultValue) throws IOException {
        return rewritten != null
                ? NumberInput.parseAsDouble(rewritten, defaultValue)
                : delegate.getValueAsDouble(defaultValue);
    }

    @Override
    public boolean getValueAsBoolean() throws IOException {
        return rewritten != null ? parseAsBoolean(false) : delegate.getValueAsBoolean();
    }

    @Override
    public boolean getValueAsBoolean(boolean defaultValue) throws IOException {
        return rewritten != null ? parseAsBoolean(defaultValue) : delegate.getValueAsBoolean(defaultValue);
    }

    @Override
    public byte[] getBinaryValue(Base64Variant variant) throws IOException {
        return rewritten != null ? variant.decode(rewritten) : delegate.getBinaryValue(variant);
    }

    private boolean parseAsBoolean(boolean defaultValue) {
        final String text = rewritten.trim();
        if ("true".equals(text)) {
            return true;
        }
        if ("false".equals(text)) {
            return false;
        }
        return defaultValue;
    }

    private void rewriteFieldName(String written) {
        final Level level = levels.isEmpty() ? null : levels.get(levels.size() - 1);
        if (level != null) {
            // Set before expanding so a variable that fails on the key itself reports `llm.${MISSING}`.
            level.fieldName = written;
        }
        final String expanded = expand(written);
        if (!expanded.equals(written)) {
            expandedNames.put(written, expanded);
            rewritten = expanded;
        }
        if (level != null) {
            level.fieldName = expanded;
            level.recordSibling(written, expanded, enclosingPath());
        }
    }

    private void rewriteValue(String written) {
        final String expanded = expand(written);
        if (!expanded.equals(written)) {
            rewritten = expanded;
        }
    }

    private String expand(String written) {
        if (written == null || !written.contains("${")) {
            return written;
        }
        final Matcher matcher = ENV_VAR_PATTERN.matcher(written);
        final StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            final String name = matcher.group(1);
            final String value = envVarResolver.apply(name);
            if (value == null) {
                throw new ConfigurationException(
                        "Environment variable not set: " + name + " (at " + currentPath() + ")");
            }
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    private void popLevel() {
        if (!levels.isEmpty()) {
            levels.remove(levels.size() - 1);
        }
    }

    /** The dotted path of the key being read, e.g. {@code memory.dreamer.scorer.embedding.apiKey}. */
    private String currentPath() {
        return buildPath(levels.size());
    }

    /** The path of the object holding the current key — what a collision message points the operator at. */
    private String enclosingPath() {
        return buildPath(levels.size() - 1);
    }

    private String buildPath(int depth) {
        final StringBuilder path = new StringBuilder();
        for (int index = 0; index < depth && index < levels.size(); index++) {
            final Level level = levels.get(index);
            if (level.array) {
                // Array segments carry no index: the message already names the key.
                path.append("[]");
            } else if (level.fieldName != null) {
                if (path.length() > 0) {
                    path.append('.');
                }
                path.append(level.fieldName);
            }
        }
        return path.toString();
    }

    /** One open object or array. */
    private static final class Level {
        private final boolean array;
        private String fieldName;
        private Map<String, String> writtenByExpanded;

        Level(boolean array) {
            this.array = array;
        }

        void recordSibling(String written, String expanded, String enclosingPath) {
            if (writtenByExpanded == null) {
                writtenByExpanded = new LinkedHashMap<>();
            }
            final String previous = writtenByExpanded.putIfAbsent(expanded, written);
            if (previous == null || (previous.equals(expanded) && written.equals(expanded))) {
                // Nothing seen before, or the same key written twice — a duplicate expansion did not create.
                return;
            }
            throw new ConfigurationException("Configuration keys `" + previous + "` and `" + written
                    + "` both expand to `" + expanded + "`, so one would silently replace the other. Keep one of them "
                    + (enclosingPath.isEmpty() ? "at the top level." : "under `" + enclosingPath + "`."));
        }
    }
}
