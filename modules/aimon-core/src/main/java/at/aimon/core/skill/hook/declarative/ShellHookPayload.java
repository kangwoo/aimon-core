package at.aimon.core.skill.hook.declarative;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Builds the JSON document handed to a declarative shell hook on standard input (Claude Code parity).
 *
 * <p>
 * Environment variables alone cannot carry the tool input — it is a nested, arbitrarily-shaped object, and the env
 * block has a hard size limit that a large {@code Write} or {@code Edit} payload would blow through. Claude Code
 * solves this by piping a JSON document to the hook command; AIMON does the same so a script written against either
 * runtime reads its input the same way:
 *
 * <pre>
 * {@code
 * payload=$(cat)
 * tool=$(echo "$payload" | jq -r '.tool_name')
 * path=$(echo "$payload" | jq -r '.tool_input.file_path')
 * }
 * </pre>
 *
 * <p>
 * <b>Field derivation.</b> The scalar fields mirror the {@code AIMON_*} environment variables the same hook exports,
 * transformed by stripping the {@code AIMON_} prefix and lower-casing: {@code AIMON_TOOL_NAME} &rarr;
 * {@code tool_name}, {@code AIMON_ITERATION} &rarr; {@code iteration}. Keeping one source of truth means an event that
 * gains a new env var gains the matching JSON field for free — and a script can pick whichever channel is more
 * convenient without the two drifting apart. The nested {@code tool_input} object is added on tool events only.
 *
 * <p>
 * Stateless and thread-safe.
 */
final class ShellHookPayload {

    private static final Logger log = LoggerFactory.getLogger(ShellHookPayload.class);

    private static final String ENV_PREFIX = "AIMON_";

    /** Emitted when serialisation fails, so the hook command still receives well-formed JSON on stdin. */
    private static final String EMPTY_JSON = "{}";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ShellHookPayload() {
        // utility class
    }

    /**
     * Renders the payload for a hook firing.
     *
     * @param env
     *            the {@code AIMON_*} environment map the hook exports (must not be null)
     * @param toolInput
     *            the raw tool input to nest under {@code tool_input}, or null on non-tool events
     * @return a JSON object string (never null; {@code "{}"} if serialisation failed)
     */
    static String render(Map<String, String> env, Map<String, Object> toolInput) {
        Objects.requireNonNull(env, "env cannot be null");
        final Map<String, Object> doc = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            doc.put(toJsonField(e.getKey()), e.getValue());
        }
        if (toolInput != null) {
            doc.put("tool_input", toolInput);
        }
        try {
            return MAPPER.writeValueAsString(doc);
        } catch (JsonProcessingException ex) {
            // Tool inputs come from the model and can contain values Jackson refuses; a hook that receives "{}"
            // degrades gracefully, whereas propagating would break the "hooks never throw" contract.
            log.warn("Failed to serialise shell hook payload; sending an empty document instead: {}", ex.getMessage());
            return EMPTY_JSON;
        }
    }

    private static String toJsonField(String envName) {
        final String stripped = envName.startsWith(ENV_PREFIX) ? envName.substring(ENV_PREFIX.length()) : envName;
        return stripped.toLowerCase(Locale.ROOT);
    }
}
