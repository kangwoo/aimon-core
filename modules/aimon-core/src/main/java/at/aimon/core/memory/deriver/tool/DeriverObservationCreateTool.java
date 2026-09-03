package at.aimon.core.memory.deriver.tool;

import java.time.Instant;
import java.util.ArrayList;
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
 * Internal deriver tool: persist a single {@link Observation} candidate
 * extracted by the ReAct loop. Honcho analogue of {@code create_observations}
 * (design doc §6.1.4).
 *
 * <p>
 * The tool is stateless. The deriver-side ReAct loop wires the {@link Workspace},
 * observer, and subject into the {@link ToolContext}; the tool itself only
 * looks up those keys and persists the candidate via {@link ObservationStore}.
 *
 * <p>
 * Inputs:
 * <ul>
 * <li>{@code content} (string, required) — factual sentence about the subject.</li>
 * <li>{@code type} (string, optional) — {@code EXPLICIT} or {@code DEDUCTIVE};
 * defaults to {@code DEDUCTIVE}.</li>
 * <li>{@code source_message_ids} (array of strings, optional) — messages this
 * observation was derived from; embedded into the resulting {@code Observation}.</li>
 * </ul>
 *
 * <p>
 * Confidence is <em>not</em> a tool input: per design doc §4.3 the system computes it from the
 * {@code type} base score ({@code EXPLICIT=0.9}, {@code DEDUCTIVE=0.6}) rather than letting the LLM
 * self-report it.
 *
 * <p>
 * The tool returns the new observation id so the deriver can chain it into
 * {@link DeriverMessageLinkTool} or follow-up reconciliation calls.
 */
public class DeriverObservationCreateTool extends AbstractTool {

    public static final String TOOL_NAME = "deriver.observation.create";

    /** Workspace the new observation belongs to. */
    public static final ToolContextKey<Workspace> WORKSPACE_KEY = ToolContextKey.of("memory.workspace",
            Workspace.class);

    /** Peer that recorded the observation (typically the deriver agent). */
    public static final ToolContextKey<PeerView> OBSERVER_KEY = ToolContextKey.of("memory.observer", PeerView.class);

    /** Peer the observation is about; defaults to {@link #OBSERVER_KEY} when absent. */
    public static final ToolContextKey<PeerView> SUBJECT_KEY = ToolContextKey.of("memory.subject", PeerView.class);

    private static final String META_KEY_REDACTED = "redacted";
    private static final String META_KEY_REDACTION_CATEGORIES = "redaction.categories";
    private static final String META_KEY_SOURCE = "source";
    private static final String META_VALUE_SOURCE = "DeriverObservationCreateTool";

    private static final Logger log = LoggerFactory.getLogger(DeriverObservationCreateTool.class);

    private final ObservationStore observationStore;
    private final RedactionPolicy redactionPolicy;

    public DeriverObservationCreateTool(ObservationStore observationStore) {
        this(observationStore, null);
    }

    /**
     * @param observationStore
     *            backing store (must not be null)
     * @param redactionPolicy
     *            optional policy applied to {@code content} before persistence; {@code null} disables redaction
     */
    public DeriverObservationCreateTool(ObservationStore observationStore, RedactionPolicy redactionPolicy) {
        super(TOOL_NAME,
                "Persist a single observation about the subject peer. Use during the deriver ReAct loop "
                        + "after gathering enough evidence in the conversation. Returns the new observation id.",
                createInputSchema());
        this.observationStore = Objects.requireNonNull(observationStore, "observationStore cannot be null");
        this.redactionPolicy = redactionPolicy;
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties",
                Map.of("content", Map.of("type", "string", "description", "Factual sentence about the subject."),
                        "type",
                        Map.of("type", "string", "description",
                                "Observation kind: EXPLICIT (stated directly) or DEDUCTIVE (inferred). "
                                        + "Defaults to DEDUCTIVE. The system derives confidence from this.",
                                "enum", List.of("EXPLICIT", "DEDUCTIVE")),
                        "source_message_ids",
                        Map.of("type", "array", "description",
                                "Identifiers of source messages this observation was derived from.", "items",
                                Map.of("type", "string"))),
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
            // §4.3: confidence is computed from the type base score, not taken from the LLM.
            double confidence = type.baseConfidence();
            List<String> sourceMessageIds = parseSourceMessageIds(input);

            Workspace workspace = context.get(WORKSPACE_KEY).orElse(null);
            if (workspace == null) {
                return ToolResult.error(TOOL_NAME + " requires '" + WORKSPACE_KEY.name() + "' in ToolContext");
            }
            PeerView observer = context.get(OBSERVER_KEY).orElse(null);
            if (observer == null) {
                return ToolResult.error(TOOL_NAME + " requires '" + OBSERVER_KEY.name() + "' in ToolContext");
            }
            PeerView subject = context.get(SUBJECT_KEY).orElse(observer);
            if (!subject.getWorkspace().equals(workspace)) {
                return ToolResult.error("subject workspace (" + subject.getWorkspace().getId()
                        + ") does not match deriver workspace (" + workspace.getId() + ")");
            }
            if (!observer.getWorkspace().equals(workspace)) {
                return ToolResult.error("observer workspace (" + observer.getWorkspace().getId()
                        + ") does not match deriver workspace (" + workspace.getId() + ")");
            }

            RedactionResult redaction = applyRedaction(rawContent);
            String storedContent = redaction.getRedactedContent();
            if (storedContent.isBlank()) {
                return ToolResult.error("content became blank after redaction; nothing recorded");
            }

            ObservationPayload payload = ObservationPayload.builder().content(storedContent).type(type)
                    .confidence(confidence).sourceMessageIds(sourceMessageIds).build();
            Observation saved = observationStore
                    .save(buildObservation(workspace, subject, observer, payload, redaction));
            log.debug("DeriverObservationCreate recorded {} for subject={}", saved.getId(), subject.key());
            return ToolResult.success(render(saved, redaction));

        } catch (IllegalArgumentException e) {
            log.warn("DeriverObservationCreate invalid input: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("DeriverObservationCreate unexpected error: {}", e.getMessage(), e);
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    private RedactionResult applyRedaction(String content) {
        if (redactionPolicy == null) {
            return RedactionResult.unchanged(content);
        }
        RedactionResult result = redactionPolicy.redact(content);
        if (result.isModified()) {
            log.debug("DeriverObservationCreate content redacted: categories={}", result.getCategories());
        }
        return result;
    }

    private static Observation buildObservation(Workspace workspace, PeerView subject, PeerView observer,
            ObservationPayload payload, RedactionResult redaction) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(META_KEY_SOURCE, META_VALUE_SOURCE);
        if (redaction.isModified()) {
            metadata.put(META_KEY_REDACTED, "true");
            metadata.put(META_KEY_REDACTION_CATEGORIES, String.join(",", redaction.getCategories()));
        }
        ObservationId id = ObservationId.of(workspace, UUID.randomUUID().toString());
        return Observation.builder().id(id).subject(subject).observer(observer).content(payload.getContent())
                .type(payload.getType()).confidence(payload.getConfidence())
                .sourceMessageIds(payload.getSourceMessageIds()).createdAt(Instant.now()).metadata(Map.copyOf(metadata))
                .build();
    }

    /**
     * Immutable holder grouping the cohesive observation payload fields
     * (content, type, confidence, source message ids) passed to
     * {@link #buildObservation}.
     *
     * <p>
     * Named for what it holds rather than for what it is a draft of, because
     * {@link at.aimon.core.memory.ObservationDraft} is now a public type in the parent package: the request object of
     * the OBSERVE tier. The two are the same idea at different altitudes — this one's four fields are a proper subset
     * of that one's eight — and the simple name belongs to the public one. A repository that maintains an ArchUnit
     * rule against two lifetimes sharing the word "Session" should not leave two observation drafts sharing theirs.
     */
    private static final class ObservationPayload {

        private final String content;
        private final ObservationType type;
        private final double confidence;
        private final List<String> sourceMessageIds;

        private ObservationPayload(Builder builder) {
            this.content = Objects.requireNonNull(builder.content, "content cannot be null");
            this.type = Objects.requireNonNull(builder.type, "type cannot be null");
            this.confidence = builder.confidence;
            this.sourceMessageIds = builder.sourceMessageIds != null
                    ? List.copyOf(builder.sourceMessageIds)
                    : List.of();
        }

        static Builder builder() {
            return new Builder();
        }

        String getContent() {
            return content;
        }

        ObservationType getType() {
            return type;
        }

        double getConfidence() {
            return confidence;
        }

        List<String> getSourceMessageIds() {
            return sourceMessageIds;
        }

        static final class Builder {
            private String content;
            private ObservationType type;
            private double confidence;
            private List<String> sourceMessageIds;

            Builder content(String content) {
                this.content = content;
                return this;
            }

            Builder type(ObservationType type) {
                this.type = type;
                return this;
            }

            Builder confidence(double confidence) {
                this.confidence = confidence;
                return this;
            }

            Builder sourceMessageIds(List<String> sourceMessageIds) {
                this.sourceMessageIds = sourceMessageIds;
                return this;
            }

            ObservationPayload build() {
                return new ObservationPayload(this);
            }
        }
    }

    private static String render(Observation saved, RedactionResult redaction) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("observation_id: ").append(saved.getId().getLocalId()).append('\n');
        sb.append("subject: ").append(saved.getSubject().key()).append('\n');
        sb.append("type: ").append(saved.getType()).append('\n');
        sb.append("confidence: ").append(String.format(Locale.ROOT, "%.2f", saved.getConfidence())).append('\n');
        sb.append("source_message_ids: ").append(saved.getSourceMessageIds()).append('\n');
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

    private static List<String> parseSourceMessageIds(ToolInput input) {
        if (!input.has("source_message_ids")) {
            return List.of();
        }
        Object raw = input.get("source_message_ids");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "source_message_ids must be an array, got " + raw.getClass().getSimpleName());
        }
        List<String> ids = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element == null) {
                continue;
            }
            if (!(element instanceof String s)) {
                throw new IllegalArgumentException(
                        "source_message_ids elements must be strings, got " + element.getClass().getSimpleName());
            }
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                ids.add(trimmed);
            }
        }
        return List.copyOf(ids);
    }
}
