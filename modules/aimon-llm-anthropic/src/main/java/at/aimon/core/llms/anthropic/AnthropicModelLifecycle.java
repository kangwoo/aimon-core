package at.aimon.core.llms.anthropic;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Says, for a model name about to go on the wire, whether the vendor has deprecated or retired it.
 *
 * <p>
 * A warning, never a refusal: the server decides what it serves, and this table only gives an operator advance notice
 * of a date — or an explanation of a 404 — that nothing else in the request path would. It is deliberately not a
 * field of {@link at.aimon.core.llm.capability.ModelCapabilities}: lifecycle is not a request-surface fact, and that
 * type is provider-neutral and read by both clients.
 *
 * <p>
 * Prefixes, lower-cased, so a dated snapshot ({@code claude-sonnet-4-5-20250929}) matches its alias. Each prefix was
 * checked not to reach an active model: {@code claude-sonnet-4-2025} / {@code claude-opus-4-2025} match only the
 * retired {@code -20250514} snapshots and not {@code claude-opus-4-5}, and {@code claude-3-} is safe because the whole
 * 3.x line is retired. {@code claude-haiku-4-5} and {@code claude-opus-4-5} are active in the vendor catalog and are
 * not listed.
 *
 * <p>
 * Stateless and immutable.
 */
final class AnthropicModelLifecycle {

    /** Deprecated, still served until the stated date. */
    private static final List<Entry> DEPRECATED = List
            .of(new Entry("claude-sonnet-4-5", "is deprecated and retires on 2026-11-30, after which requests to it "
                    + "fail with 404 not_found_error; move to claude-sonnet-5-5, the current default"));

    /** Retired: the vendor answers 404. */
    private static final List<String> RETIRED = List.of("claude-sonnet-4-2025", "claude-opus-4-2025",
            "claude-sonnet-4-0", "claude-opus-4-0", "claude-opus-4-1", "claude-3-", "claude-2.");

    private AnthropicModelLifecycle() {
    }

    /**
     * The warning to log for a model name, if the vendor has deprecated or retired it.
     *
     * @param modelName
     *            the name about to go on the wire; {@code null} or empty yields nothing
     * @return the full sentence, or empty for a name this table does not list
     */
    static Optional<String> warningFor(String modelName) {
        if (modelName == null || modelName.isEmpty()) {
            return Optional.empty();
        }
        final String lower = modelName.toLowerCase(Locale.ROOT);
        for (Entry entry : DEPRECATED) {
            if (lower.startsWith(entry.prefix)) {
                return Optional.of("Anthropic model " + modelName + " " + entry.notice + ".");
            }
        }
        for (String prefix : RETIRED) {
            if (lower.startsWith(prefix)) {
                return Optional.of("Anthropic model " + modelName + " is retired; requests to it fail with 404 "
                        + "not_found_error. Configure a current model such as claude-sonnet-5-5.");
            }
        }
        return Optional.empty();
    }

    private static final class Entry {

        private final String prefix;
        private final String notice;

        private Entry(String prefix, String notice) {
            this.prefix = prefix;
            this.notice = notice;
        }
    }
}
