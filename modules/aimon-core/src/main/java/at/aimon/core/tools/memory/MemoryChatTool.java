package at.aimon.core.tools.memory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolContextKey;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.dialectic.DialecticEngine;
import at.aimon.core.memory.dialectic.DialecticQuery;
import at.aimon.core.memory.dialectic.DialecticResponse;
import at.aimon.core.memory.dialectic.ReasoningLevel;

/**
 * Asks the {@link DialecticEngine} a natural-language question about a peer.
 *
 * <p>
 * The tool reads the {@link Workspace}, {@link PeerView observer}, and
 * {@link PeerView subject} from {@link ToolContext} via the typed keys exposed
 * on this class. The agent that wires the tool into a session is responsible
 * for populating those keys with the active workspace and the current peers —
 * the tool itself is stateless.
 *
 * <p>
 * Inputs:
 * <ul>
 * <li>{@code question} (string, required) — the question to answer.</li>
 * <li>{@code level} (string, optional) — one of {@code FAST}, {@code BALANCED},
 * {@code DEEP}; defaults to {@code BALANCED}.</li>
 * </ul>
 */
public final class MemoryChatTool extends AbstractTool {

    public static final String TOOL_NAME = "MemoryChat";

    /** Workspace the dialectic query runs in. */
    public static final ToolContextKey<Workspace> WORKSPACE_KEY = MemoryToolContextKeys.WORKSPACE;

    /** Peer asking the question (typically the agent itself). */
    public static final ToolContextKey<PeerView> OBSERVER_KEY = MemoryToolContextKeys.OBSERVER;

    /** Peer the question is about. */
    public static final ToolContextKey<PeerView> SUBJECT_KEY = MemoryToolContextKeys.SUBJECT;

    /** Optional session id correlating this query to a specific session. */
    public static final ToolContextKey<String> SESSION_ID_KEY = MemoryToolContextKeys.SESSION_ID;

    private static final Logger log = LoggerFactory.getLogger(MemoryChatTool.class);

    private final DialecticEngine dialecticEngine;

    public MemoryChatTool(DialecticEngine dialecticEngine) {
        super(TOOL_NAME,
                "Answer a natural-language question about a peer using stored observations. "
                        + "Use this when the user asks 'what do we know about <peer>' style questions. "
                        + "Returns a short answer plus the list of observations the engine considered.",
                createInputSchema());
        this.dialecticEngine = Objects.requireNonNull(dialecticEngine, "dialecticEngine cannot be null");
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties", Map.of("question",
                Map.of("type", "string", "description", "The natural-language question about the peer."), "level",
                Map.of("type", "string", "description",
                        "Reasoning depth: FAST (cheap lookup), BALANCED (default), or DEEP "
                                + "(thorough multi-step). Defaults to BALANCED.",
                        "enum", List.of("FAST", "BALANCED", "DEEP"))),
                "required", List.of("question"));
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "input cannot be null");
        Objects.requireNonNull(context, "context cannot be null");

        try {
            String question = input.getRequiredString("question");
            if (question.isBlank()) {
                return ToolResult.error("question cannot be blank");
            }
            ReasoningLevel level = parseLevel(input.getStringOrNull("level"));

            Workspace workspace = context.get(WORKSPACE_KEY).orElse(null);
            if (workspace == null) {
                return ToolResult.error("MemoryChat requires '" + WORKSPACE_KEY.name() + "' in ToolContext");
            }
            PeerView observer = context.get(OBSERVER_KEY).orElse(null);
            if (observer == null) {
                return ToolResult.error("MemoryChat requires '" + OBSERVER_KEY.name() + "' in ToolContext");
            }
            PeerView subject = context.get(SUBJECT_KEY).orElse(observer);
            String sessionId = context.get(SESSION_ID_KEY).orElse(null);

            DialecticQuery.Builder queryBuilder = DialecticQuery.builder().workspace(workspace).observer(observer)
                    .subject(subject).question(question).level(level);
            if (sessionId != null && !sessionId.isBlank()) {
                queryBuilder.sessionId(sessionId);
            }
            DialecticQuery query = queryBuilder.build();

            DialecticResponse response = dialecticEngine.query(query);
            log.debug("MemoryChat answered subject={} (observations={}, tokens={})", subject.key(),
                    response.getObservationsConsidered().size(), response.getTokenUsage().getTotalTokens());
            return ToolResult.success(response.getAnswer());

        } catch (IllegalArgumentException e) {
            log.warn("MemoryChat invalid input: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("MemoryChat unexpected error: {}", e.getMessage(), e);
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    private static ReasoningLevel parseLevel(String raw) {
        if (raw == null || raw.isBlank()) {
            return ReasoningLevel.BALANCED;
        }
        try {
            return ReasoningLevel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown reasoning level: '" + raw + "'");
        }
    }
}
