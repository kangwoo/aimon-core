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
import at.aimon.core.memory.MemoryInjectionMode;
import at.aimon.core.memory.MemorySnapshot;
import at.aimon.core.memory.MemorySnapshotQuery;
import at.aimon.core.memory.MemorySnapshotReader;
import at.aimon.core.memory.MemorySnapshotScope;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.RepresentationStore;
import at.aimon.core.memory.StoreBackedPeerMemory;
import at.aimon.core.memory.Workspace;

/**
 * Injects the latest peer snapshot into the reasoning context — the AIMON
 * analogue of Honcho {@code session.context()}.
 *
 * <p>
 * The tool stands on the SNAPSHOT tier rather than on a store, so a backend that
 * computes its snapshot on read instead of storing one serves it unchanged.
 *
 * <p>
 * Inputs:
 * <ul>
 * <li>{@code mode} (string, optional) — {@code GLOBAL} (default, system-wide
 * understanding of the subject) or {@code LOCAL} (subject as seen by the
 * observer in the current session).</li>
 * <li>{@code max_tokens} (number, optional) — when set, a snapshot whose
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
 * snapshots are matched.</li>
 * </ul>
 *
 * <p>
 * <b>Two things the render says out loud rather than by omission.</b> A backend
 * that does not expose individual observations produces an empty list, and so
 * does a peer nobody has observed yet; without a line saying which, the model
 * reads the first as the second. Likewise a confidence the backend did not store
 * is not printed at all, because a plausible number the model believes is worse
 * than a missing one.
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

    /**
     * What the model is told this tool returns.
     *
     * <p>
     * "Returns the summary plus the observations the snapshot was built from" was true of the store-backed backend
     * and of no other: a backend that computes its snapshot on read has no individual observations to hand over, and
     * says so through {@link MemorySnapshot#isObservationsAvailable()}. The render already reports that case in a
     * line of its own; the description is widened to match it so the two do not disagree before the first call.
     *
     * <p>
     * The budget clause is hedged for the same reason and it is the same sentence: {@code max_tokens} goes onto
     * {@link MemorySnapshotQuery}, and {@link MemorySnapshotQuery#getMaxTokens()} is honoured exactly by the default
     * backend and treated as a hint by a remote one. A backend that ignores it returns an over-budget snapshot with
     * {@link MemorySnapshot#isTruncated()} false, the render prints no over-budget note, and a flat promise would
     * have been made to the model before its first call.
     */
    private static final String DESCRIPTION = "Recall the latest insight snapshot about a peer so it can be injected "
            + "into the reasoning context. Use this when you need a quick portrait of who the peer is, what they "
            + "prefer, and what has been observed about them across sessions. Returns the summary and, where the "
            + "memory backend exposes them, the observations behind it. An optional max_tokens budget is passed to "
            + "the backend, which drops observations to fit it where it can; the result says whether it did.";

    private static final Logger log = LoggerFactory.getLogger(MemoryRecallTool.class);

    private final MemorySnapshotReader snapshotReader;

    /**
     * Creates a recall tool on the SNAPSHOT tier.
     *
     * @param snapshotReader
     *            the tier snapshots are read from (must not be null)
     */
    public MemoryRecallTool(MemorySnapshotReader snapshotReader) {
        super(TOOL_NAME, DESCRIPTION, createInputSchema());
        this.snapshotReader = Objects.requireNonNull(snapshotReader, "snapshotReader cannot be null");
    }

    /**
     * Creates a recall tool over a {@link RepresentationStore}, for callers assembling the default backend by hand.
     *
     * <p>
     * A named factory rather than a second constructor, for the reason
     * {@link at.aimon.core.memory.SnapshotMemoryContextProvider#readerOver} gives: two constructors taking unrelated
     * interfaces are ambiguous for any caller passing a {@code null}, and — worse than the compile error — they read
     * as saying a store and a tier are interchangeable, which is the one thing the tier SPI exists to deny. The
     * altitude is in the name instead.
     *
     * @param representationStore
     *            backing store (must not be null)
     * @return a recall tool on the SNAPSHOT tier that store provides
     * @throws NullPointerException
     *             if {@code representationStore} is null
     */
    public static MemoryRecallTool overStore(RepresentationStore representationStore) {
        Objects.requireNonNull(representationStore, "representationStore cannot be null");
        return new MemoryRecallTool(StoreBackedPeerMemory.builder().representationStore(representationStore).build()
                .snapshotReader().orElseThrow());
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties", Map.of("mode",
                Map.of("type", "string", "description", "Recall scope: GLOBAL (cross-session, no observer) or LOCAL "
                        + "(observer-and-session-bound). Defaults to GLOBAL.", "enum", List.of("GLOBAL", "LOCAL")),
                "max_tokens",
                Map.of("type", "number", "description",
                        "Optional token budget. When the snapshot tokenCount exceeds the budget, "
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

            // FULL because the tool's own budget, not an injection mode, decides whether observations come along.
            MemorySnapshotQuery.Builder query = MemorySnapshotQuery.builder().subject(subject)
                    .mode(MemoryInjectionMode.FULL).maxTokens(maxTokens);
            if (mode == RecallMode.LOCAL) {
                query.scope(MemorySnapshotScope.LOCAL).observer(observer)
                        .sessionId(context.get(SESSION_ID_KEY).orElse(null));
            } else {
                query.scope(MemorySnapshotScope.GLOBAL);
            }

            Optional<MemorySnapshot> latest = snapshotReader.read(query.build());
            if (latest.isEmpty()) {
                log.debug("MemoryRecall miss: subject={} mode={}", subject.key(), mode);
                return ToolResult.success("No " + mode.name().toLowerCase(Locale.ROOT) + " snapshot available for "
                        + subject.key() + " yet.");
            }

            String rendered = render(subject, latest.get(), maxTokens);
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

    private static String render(PeerView subject, MemorySnapshot snapshot, int maxTokens) {
        StringBuilder out = new StringBuilder(256);
        out.append("Snapshot for ").append(subject.key()).append('\n');
        out.append("scope: ").append(snapshot.getResolvedScope() == MemorySnapshotScope.GLOBAL ? "global" : "local")
                .append('\n');
        out.append("generatedAt: ").append(snapshot.getGeneratedAt()).append('\n');
        out.append("tokenCount: ").append(snapshot.getTokenCount());
        if (snapshot.isTruncated()) {
            out.append(" (over budget=").append(maxTokens).append(", observations omitted)");
        }
        out.append('\n');
        out.append('\n');
        out.append("Summary:\n");
        out.append(snapshot.getRenderedText().isEmpty() ? "(empty)" : snapshot.getRenderedText()).append('\n');

        if (!snapshot.isObservationsAvailable()) {
            // Without this line an empty list reads as "nothing has been observed about this peer", which is a
            // different and wrong statement.
            out.append('\n');
            out.append("(this memory backend does not expose individual observations — the summary is all there is)\n");
            return out.toString();
        }

        if (!snapshot.getObservations().isEmpty()) {
            out.append('\n');
            out.append("Observations (").append(snapshot.getObservations().size()).append("):\n");
            for (Observation obs : snapshot.getObservations()) {
                out.append("- [").append(obs.getId().getLocalId()).append("] ").append(obs.getContent());
                if (snapshot.isConfidenceAvailable()) {
                    out.append(" (confidence=").append(String.format(Locale.ROOT, "%.2f", obs.getConfidence()))
                            .append(")");
                }
                out.append('\n');
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
