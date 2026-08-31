package at.aimon.core.tracing.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import at.aimon.core.tracing.SpanRedactor;

/**
 * Default {@link SpanRedactor}: masks map entries whose key looks sensitive, recursing into nested maps and lists.
 *
 * <p>
 * A key is sensitive when its lower-cased form, with {@code -}, {@code _}, {@code .} and spaces removed, contains any
 * of {@code token}, {@code secret}, {@code password}, {@code credential}, {@code apikey}, {@code authorization}.
 * Matching values are replaced with {@link #REDACTED}. Non-map / non-list payloads (e.g. a free-text content string)
 * are returned unchanged — this redactor is <b>key-based</b> and does not scan free text. The original payload is
 * never mutated; a masked copy is returned only when something was actually masked.
 *
 * <p>
 * The separators are dropped rather than enumerated. The list used to carry {@code apikey} and {@code api_key} as
 * two entries, which is what a hand-maintained list of separator spellings looks like just before it misses one — and
 * it missed {@code api-key}, the header Azure OpenAI authenticates with and the spelling this project's own property
 * (<code>aimon.llm.api-key</code>) uses. Normalising the key instead closes {@code x-api-key} and {@code Api Key} with
 * it, and cannot lose a match the old form had, because no fragment contains a separator.
 *
 * <p>
 * Stateless and thread-safe; exposed as a shared {@link #INSTANCE} via {@link SpanRedactor#defaultRedactor()}.
 */
public final class KeyPatternSpanRedactor implements SpanRedactor {

    /** Shared stateless instance. */
    public static final KeyPatternSpanRedactor INSTANCE = new KeyPatternSpanRedactor();

    /** Replacement marker for masked values. */
    public static final String REDACTED = "***REDACTED***";

    private static final List<String> SENSITIVE_FRAGMENTS = List.of("token", "secret", "password", "credential",
            "apikey", "authorization");

    private static final String SEPARATORS = "-_. ";

    private KeyPatternSpanRedactor() {
    }

    @Override
    public Object redact(Object payload) {
        if (payload instanceof Map<?, ?> map) {
            return redactMap(map);
        }
        if (payload instanceof List<?> list) {
            return redactList(list);
        }
        return payload;
    }

    private Map<Object, Object> redactMap(Map<?, ?> map) {
        final Map<Object, Object> copy = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            final Object key = entry.getKey();
            if (isSensitiveKey(key)) {
                copy.put(key, REDACTED);
            } else {
                copy.put(key, redact(entry.getValue()));
            }
        }
        return copy;
    }

    private List<Object> redactList(List<?> list) {
        final List<Object> copy = new ArrayList<>(list.size());
        for (final Object element : list) {
            copy.add(redact(element));
        }
        return copy;
    }

    private static boolean isSensitiveKey(Object key) {
        if (!(key instanceof String s)) {
            return false;
        }
        final String normalized = normalize(s);
        for (final String fragment : SENSITIVE_FRAGMENTS) {
            if (normalized.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lower-cases a key and removes the separators that split a word without changing which word it is, so that
     * {@code api-key}, {@code api_key}, {@code Api Key} and {@code apiKey} all reach the fragment list as
     * {@code apikey}.
     */
    private static String normalize(String key) {
        final StringBuilder normalized = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            final char c = key.charAt(i);
            if (SEPARATORS.indexOf(c) < 0) {
                normalized.append(c);
            }
        }
        return normalized.toString().toLowerCase(Locale.ROOT);
    }
}
