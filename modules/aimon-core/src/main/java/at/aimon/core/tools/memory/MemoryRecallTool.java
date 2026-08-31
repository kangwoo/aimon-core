package at.aimon.core.tools.memory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolContextKey;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Representation;
import at.aimon.core.memory.RepresentationStore;
import at.aimon.core.memory.Workspace;

/**
 * Injects the latest {@link Representation peer representation} as a
 * context-window snapshot — the AIMON analogue of Honcho
 * {@code session.context()}.
 *
 * <p>
 * Inputs:
 * <ul>
 * <li>{@code mode} (string, optional) — {@code GLOBAL} (default, system-wide
 * understanding of the subject) or {@code LOCAL} (subject as seen by the
 * observer in the current session).</li>
 * <li>{@code max_tokens} (number, optional) — when set, a representation whose
 * {@code tokenCount} exceeds the budget returns the summary only (observations
 * are dropped). Use {@code 0} or omit to skip budgeting.</li>
 * </ul>
 *
 * <p>
 * Context keys (typed):
 * <ul>
 * <li>{@link #WORKSPACE_KEY} — required.</li>
 * <li>{@link #OBSERVER_KEY} — required for {@code LOCAL} mode.</li>
 * <li>{@link #SUBJECT_KEY} — defaults to {@link #OBSERVER_KEY} when absent.</li>
 * <li>{@link #SESSION_ID_KEY} — optional; when omitted, cross-session local
 * representations are matched.</li>
 * </ul>
 */
public final class MemoryRecallTool extends AbstractTool {

    public static final String TOOL_NAME = "MemoryRecall";

    /** Workspace the recall runs in. */
    public static final ToolContextKey<Workspace> WORKSPACE_KEY = MemoryToolContextKeys.WORKSPACE;

    /** Peer doing the recall (typically the agent itself). */
    public static final ToolContextKey<PeerView> OBSERVER_KEY = MemoryToolContextKeys.OBSERVER;

    /** Peer the recall is about; defaults to {@link #OBSERVER_KEY} when missing. */
    public static final ToolContextKey<PeerView> SUBJECT_KEY = MemoryToolContextKeys.SUBJECT;

    /** Optional session id correlating this recall to a specific session. */
    public static final ToolContextKey<String> SESSION_ID_KEY = MemoryToolContextKeys.SESSION_ID;

    private static final Logger log = LoggerFactory.getLogger(MemoryRecallTool.class);

    private final RepresentationStore representationStore;

    public MemoryRecallTool(RepresentationStore representationStore) {
        super(TOOL_NAME,
                "Recall the latest insight snapshot about a peer (a Representation) so it can be injected "
                        + "into the reasoning context. Use this when you need a quick portrait of who the peer is, "
                        + "what they prefer, and what has been observed about them across sessions. "
                        + "Returns the summary plus the observations the snapshot was built from; honours an "
                        + "optional max_tokens budget by dropping observations when the snapshot is too large.",
                createInputSchema());
        this.representationStore = Objects.requireNonNull(representationStore, "representationStore cannot be null");
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties", Map.of("mode",
                Map.of("type", "string", "description", "Recall scope: GLOBAL (cross-session, no observer) or LOCAL "
                        + "(observer-and-session-bound). Defaults to GLOBAL.", "enum", List.of("GLOBAL", "LOCAL")),
                "max_tokens",
                Map.of("type", "number", "description",
                        "Optional token budget. When the representation tokenCount exceeds the budget, "
                                + "observations are dropped and only the summary is returned. "
                                + "Use 0 or omit to skip budgeting.")),
                "required", List.of());
    }

    @Override
    public SideEffectLevel getSideEffectLevel() {
        // Reads memory entries back; recall does not mark, touch, or expire them.
        return SideEffectLevel.READ_ONLY;
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "input cannot be null");
        Objects.requireNonNull(context, "context cannot be null");

        try {
            RecallMode mode = parseMode(input.getStringOrNull("mode"));
            int maxTokens = input.getInteger("max_tokens", 0);
            if (maxTokens < 0) {
                return ToolResult.error("max_tokens must be >= 0, got " + maxTokens);
            }

            Workspace workspace = context.get(WORKSPACE_KEY).orElse(null);
            if (workspace == null) {
                return ToolResult.error("MemoryRecall requires '" + WORKSPACE_KEY.name() + "' in ToolContext");
            }
            PeerView observer = context.get(OBSERVER_KEY).orElse(null);
            if (mode == RecallMode.LOCAL && observer == null) {
                return ToolResult
                        .error("MemoryRecall LOCAL mode requires '" + OBSERVER_KEY.name() + "' in ToolContext");
            }
            PeerView subject = context.get(SUBJECT_KEY).orElse(observer);
            if (subject == null) {
                return ToolResult.error("MemoryRecall requires either '" + SUBJECT_KEY.name() + "' or '"
                        + OBSERVER_KEY.name() + "' in ToolContext to identify the recall target");
            }
            if (!subject.getWorkspace().equals(workspace)) {
                return ToolResult.error("subject workspace (" + subject.getWorkspace().getId()
                        + ") does not match recall workspace (" + workspace.getId() + ")");
            }

            Optional<Representation> latest = mode == RecallMode.GLOBAL
                    ? representationStore.findLatestGlobal(subject)
                    : representationStore.findLatestLocal(subject, observer, context.get(SESSION_ID_KEY).orElse(null));

            if (latest.isEmpty()) {
                log.debug("MemoryRecall miss: subject={} mode={}", subject.key(), mode);
                return ToolResult.success("No " + mode.name().toLowerCase(Locale.ROOT)
                        + " representation available for " + subject.key() + " yet.");
            }

            String rendered = render(latest.get(), maxTokens);
            log.debug("MemoryRecall hit: subject={} mode={} tokens={}", subject.key(), mode,
                    latest.get().getTokenCount());
            return ToolResult.success(rendered);

        } catch (IllegalArgumentException e) {
            log.warn("MemoryRecall invalid input: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("MemoryRecall unexpected error: {}", e.getMessage(), e);
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    private static String render(Representation rep, int maxTokens) {
        boolean overBudget = maxTokens > 0 && rep.getTokenCount() > maxTokens;

        StringBuilder out = new StringBuilder(256);
        out.append("Representation for ").append(rep.getSubject().key()).append('\n');
        out.append("scope: ").append(rep.isGlobal() ? "global" : "local").append('\n');
        out.append("generatedAt: ").append(rep.getGeneratedAt()).append('\n');
        out.append("tokenCount: ").append(rep.getTokenCount());
        if (overBudget) {
            out.append(" (over budget=").append(maxTokens).append(", observations omitted)");
        }
        out.append('\n');
        out.append('\n');
        out.append("Summary:\n");
        out.append(rep.getSummary().isEmpty() ? "(empty)" : rep.getSummary()).append('\n');

        if (!overBudget && !rep.getObservations().isEmpty()) {
            out.append('\n');
            out.append("Observations (").append(rep.getObservations().size()).append("):\n");
            for (Observation obs : rep.getObservations()) {
                out.append("- [").append(obs.getId().getLocalId()).append("] ").append(obs.getContent())
                        .append(" (confidence=").append(String.format(Locale.ROOT, "%.2f", obs.getConfidence()))
                        .append(")\n");
            }
        }
        return out.toString();
    }

    private static RecallMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return RecallMode.GLOBAL;
        }
        try {
            return RecallMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown recall mode: '" + raw + "' (expected GLOBAL or LOCAL)");
        }
    }

    private enum RecallMode {
        GLOBAL, LOCAL
    }
}
