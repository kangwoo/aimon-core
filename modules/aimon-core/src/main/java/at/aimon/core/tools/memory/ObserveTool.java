package at.aimon.core.tools.memory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolContextKey;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.redaction.RedactionPolicy;
import at.aimon.core.memory.redaction.RedactionResult;

/**
 * Explicitly registers a single {@link Observation}. Intended for
 * administrator/system flows that bypass the deriver — e.g. importing facts
 * the operator already knows or running data-fixup scripts.
 *
 * <p>
 * Inputs:
 * <ul>
 * <li>{@code content} (string, required) — the factual sentence.</li>
 * <li>{@code type} (string, optional) — {@code EXPLICIT} or {@code DEDUCTIVE};
 * defaults to {@code DEDUCTIVE}.</li>
 * <li>{@code confidence} (number, optional) — value in {@code [0, 1]};
 * defaults to {@value #DEFAULT_CONFIDENCE}.</li>
 * </ul>
 *
 * <p>
 * Context keys (same vocabulary as the other memory tools):
 * <ul>
 * <li>{@link #WORKSPACE_KEY} — required.</li>
 * <li>{@link #OBSERVER_KEY} — required (who is recording the observation).</li>
 * <li>{@link #SUBJECT_KEY} — defaults to the observer when absent.</li>
 * </ul>
 *
 * <p>
 * When a {@link RedactionPolicy} is configured, the content is redacted before
 * persistence and the metadata records {@code redacted=true} along with the
 * matched categories (design doc §6.5).
 */
public final class ObserveTool extends AbstractTool {

    public static final String TOOL_NAME = "Observe";

    /** Workspace the observation lives in. */
    public static final ToolContextKey<Workspace> WORKSPACE_KEY = MemoryToolContextKeys.WORKSPACE;

    /** Peer recording the observation. */
    public static final ToolContextKey<PeerView> OBSERVER_KEY = MemoryToolContextKeys.OBSERVER;

    /** Peer the observation is about; defaults to the observer when absent. */
    public static final ToolContextKey<PeerView> SUBJECT_KEY = MemoryToolContextKeys.SUBJECT;

    static final double DEFAULT_CONFIDENCE = 0.7d;

    private static final String META_KEY_REDACTED = "redacted";
    private static final String META_KEY_REDACTION_CATEGORIES = "redaction.categories";
    private static final String META_KEY_SOURCE = "source";
    private static final String META_VALUE_SOURCE = "ObserveTool";

    private static final Logger log = LoggerFactory.getLogger(ObserveTool.class);

    private final ObservationStore observationStore;
    private final RedactionPolicy redactionPolicy;

    public ObserveTool(ObservationStore observationStore) {
        this(observationStore, null);
    }

    /**
     * Creates a new {@code ObserveTool}.
     *
     * @param observationStore
     *            backing store (must not be null)
     * @param redactionPolicy
     *            optional policy applied to {@code content} before persistence; {@code null} to disable
     */
    public ObserveTool(ObservationStore observationStore, RedactionPolicy redactionPolicy) {
        super(TOOL_NAME,
                "Record a single explicit observation about a peer (administrator / system flow). "
                        + "Use this to import facts the operator already knows; the deriver remains responsible "
                        + "for inferences from conversations. Returns the new observation id.",
                createInputSchema());
        this.observationStore = Objects.requireNonNull(observationStore, "observationStore cannot be null");
        this.redactionPolicy = redactionPolicy;
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties",
                Map.of("content",
                        Map.of("type", "string", "description", "The factual sentence to record about the subject."),
                        "type",
                        Map.of("type", "string", "description",
                                "Observation kind: EXPLICIT (stated directly) or DEDUCTIVE (inferred). "
                                        + "Defaults to DEDUCTIVE.",
                                "enum", List.of("EXPLICIT", "DEDUCTIVE")),
                        "confidence",
                        Map.of("type", "number", "description",
                                "Confidence in [0, 1]. Defaults to " + DEFAULT_CONFIDENCE + ".")),
                "required", List.of("content"));
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "input cannot be null");
        Objects.requireNonNull(context, "context cannot be null");

        try {
            String rawContent = input.getRequiredString("content");
            if (rawContent.isBlank()) {
                return ToolResult.error("content cannot be blank");
            }
            ObservationType type = parseType(input.getStringOrNull("type"));
            double confidence = parseConfidence(input);
            if (confidence < 0.0d || confidence > 1.0d) {
                return ToolResult.error("confidence must be within [0, 1], got " + confidence);
            }

            Workspace workspace = context.get(WORKSPACE_KEY).orElse(null);
            if (workspace == null) {
                return ToolResult.error("Observe requires '" + WORKSPACE_KEY.name() + "' in ToolContext");
            }
            PeerView observer = context.get(OBSERVER_KEY).orElse(null);
            if (observer == null) {
                return ToolResult.error("Observe requires '" + OBSERVER_KEY.name() + "' in ToolContext");
            }
            PeerView subject = context.get(SUBJECT_KEY).orElse(observer);
            if (!subject.getWorkspace().equals(workspace)) {
                return ToolResult.error("subject workspace (" + subject.getWorkspace().getId()
                        + ") does not match observe workspace (" + workspace.getId() + ")");
            }
            if (!observer.getWorkspace().equals(workspace)) {
                return ToolResult.error("observer workspace (" + observer.getWorkspace().getId()
                        + ") does not match observe workspace (" + workspace.getId() + ")");
            }

            RedactionResult redaction = applyRedaction(rawContent);
            String storedContent = redaction.getRedactedContent();
            if (storedContent.isBlank()) {
                return ToolResult.error("content became blank after redaction; nothing recorded");
            }

            Observation saved = observationStore
                    .save(buildObservation(workspace, subject, observer, storedContent, type, confidence, redaction));
            log.debug("Observe recorded observation {} for subject={}", saved.getId(), subject.key());
            return ToolResult.success(render(saved, redaction));

        } catch (IllegalArgumentException e) {
            log.warn("Observe invalid input: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("Observe unexpected error: {}", e.getMessage(), e);
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    private RedactionResult applyRedaction(String content) {
        if (redactionPolicy == null) {
            return RedactionResult.unchanged(content);
        }
        RedactionResult result = redactionPolicy.redact(content);
        if (result.isModified()) {
            log.debug("Observe content redacted: categories={}", result.getCategories());
        }
        return result;
    }

    private static Observation buildObservation(Workspace workspace, PeerView subject, PeerView observer,
            String content, ObservationType type, double confidence, RedactionResult redaction) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(META_KEY_SOURCE, META_VALUE_SOURCE);
        if (redaction.isModified()) {
            metadata.put(META_KEY_REDACTED, "true");
            metadata.put(META_KEY_REDACTION_CATEGORIES, String.join(",", redaction.getCategories()));
        }
        ObservationId id = ObservationId.of(workspace, UUID.randomUUID().toString());
        return Observation.builder().id(id).subject(subject).observer(observer).content(content).type(type)
                .confidence(confidence).createdAt(Instant.now()).metadata(Map.copyOf(metadata)).build();
    }

    private static String render(Observation saved, RedactionResult redaction) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("Observation recorded\n");
        sb.append("id: ").append(saved.getId()).append('\n');
        sb.append("subject: ").append(saved.getSubject().key()).append('\n');
        sb.append("type: ").append(saved.getType()).append('\n');
        sb.append("confidence: ").append(String.format(Locale.ROOT, "%.2f", saved.getConfidence())).append('\n');
        sb.append("content: ").append(saved.getContent()).append('\n');
        if (redaction.isModified()) {
            sb.append("redacted: ").append(String.join(",", redaction.getCategories())).append('\n');
        }
        return sb.toString();
    }

    private static ObservationType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return ObservationType.DEDUCTIVE;
        }
        try {
            return ObservationType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown observation type: '" + raw + "'");
        }
    }

    private static double parseConfidence(ToolInput input) {
        if (!input.has("confidence")) {
            return DEFAULT_CONFIDENCE;
        }
        Object raw = input.get("confidence");
        if (raw == null) {
            return DEFAULT_CONFIDENCE;
        }
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("confidence must be a finite number");
            }
            return value;
        }
        throw new IllegalArgumentException("confidence must be a number, got " + raw.getClass().getSimpleName());
    }
}
