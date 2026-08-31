package at.aimon.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.tracing.SpanContext;
import at.aimon.spring.boot.metrics.AimonMetrics;

/**
 * Every {@code aimon.*} key a guide hands a reader must exist, and every default it states must be the one the field
 * carries.
 *
 * <p>
 * A reader's compiler checks the API names in a guide. Nothing checks the configuration. Both halves of that gap have
 * already produced a defect here: the budget prefix was documented as {@code aimon.execution-budget.*} when the
 * binder reads {@code aimon.budget.*} — a wrong prefix does not fail to start, it binds nothing and leaves the
 * defaults running under a reader who believes they set a limit — and the starter's budget defaults were documented
 * as unlimited when they are 20 / 100000 / 120s. Two of the ten documentation defects found in review were of this
 * one kind, which is what makes it worth a machine rather than another proofread.
 *
 * <h2>Where this looks, and why not everywhere</h2>
 *
 * <p>
 * {@code docs/**}, minus {@code docs/design/**} and {@code docs/backlog/**}. Those two trees are decision records,
 * and a record's job includes naming keys that are not real: the design carries the proposed property tree the
 * implementation deliberately departed from (§4.3 puts {@code wait-for-jobs-on-shutdown} at the top level, and §7
 * explains that the implementation moved it under {@code quartz} because at the top level it would be a knob that
 * does nothing on two of three backends), §9.1 lists properties nobody has built, and the backlog register quotes
 * {@code aimon.execution-budget.*} on purpose, as the defect it is. Checked against the live tree, 18 of the 18
 * unresolved keys in those trees are of exactly this kind. Guides are the opposite: a key there is an instruction.
 *
 * <p>
 * That split is the scope rule — by what the document is for, not by which file was convenient.
 *
 * <h2>What it checks</h2>
 *
 * <ul>
 * <li>every leaf of every YAML block rooted at {@code aimon:} resolves — this is the surface readers copy and paste;
 * <li>every inline {@code aimon.x.y} reference resolves, except the exemptions below;
 * <li>every stated default equals the value the field initialiser actually carries.
 * </ul>
 *
 * <p>
 * Defaults are read by reflection rather than from {@code spring-configuration-metadata.json}, whose
 * {@code defaultValue} entries {@link AimonConfigurationMetadataTest} documents as unreliable: the processor reads
 * them from field initialisers and drops them from entries an incremental build did not recompile. A test asserting
 * defaults from that file would pass on CI and fail on a developer's second run. The field is the truth here.
 *
 * <h2>Exemptions</h2>
 *
 * <p>
 * {@code aimon.*} is not one namespace. Three naming systems share the prefix in these guides: configuration
 * properties, Micrometer meters, and the CLI's own {@code aimon.yaml} file. The meter and tag families are derived
 * from the constants that emit them ({@link AimonMetrics#PREFIX}, {@link SpanContext#TAG_TRACE_ID}), so renaming a
 * meter moves its exemption with it. The rest are named one by one in {@link #EXEMPT_REFERENCES} with a reason.
 *
 * <p>
 * Exemptions are exact names, never prefixes, and {@code aimon.session.cache.hits} is why: that meter shares its
 * whole namespace with the real {@code aimon.session.cache.*} properties, so exempting the prefix would blind this
 * test to a typo in {@code idle-ttl} — the precise failure it exists to catch.
 *
 * <h2>What this cannot see</h2>
 *
 * <p>
 * A default stated in prose without naming its key. "The budget is unlimited" — the original defect's own shape —
 * names no property, so no machine ties it to a field. What is covered is the form the corrected text happens to
 * use, where the key or its leaf name is written next to the value. The key half of that defect, the wrong prefix,
 * is caught in full.
 *
 * <p>
 * Also unchecked: a leaf name whose candidates disagree. {@code max-tokens} is both {@code aimon.budget.max-tokens}
 * (100000) and {@code aimon.memory.max-tokens} (0), so a bare {@code max-tokens: N} is ambiguous and skipped rather
 * than guessed at. Leaf names are only resolved when every property carrying that name shares one default.
 */
@DisplayName("documented aimon.* keys and defaults match the starter")
class AimonDocumentedPropertiesTest {

    /** Documentation root, relative to the repository root. */
    private static final String DOCS = "docs";

    /**
     * Decision records rather than instructions — see the class javadoc. Paths are repository-relative prefixes.
     */
    private static final List<String> RECORD_TREES = List.of("docs/design/", "docs/backlog/");

    /**
     * Tokens that share the {@code aimon.} prefix without being configuration properties, each with the reason it
     * is not one. Exact names, never prefixes.
     */
    private static final Map<String, String> EXEMPT_REFERENCES = Map.ofEntries(
            Map.entry("aimon.queue.enqueued", "Micrometer meter, command queue guide"),
            Map.entry("aimon.queue.drained", "Micrometer meter, command queue guide"),
            Map.entry("aimon.queue.age_on_drain", "Micrometer meter, command queue guide"),
            // These two sit inside the aimon.session.cache.* property namespace. Named exactly, so a typo in a
            // sibling property is still caught.
            Map.entry("aimon.session.cache.hits", "Micrometer meter, session routing"),
            Map.entry("aimon.session.cache.misses", "Micrometer meter, session routing"),
            Map.entry("aimon.session.holder_loss_recovered", "Micrometer meter, session routing"),
            Map.entry("aimon.session.lock.acquire", "Micrometer meter, session lease store"),
            // A different product's configuration file. The CLI reads aimon.yaml; the starter binds aimon.* from
            // the Spring environment. Same prefix, unrelated trees.
            Map.entry("aimon.openai.key", "CLI aimon.yaml key, not a starter property"),
            Map.entry("aimon.openai.model", "CLI aimon.yaml key, not a starter property"),
            Map.entry("aimon.java-conventions", "Gradle script plugin id"),
            Map.entry("aimon.publishable", "Gradle script plugin id"),
            // The same two ids written as the files that define them. The reference pattern swallows the
            // extension, so `aimon.java-conventions.gradle.kts` is a different token from the bare id above and
            // needs its own entry — a doc that cites the file by name rather than the plugin by id hits this one.
            Map.entry("aimon.java-conventions.gradle.kts", "Gradle script plugin file"),
            Map.entry("aimon.publishable.gradle.kts", "Gradle script plugin file"),
            Map.entry("aimon.yaml", "filename"));

    /** Marks a line as claiming a default rather than showing an example value. */
    private static final Pattern DEFAULT_MARKER = Pattern.compile("기본값|기본|default|Default");

    /** An {@code aimon.}-prefixed dotted token, not preceded by a word character or dot (excludes {@code at.aimon}). */
    private static final Pattern REFERENCE = Pattern.compile("(?<![\\w.])aimon\\.[a-z0-9][a-z0-9._<>*-]*");

    /** A backticked {@code key: value} or {@code key=value} pair. */
    private static final Pattern INLINE_ASSIGNMENT = Pattern.compile("`([a-z][a-z0-9.-]*)\\s*[:=]\\s*([^`]+)`");

    /** Any backticked token. */
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");

    private static final Pattern YAML_ENTRY = Pattern.compile("^(\\s*)([A-Za-z0-9_.<>-]+):\\s*(.*)$");

    private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

    private static final PropertyTree TREE = PropertyTree.reflect();

    @Test
    @DisplayName("every key in a copy-pasteable YAML block exists")
    void everyDocumentedYamlKeyExists() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");

        final List<String> failures = new ArrayList<>();
        int checked = 0;
        for (final Path file : guideFiles()) {
            final List<String> lines = Files.readAllLines(file);
            for (final YamlLeaf leaf : yamlLeaves(lines)) {
                checked++;
                if (!TREE.isKnownKey(leaf.key)) {
                    failures.add(location(file, leaf.line) + "  " + leaf.key + TREE.suggestFor(leaf.key));
                }
            }
        }

        assertThat(checked).withFailMessage(
                "found no `aimon:` YAML blocks anywhere under %s — the scan is broken, not clean", DOCS).isPositive();
        assertThat(failures).withFailMessage(
                "these YAML keys do not exist in AimonProperties, so Spring binds nothing and the example silently "
                        + "does nothing:%n%s%nA reader copies these blocks verbatim.",
                String.join("\n", failures)).isEmpty();
    }

    @Test
    @DisplayName("every inline aimon.* reference exists")
    void everyDocumentedReferenceExists() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");

        final List<String> failures = new ArrayList<>();
        int checked = 0;
        for (final Path file : guideFiles()) {
            final List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                final Matcher matcher = REFERENCE.matcher(lines.get(i));
                while (matcher.find()) {
                    final String reference = trimTrailingPunctuation(matcher.group());
                    if (isExempt(reference)) {
                        continue;
                    }
                    checked++;
                    if (!TREE.isKnownKey(reference)) {
                        failures.add(location(file, i + 1) + "  " + reference + TREE.suggestFor(reference));
                    }
                }
            }
        }

        assertThat(checked)
                .withFailMessage("found no aimon.* references anywhere under %s — the scan is broken, not clean", DOCS)
                .isPositive();
        assertThat(failures).withFailMessage(
                "these documented keys do not exist in AimonProperties:%n%s%n"
                        + "A wrong key does not fail to start — it binds nothing, and the reader runs on defaults "
                        + "believing otherwise. If the token is not a configuration property (a meter, a plugin id, "
                        + "another product's file), add it to EXEMPT_REFERENCES with the reason.",
                String.join("\n", failures)).isEmpty();
    }

    @Test
    @DisplayName("every stated default matches the field")
    void everyStatedDefaultMatchesTheField() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");

        final List<String> failures = new ArrayList<>();
        int checked = 0;
        for (final Path file : guideFiles()) {
            final List<String> lines = Files.readAllLines(file);
            final boolean namesStarterProperties = lines.stream()
                    .anyMatch(line -> line.contains(AimonProperties.PREFIX + "."));
            for (int i = 0; i < lines.size(); i++) {
                for (final StatedDefault stated : statedDefaults(lines.get(i), namesStarterProperties)) {
                    checked++;
                    final Object actual = TREE.defaultOf(stated.key);
                    if (!matchesDefault(actual, stated.value)) {
                        failures.add(location(file, i + 1) + "  " + stated.key + " documented as `" + stated.value
                                + "`, field carries `" + render(actual) + "`");
                    }
                }
            }
        }

        assertThat(checked)
                .withFailMessage("found no stated defaults anywhere under %s — the scan is broken, not clean", DOCS)
                .isPositive();
        assertThat(failures).withFailMessage(
                "these documented defaults contradict the field initialiser:%n%s%n"
                        + "A reader who trusts the document configures nothing and gets a value they did not expect.",
                String.join("\n", failures)).isEmpty();
    }

    @Test
    @DisplayName("the property tree was actually walked")
    void thePropertyTreeWasWalked() {
        // Reflection failing softly would leave every other assertion here vacuously true: an empty tree knows no
        // keys, but it is only ever asked whether a key is absent when something already failed to resolve.
        assertThat(TREE.keys()).hasSizeGreaterThan(30);
        assertThat(TREE.keys()).contains("aimon.budget.max-iterations", "aimon.session.cache.idle-ttl",
                "aimon.llm.provider");
        assertThat(TREE.defaultOf("aimon.budget.max-iterations")).isEqualTo(20);
        // Booleans arrive through is*, not get*, and are the majority of the top-level keys.
        assertThat(TREE.keys()).contains("aimon.enabled", "aimon.fail-fast", "aimon.tools.bash.enabled");
        // The map node, whose second segment is any agent ref the host chooses.
        assertThat(TREE.isKnownKey("aimon.agents.ops.bundle")).isTrue();
        assertThat(TREE.isKnownKey("aimon.agents.ops.nonexistent")).isFalse();
        // The map-of-maps node, where both the profile and the field are the host's to name.
        assertThat(TREE.isKnownKey("aimon.credentials.jira.password")).isTrue();
        assertThat(TREE.isKnownKey("aimon.credentials.jira")).isTrue();
        assertThat(TREE.isKnownKey("aimon.credentials.jira.password.extra")).isFalse();
    }

    // ---------------------------------------------------------------------------------------------------------
    // scanning
    // ---------------------------------------------------------------------------------------------------------

    private static List<Path> guideFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(REPOSITORY_ROOT.resolve(DOCS))) {
            return walk.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".md"))
                    .filter(path -> !isRecordTree(path)).sorted().toList();
        }
    }

    private static boolean isRecordTree(Path path) {
        final String relative = REPOSITORY_ROOT.relativize(path).toString().replace('\\', '/');
        return RECORD_TREES.stream().anyMatch(relative::startsWith);
    }

    /**
     * Walks every YAML block rooted at a column-zero {@code aimon:} and returns its leaf paths.
     *
     * <p>
     * Indentation is the whole structure here. A leaf is an entry that carries a value; an entry with none opens a
     * level. The walk ends at the fence that closes the block or at the first column-zero line that is not part of
     * the tree.
     */
    private static List<YamlLeaf> yamlLeaves(List<String> lines) {
        final List<YamlLeaf> leaves = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!"aimon:".equals(lines.get(i).strip()) || !lines.get(i).startsWith("aimon:")) {
                continue;
            }
            final Deque<int[]> indents = new ArrayDeque<>();
            final Deque<String> names = new ArrayDeque<>();
            for (int j = i + 1; j < lines.size(); j++) {
                final String raw = lines.get(j);
                if (raw.isBlank() || raw.strip().startsWith("#")) {
                    continue;
                }
                if (raw.startsWith("```") || (!raw.startsWith(" ") && !raw.startsWith("\t"))) {
                    break;
                }
                final Matcher entry = YAML_ENTRY.matcher(raw);
                if (!entry.matches()) {
                    continue;
                }
                final int indent = entry.group(1).length();
                while (!indents.isEmpty() && indents.peek()[0] >= indent) {
                    indents.pop();
                    names.pop();
                }
                final String key = entry.group(2);
                final String value = stripComment(entry.group(3));
                final List<String> path = new ArrayList<>(names.size() + 2);
                path.add("aimon");
                final List<String> reversed = new ArrayList<>(names);
                for (int k = reversed.size() - 1; k >= 0; k--) {
                    path.add(reversed.get(k));
                }
                path.add(key);
                if (!value.isEmpty()) {
                    leaves.add(new YamlLeaf(String.join(".", path), j + 1));
                }
                indents.push(new int[]{indent});
                names.push(key);
            }
        }
        return leaves;
    }

    /**
     * Extracts default claims from one line.
     *
     * <p>
     * A line qualifies only if it is marked as stating a default. Without that marker every YAML example — which
     * legitimately shows values that are not defaults — would be read as a contradiction.
     */
    private static List<StatedDefault> statedDefaults(String line, boolean namesStarterProperties) {
        if (!DEFAULT_MARKER.matcher(line).find() && !isTableRow(line)) {
            return List.of();
        }
        final List<StatedDefault> stated = new ArrayList<>();

        // Table row: `| `aimon.x.y` | … | `value` |`, the default in the last populated cell.
        if (isTableRow(line)) {
            final String[] cells = line.split("\\|", -1);
            final String key = firstBacktickedToken(cells.length > 1 ? cells[1] : "");
            if (key != null && TREE.hasDefault(key)) {
                for (int c = cells.length - 1; c > 1; c--) {
                    final String candidate = firstBacktickedToken(cells[c]);
                    if (candidate != null) {
                        if (!isPlaceholder(candidate)) {
                            stated.add(new StatedDefault(key, candidate));
                        }
                        break;
                    }
                }
            }
            return stated;
        }

        // Inline `key: value`, where the key may be written in full or as its leaf name.
        final Matcher assignment = INLINE_ASSIGNMENT.matcher(line);
        while (assignment.find()) {
            final String written = assignment.group(1);
            final String value = assignment.group(2).strip();
            // A bare leaf name means a starter property only in a document that names starter properties at all.
            // `max-iterations` is also a field of skill frontmatter, and the skill migration guide — which mentions
            // no aimon.* key anywhere — must not have its rows read as claims about aimon.budget.
            if (!written.startsWith(AimonProperties.PREFIX + ".") && !namesStarterProperties) {
                continue;
            }
            final String resolved = TREE.resolveReference(written);
            if (resolved != null && !isPlaceholder(value)) {
                stated.add(new StatedDefault(resolved, value));
            }
        }
        if (!stated.isEmpty()) {
            return stated;
        }

        // Prose naming one property and one scalar: "`aimon.fail-fast` 의 기본값은 `false` 입니다".
        final List<String> tokens = new ArrayList<>();
        final Matcher backticked = BACKTICKED.matcher(line);
        while (backticked.find()) {
            tokens.add(backticked.group(1).strip());
        }
        final List<String> namedKeys = tokens.stream().filter(TREE::hasDefault).toList();
        if (namedKeys.size() == 1) {
            for (final String token : tokens) {
                if (!TREE.hasDefault(token) && isScalar(token)) {
                    stated.add(new StatedDefault(namedKeys.get(0), token));
                    break;
                }
            }
        }
        return stated;
    }

    private static boolean isTableRow(String line) {
        return line.startsWith("|") && line.contains("`aimon.");
    }

    private static String firstBacktickedToken(String cell) {
        final Matcher matcher = BACKTICKED.matcher(cell);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

    private static boolean isScalar(String token) {
        return token.matches("[A-Za-z0-9_.:/+-]+");
    }

    /** {@code <int>}, {@code —}, {@code …} — a shape to fill in, not a value to check. */
    private static boolean isPlaceholder(String value) {
        final String cleaned = value.strip();
        return cleaned.isEmpty() || cleaned.startsWith("<") || "—".equals(cleaned) || "…".equals(cleaned)
                || "-".equals(cleaned);
    }

    private static boolean isExempt(String reference) {
        if (EXEMPT_REFERENCES.containsKey(reference)) {
            return true;
        }
        if (reference.equals(AimonMetrics.PREFIX) || reference.startsWith(AimonMetrics.PREFIX + ".")) {
            return true;
        }
        return reference.equals(SpanContext.TAG_TRACE_ID) || reference.equals(SpanContext.TAG_PARENT_SPAN_ID);
    }

    private static String trimTrailingPunctuation(String reference) {
        String trimmed = reference;
        while (!trimmed.isEmpty() && (trimmed.endsWith(".") || trimmed.endsWith("-") || trimmed.endsWith("*"))) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String stripComment(String value) {
        final int hash = value.indexOf('#');
        return (hash < 0 ? value : value.substring(0, hash)).strip();
    }

    private static String location(Path file, int line) {
        return REPOSITORY_ROOT.relativize(file).toString().replace('\\', '/') + ":" + line;
    }

    // ---------------------------------------------------------------------------------------------------------
    // value comparison
    // ---------------------------------------------------------------------------------------------------------

    private static boolean matchesDefault(Object actual, String stated) {
        if (actual == null) {
            return true; // no default to contradict
        }
        final String cleaned = stated.replaceAll("^\\*+|\\*+$", "").strip();
        if (actual instanceof Duration duration) {
            final Duration parsed = parseDuration(cleaned);
            return parsed != null && parsed.equals(duration);
        }
        if (actual instanceof Enum<?> constant) {
            return cleaned.equalsIgnoreCase(constant.name())
                    || cleaned.equalsIgnoreCase(AimonProperties.asPropertyValue(constant));
        }
        return render(actual).equalsIgnoreCase(cleaned);
    }

    private static Duration parseDuration(String value) {
        final Matcher matcher = Pattern.compile("^(\\d+)(ms|s|m|h|d)$").matcher(value);
        if (matcher.matches()) {
            final long amount = Long.parseLong(matcher.group(1));
            return switch (matcher.group(2)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                default -> Duration.ofDays(amount);
            };
        }
        try {
            return Duration.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String render(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Enum<?> constant) {
            return AimonProperties.asPropertyValue(constant);
        }
        return String.valueOf(value);
    }

    private static Path locateRepositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))
                    && Files.isDirectory(candidate.resolve("modules"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }

    private static final class YamlLeaf {

        private final String key;

        private final int line;

        private YamlLeaf(String key, int line) {
            this.key = key;
            this.line = line;
        }
    }

    private static final class StatedDefault {

        private final String key;

        private final String value;

        private StatedDefault(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    /**
     * The binder's own view of {@link AimonProperties}: every bindable key, and the value its field carries when
     * nothing is configured.
     *
     * <p>
     * Built by walking getters rather than fields, because the getters are what Spring binds and what the property
     * name is derived from. A {@code Map} node contributes a wildcard segment — {@code aimon.agents.<name>} accepts
     * any name the host chooses — so the walk descends into the map's value type once and marks the segment
     * {@code *}.
     */
    private static final class PropertyTree {

        private static final String WILDCARD = "*";

        private final Map<String, Object> defaults = new LinkedHashMap<>();

        private final List<Pattern> keyPatterns = new ArrayList<>();

        private final List<String> keys = new ArrayList<>();

        static PropertyTree reflect() {
            final PropertyTree tree = new PropertyTree();
            tree.record(AimonProperties.PREFIX, null, false);
            tree.walk(new AimonProperties(), AimonProperties.PREFIX, 0);
            return tree;
        }

        private void walk(Object node, String prefix, int depth) {
            if (depth > 6) {
                return;
            }
            for (final Method method : node.getClass().getMethods()) {
                if (!isGetter(method)) {
                    continue;
                }
                final String name = propertyName(method);
                final String key = prefix + "." + name;
                final Object value = invoke(method, node);
                if (Map.class.isAssignableFrom(method.getReturnType())) {
                    record(key, null, false);
                    recordMapLevels(key, mapValueType(method.getGenericReturnType()), depth + 1);
                } else if (isNested(method.getReturnType())) {
                    record(key, null, false);
                    walk(value == null ? instantiate(method.getReturnType()) : value, key, depth + 1);
                } else {
                    record(key, value, true);
                }
            }
        }

        /**
         * Records the wildcard segment a map contributes, descending once per level the host names.
         *
         * <p>
         * A map's value can be another map: {@code aimon.credentials} is
         * {@code Map<String, Map<String, String>>}, two host-chosen segments rather than one. Stopping after the
         * first would leave {@code aimon.credentials.jira.password} unknown, and this test would then report a
         * real key as a typo — the exact inversion of what it exists for.
         */
        private void recordMapLevels(String prefix, java.lang.reflect.Type valueType, int depth) {
            final String key = prefix + "." + WILDCARD;
            record(key, null, false);
            final Class<?> raw = rawType(valueType);
            if (raw == null || depth > 6) {
                return;
            }
            if (Map.class.isAssignableFrom(raw)) {
                recordMapLevels(key, mapValueType(valueType), depth + 1);
            } else if (isNested(raw)) {
                walk(instantiate(raw), key, depth + 1);
            }
        }

        private void record(String key, Object value, boolean leaf) {
            if (!keys.contains(key)) {
                keys.add(key);
                keyPatterns.add(Pattern.compile(java.util.Arrays.stream(key.split("\\.", -1))
                        .map(segment -> WILDCARD.equals(segment) ? "[^.]+" : Pattern.quote(segment))
                        .reduce((a, b) -> a + "\\." + b).orElse("")));
            }
            if (leaf) {
                defaults.put(key, value);
            }
        }

        boolean isKnownKey(String key) {
            return keyPatterns.stream().anyMatch(pattern -> pattern.matcher(key).matches());
        }

        boolean hasDefault(String key) {
            return defaults.get(key) != null;
        }

        Object defaultOf(String key) {
            return defaults.get(key);
        }

        List<String> keys() {
            return keys;
        }

        /**
         * Resolves a reference written either in full or as a bare leaf name.
         *
         * <p>
         * A leaf name resolves only when it is unambiguous, or when every property carrying it agrees on one
         * default — {@code max-tokens} means 100000 under {@code budget} and 0 under {@code memory}, so it resolves
         * to neither.
         */
        String resolveReference(String reference) {
            if (hasDefault(reference)) {
                return reference;
            }
            final List<String> candidates = defaults.keySet().stream()
                    .filter(key -> key.endsWith("." + reference) && defaults.get(key) != null).toList();
            if (candidates.isEmpty()) {
                return null;
            }
            final Object first = defaults.get(candidates.get(0));
            return candidates.stream().allMatch(key -> first.equals(defaults.get(key))) ? candidates.get(0) : null;
        }

        /** Names the nearest real key, so a failure reads as a correction rather than a rejection. */
        String suggestFor(String key) {
            final String leaf = key.substring(key.lastIndexOf('.') + 1);
            final List<String> near = keys.stream().filter(candidate -> candidate.endsWith("." + leaf)).toList();
            return near.isEmpty() ? "" : "  (did you mean " + String.join(" or ", near) + "?)";
        }

        /**
         * Spring binds {@code isEnabled()} exactly as it binds {@code getRoot()}. Reading only {@code get*} would
         * drop every boolean the starter has — {@code aimon.enabled}, {@code aimon.fail-fast} and the rest — and
         * the resulting tree would then report those real keys as typos.
         */
        private static boolean isGetter(Method method) {
            if (method.getParameterCount() != 0 || method.getDeclaringClass() == Object.class
                    || method.getReturnType() == void.class) {
                return false;
            }
            final String name = method.getName();
            if (name.startsWith("get") && name.length() > 3) {
                return !"getClass".equals(name);
            }
            return name.startsWith("is") && name.length() > 2
                    && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class);
        }

        private static String propertyName(Method method) {
            final String raw = method.getName().startsWith("is")
                    ? method.getName().substring(2)
                    : method.getName().substring(3);
            final StringBuilder name = new StringBuilder();
            for (int i = 0; i < raw.length(); i++) {
                final char c = raw.charAt(i);
                if (Character.isUpperCase(c)) {
                    if (i > 0) {
                        name.append('-');
                    }
                    name.append(Character.toLowerCase(c));
                } else {
                    name.append(c);
                }
            }
            return name.toString().toLowerCase(Locale.ROOT);
        }

        private static boolean isNested(Class<?> type) {
            return type != null && type.getName().startsWith(AimonProperties.class.getName() + "$");
        }

        private static java.lang.reflect.Type mapValueType(java.lang.reflect.Type type) {
            if (type instanceof java.lang.reflect.ParameterizedType parameterized
                    && parameterized.getActualTypeArguments().length == 2) {
                return parameterized.getActualTypeArguments()[1];
            }
            return null;
        }

        private static Class<?> rawType(java.lang.reflect.Type type) {
            if (type instanceof Class<?> clazz) {
                return clazz;
            }
            if (type instanceof java.lang.reflect.ParameterizedType parameterized
                    && parameterized.getRawType() instanceof Class<?> raw) {
                return raw;
            }
            return null;
        }

        private static Object invoke(Method method, Object target) {
            try {
                return method.invoke(target);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not read " + method, e);
            }
        }

        private static Object instantiate(Class<?> type) {
            try {
                final java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
                constructor.setAccessible(true);
                return constructor.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not instantiate " + type, e);
            }
        }
    }
}
