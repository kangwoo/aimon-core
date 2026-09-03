package at.aimon.core.tools.memory;

import java.util.HashMap;
import java.util.LinkedHashMap;
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
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationDraft;
import at.aimon.core.memory.ObservationRecorder;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.StoreBackedPeerMemory;
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
 * defaults to {@value ObservationDraft#DEFAULT_CONFIDENCE}. <b>Present only when the backend
 * stores it</b> — see below.</li>
 * </ul>
 *
 * <h2>The confidence parameter is not always there</h2>
 *
 * <p>
 * A backend whose {@link ObservationRecorder#storesConfidence()} is {@code false}
 * drops the number on write, and the tool would then echo back a placeholder the
 * model reads as the value it chose. Rather than signal that after the fact, the
 * parameter is <b>removed from the input schema</b>: a parameter the model cannot
 * send is a round trip that cannot lose anything. The rendered result omits the
 * confidence line in the same case.
 *
 * <p>
 * This is the one place the memory tools' input schema varies by backend, and it
 * is deliberate. Freezing the schema would keep the model sending a value that is
 * discarded — which is exactly the quietly-wrong answer the capability model
 * exists to remove. The default backend stores confidence, so a deployment
 * running today sees no change.
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

    static final double DEFAULT_CONFIDENCE = ObservationDraft.DEFAULT_CONFIDENCE;

    /**
     * The observation kinds this tool advertises and accepts — the <b>single</b> source for both.
     *
     * <p>
     * {@link ObservationType} has four values; the two omitted here exist for backends whose own classification is
     * finer, and nothing in this tree produces them. Deriving the schema's {@code enum} from this list and checking
     * against the same list is what keeps "what the model is offered" and "what the tool takes" from drifting apart —
     * the drift is not hypothetical, since widening the enum turned a parser that had rejected the extra names by
     * accident into one that accepts them.
     */
    private static final List<ObservationType> ACCEPTED_TYPES = List.of(ObservationType.EXPLICIT,
            ObservationType.DEDUCTIVE);

    private static final String META_KEY_REDACTED = "redacted";
    private static final String META_KEY_REDACTION_CATEGORIES = "redaction.categories";
    private static final String META_KEY_SOURCE = "source";
    private static final String META_VALUE_SOURCE = "ObserveTool";

    private static final String BASE_DESCRIPTION = "Record a single explicit observation about a peer "
            + "(administrator / system flow). Use this to import facts the operator already knows; the deriver "
            + "remains responsible for inferences from conversations. Returns the new observation id.";

    private static final String NO_CONFIDENCE_NOTE = " This memory backend does not store a confidence score, so "
            + "none is accepted and none is reported back.";

    private static final Logger log = LoggerFactory.getLogger(ObserveTool.class);

    private final ObservationRecorder recorder;
    private final RedactionPolicy redactionPolicy;
    private final boolean storesConfidence;

    /**
     * Creates an observe tool on an {@link ObservationStore}, for callers assembling the default backend by hand.
     *
     * @param observationStore
     *            backing store (must not be null)
     */
    public ObserveTool(ObservationStore observationStore) {
        this(observationStore, null);
    }

    /**
     * Creates an observe tool on an {@link ObservationStore}, for callers assembling the default backend by hand.
     *
     * @param observationStore
     *            backing store (must not be null)
     * @param redactionPolicy
     *            optional policy applied to {@code content} before persistence; {@code null} to disable
     */
    public ObserveTool(ObservationStore observationStore, RedactionPolicy redactionPolicy) {
        this(recorderFor(observationStore), redactionPolicy);
    }

    /**
     * Creates an observe tool on the OBSERVE tier.
     *
     * @param recorder
     *            the tier observations are written through (must not be null)
     */
    public ObserveTool(ObservationRecorder recorder) {
        this(recorder, null);
    }

    /**
     * Creates an observe tool on the OBSERVE tier.
     *
     * @param recorder
     *            the tier observations are written through (must not be null)
     * @param redactionPolicy
     *            optional policy applied to {@code content} before persistence; {@code null} to disable
     */
    public ObserveTool(ObservationRecorder recorder, RedactionPolicy redactionPolicy) {
        super(TOOL_NAME, describe(recorder), createInputSchema(storesConfidence(recorder)));
        this.recorder = recorder;
        this.redactionPolicy = redactionPolicy;
        this.storesConfidence = recorder.storesConfidence();
    }

    private static ObservationRecorder recorderFor(ObservationStore observationStore) {
        Objects.requireNonNull(observationStore, "observationStore cannot be null");
        return StoreBackedPeerMemory.builder().observationStore(observationStore).build().observationRecorder()
                .orElseThrow();
    }

    private static boolean storesConfidence(ObservationRecorder recorder) {
        Objects.requireNonNull(recorder, "recorder cannot be null");
        return recorder.storesConfidence();
    }

    private static String describe(ObservationRecorder recorder) {
        return storesConfidence(recorder) ? BASE_DESCRIPTION : BASE_DESCRIPTION + NO_CONFIDENCE_NOTE;
    }

    private static Map<String, Object> createInputSchema(boolean storesConfidence) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("content",
                Map.of("type", "string", "description", "The factual sentence to record about the subject."));
        properties.put("type", Map.of("type", "string", "description",
                "Observation kind: EXPLICIT (stated directly) or DEDUCTIVE (inferred). " + "Defaults to DEDUCTIVE.",
                "enum", acceptedTypeNames()));
        if (storesConfidence) {
            properties.put("confidence", Map.of("type", "number", "description",
                    "Confidence in [0, 1]. Defaults to " + DEFAULT_CONFIDENCE + "."));
        }
        return Map.of("type", "object", "additionalProperties", false, "properties", Map.copyOf(properties), "required",
                List.of("content"));
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

            Observation saved = recorder.observe(ObservationDraft.builder().subject(subject).observer(observer)
                    .sessionId(context.get(MemoryToolContextKeys.SESSION_ID).orElse(null)).content(storedContent)
                    .type(type).confidence(confidence).metadata(buildMetadata(redaction)).build());
            log.debug("Observe recorded observation {} for subject={}", saved.getId(), subject.key());
            return ToolResult.success(render(saved, redaction, storesConfidence));

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

    private static Map<String, String> buildMetadata(RedactionResult redaction) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(META_KEY_SOURCE, META_VALUE_SOURCE);
        if (redaction.isModified()) {
            metadata.put(META_KEY_REDACTED, "true");
            metadata.put(META_KEY_REDACTION_CATEGORIES, String.join(",", redaction.getCategories()));
        }
        return Map.copyOf(metadata);
    }

    private static String render(Observation saved, RedactionResult redaction, boolean storesConfidence) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("Observation recorded\n");
        sb.append("id: ").append(saved.getId()).append('\n');
        sb.append("subject: ").append(saved.getSubject().key()).append('\n');
        sb.append("type: ").append(saved.getType()).append('\n');
        if (storesConfidence) {
            sb.append("confidence: ").append(String.format(Locale.ROOT, "%.2f", saved.getConfidence())).append('\n');
        }
        sb.append("content: ").append(saved.getContent()).append('\n');
        if (redaction.isModified()) {
            sb.append("redacted: ").append(String.join(",", redaction.getCategories())).append('\n');
        }
        return sb.toString();
    }

    private static List<String> acceptedTypeNames() {
        return ACCEPTED_TYPES.stream().map(Enum::name).toList();
    }

    /**
     * Parses the {@code type} parameter, accepting only what the schema advertises.
     *
     * <p>
     * Not {@code ObservationType.valueOf} on the raw string: that would take {@code INDUCTIVE} and
     * {@code CONTRADICTION} too, which this tool never offered. The schema gate would log the mismatch and — under
     * its default {@code WARN} mode — run the tool anyway, so a value the model invented would be persisted. The
     * reason built-in schemas are made to declare {@code additionalProperties: false} is the same one: a name the
     * tool never advertised should not be quietly honoured.
     */
    private static ObservationType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return ObservationType.DEDUCTIVE;
        }
        final String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (ObservationType accepted : ACCEPTED_TYPES) {
            if (accepted.name().equals(normalized)) {
                return accepted;
            }
        }
        throw new IllegalArgumentException(
                "unknown observation type: '" + raw + "' (expected one of " + acceptedTypeNames() + ")");
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
