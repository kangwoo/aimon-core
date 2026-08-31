package at.aimon.core.agent.compact;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.llm.Message;

/**
 * Builds the post-compaction marker messages per design §4.5.
 *
 * <p>
 * For a given session UUID, two {@code USER}-role messages are produced:
 *
 * <ol>
 * <li><b>Boundary marker</b> — wraps a JSON metadata payload between {@code BOUNDARY_OPEN} and {@code BOUNDARY_CLOSE}
 * tokens. Re-compaction can locate prior boundaries by matching on {@code sessionUuid}.
 * <li><b>Summary body</b> — wraps the LLM-generated summary text between {@code SUMMARY_OPEN} and {@code SUMMARY_CLOSE}
 * tokens, followed by a "Continue from here." prompt that anchors the next assistant turn.
 * </ol>
 *
 * <p>
 * Marker tokens use the per-session UUID suffix to prevent collision with user input or tool output. Stateless and
 * thread-safe.
 */
public final class CompactBoundary {

    public static final String BOUNDARY_OPEN_PREFIX = "[[COMPACT_BOUNDARY:";
    public static final String BOUNDARY_CLOSE_PREFIX = "[[/COMPACT_BOUNDARY:";
    public static final String SUMMARY_OPEN_PREFIX = "[[COMPACT_SUMMARY:";
    public static final String SUMMARY_CLOSE_PREFIX = "[[/COMPACT_SUMMARY:";
    public static final String MARKER_SUFFIX = "]]";
    public static final String CONTINUE_TRAILER = "Continue from here.";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CompactBoundary() {
    }

    /**
     * Returns the boundary opening token for the given session UUID, e.g. {@code [[COMPACT_BOUNDARY:abc-123]]}.
     */
    public static String boundaryOpen(String sessionUuid) {
        Objects.requireNonNull(sessionUuid, "sessionUuid cannot be null");
        return BOUNDARY_OPEN_PREFIX + sessionUuid + MARKER_SUFFIX;
    }

    /** Returns the boundary closing token for the given session UUID, e.g. {@code [[/COMPACT_BOUNDARY:abc-123]]}. */
    public static String boundaryClose(String sessionUuid) {
        Objects.requireNonNull(sessionUuid, "sessionUuid cannot be null");
        return BOUNDARY_CLOSE_PREFIX + sessionUuid + MARKER_SUFFIX;
    }

    /** Returns the summary opening token for the given session UUID, e.g. {@code [[COMPACT_SUMMARY:abc-123]]}. */
    public static String summaryOpen(String sessionUuid) {
        Objects.requireNonNull(sessionUuid, "sessionUuid cannot be null");
        return SUMMARY_OPEN_PREFIX + sessionUuid + MARKER_SUFFIX;
    }

    /** Returns the summary closing token for the given session UUID, e.g. {@code [[/COMPACT_SUMMARY:abc-123]]}. */
    public static String summaryClose(String sessionUuid) {
        Objects.requireNonNull(sessionUuid, "sessionUuid cannot be null");
        return SUMMARY_CLOSE_PREFIX + sessionUuid + MARKER_SUFFIX;
    }

    /**
     * Builds the boundary marker message containing the JSON-encoded metadata payload.
     *
     * @param sessionUuid
     *            session UUID used to suffix the marker tokens (must not be null)
     * @param trigger
     *            the compaction trigger (must not be null)
     * @param preTokenCount
     *            estimated token count before compaction
     * @param messagesSummarized
     *            number of original messages folded into the summary
     * @param discoveredToolNames
     *            distinct tool names observed in the original conversation; passed through into the metadata for
     *            re-compaction parsers (must not be null; may be empty)
     * @return the boundary {@link Message} (USER role)
     */
    public static Message boundaryMessage(String sessionUuid, CompactionTrigger trigger, int preTokenCount,
            int messagesSummarized, List<String> discoveredToolNames) {
        Objects.requireNonNull(sessionUuid, "sessionUuid cannot be null");
        Objects.requireNonNull(trigger, "trigger cannot be null");
        Objects.requireNonNull(discoveredToolNames, "discoveredToolNames cannot be null");
        final String json = encodeMetadata(sessionUuid, trigger, preTokenCount, messagesSummarized,
                discoveredToolNames);
        return Message.user(boundaryOpen(sessionUuid) + "\n" + json + "\n" + boundaryClose(sessionUuid));
    }

    /**
     * Builds the summary body message wrapped in summary markers and trailed by the "Continue from here." anchor.
     */
    public static Message summaryMessage(String sessionUuid, String summaryText) {
        Objects.requireNonNull(sessionUuid, "sessionUuid cannot be null");
        Objects.requireNonNull(summaryText, "summaryText cannot be null");
        return Message.user(summaryOpen(sessionUuid) + "\n" + summaryText + "\n" + summaryClose(sessionUuid) + "\n\n"
                + CONTINUE_TRAILER);
    }

    private static String encodeMetadata(String sessionUuid, CompactionTrigger trigger, int preTokenCount,
            int messagesSummarized, List<String> discoveredToolNames) {
        final ObjectNode root = MAPPER.createObjectNode();
        root.put("sessionUuid", sessionUuid);
        root.put("trigger", trigger.name());
        root.put("preTokenCount", preTokenCount);
        root.put("messagesSummarized", messagesSummarized);
        final ArrayNode tools = root.putArray("discoveredToolNames");
        for (String name : discoveredToolNames) {
            if (name != null) {
                tools.add(name);
            }
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            // Defensive: ObjectNode encoding cannot legitimately fail.
            return "{\"sessionUuid\":\"" + sessionUuid + "\",\"trigger\":\"" + trigger.name() + "\"}";
        }
    }
}
