package at.aimon.core.memory.deriver.tool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
import at.aimon.core.memory.Workspace;

/**
 * Internal deriver tool: link an existing {@link Observation} to one or more
 * source messages, augmenting its {@code sourceMessageIds}. Added beyond the
 * Honcho tool set (design doc §6.1.4) so the deriver can attribute observations
 * back to the messages that produced them when extraction is split across
 * tool calls.
 *
 * <p>
 * The tool reads the current observation, merges the supplied message ids with
 * the existing ones (preserving order, removing duplicates), and writes the
 * updated observation back through {@link ObservationStore#save}. All other
 * fields — id, subject, content, confidence, metadata — are preserved.
 *
 * <p>
 * Inputs:
 * <ul>
 * <li>{@code observation_id} (string, required) — local id (within the workspace) of the target observation.</li>
 * <li>{@code message_ids} (array of strings, required) — message identifiers to link; must be non-empty.</li>
 * </ul>
 */
public class DeriverMessageLinkTool extends AbstractTool {

    public static final String TOOL_NAME = "deriver.message.link";

    /** Workspace the observation lives in. */
    public static final ToolContextKey<Workspace> WORKSPACE_KEY = ToolContextKey.of("memory.workspace",
            Workspace.class);

    private static final Logger log = LoggerFactory.getLogger(DeriverMessageLinkTool.class);

    private final ObservationStore observationStore;

    public DeriverMessageLinkTool(ObservationStore observationStore) {
        super(TOOL_NAME,
                "Attach one or more source message ids to an existing observation. "
                        + "Use to record provenance after creating an observation in the deriver loop.",
                createInputSchema());
        this.observationStore = Objects.requireNonNull(observationStore, "observationStore cannot be null");
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties", Map.of("observation_id",
                Map.of("type", "string", "description", "Local id of the observation (without the workspace prefix)."),
                "message_ids", Map.of("type", "array", "description",
                        "Source message identifiers to attach to the observation.", "items", Map.of("type", "string"))),
                "required", List.of("observation_id", "message_ids"));
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "input cannot be null");
        Objects.requireNonNull(context, "context cannot be null");

        try {
            String localId = input.getRequiredString("observation_id").trim();
            if (localId.isEmpty()) {
                return ToolResult.error("observation_id cannot be blank");
            }
            List<String> incoming = parseMessageIds(input);
            if (incoming.isEmpty()) {
                return ToolResult.error("message_ids must contain at least one non-blank entry");
            }

            Workspace workspace = context.get(WORKSPACE_KEY).orElse(null);
            if (workspace == null) {
                return ToolResult.error(TOOL_NAME + " requires '" + WORKSPACE_KEY.name() + "' in ToolContext");
            }

            ObservationId id = ObservationId.of(workspace, localId);
            Optional<Observation> maybe = observationStore.findById(id);
            if (maybe.isEmpty()) {
                return ToolResult.error("Observation not found: " + id);
            }
            Observation existing = maybe.get();

            LinkedHashSet<String> merged = new LinkedHashSet<>(existing.getSourceMessageIds());
            merged.addAll(incoming);
            if (merged.size() == existing.getSourceMessageIds().size()) {
                log.debug("DeriverMessageLink no-op for {} (all ids already linked)", id);
                return ToolResult.success(render(existing, List.copyOf(merged), 0));
            }

            Observation updated = Observation.builder().id(existing.getId()).subject(existing.getSubject())
                    .observer(existing.getObserver()).content(existing.getContent()).type(existing.getType())
                    .confidence(existing.getConfidence()).sourceMessageIds(List.copyOf(merged))
                    .createdAt(existing.getCreatedAt()).metadata(existing.getMetadata()).build();
            Observation saved = observationStore.save(updated);
            int added = saved.getSourceMessageIds().size() - existing.getSourceMessageIds().size();
            log.debug("DeriverMessageLink added {} ids to {}", added, saved.getId());
            return ToolResult.success(render(saved, saved.getSourceMessageIds(), added));

        } catch (IllegalArgumentException e) {
            log.warn("DeriverMessageLink invalid input: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("DeriverMessageLink unexpected error: {}", e.getMessage(), e);
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    private static List<String> parseMessageIds(ToolInput input) {
        if (!input.has("message_ids")) {
            throw new IllegalArgumentException("Missing required parameter: message_ids");
        }
        Object raw = input.get("message_ids");
        if (raw == null) {
            throw new IllegalArgumentException("message_ids cannot be null");
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("message_ids must be an array, got " + raw.getClass().getSimpleName());
        }
        List<String> ids = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element == null) {
                continue;
            }
            if (!(element instanceof String s)) {
                throw new IllegalArgumentException(
                        "message_ids elements must be strings, got " + element.getClass().getSimpleName());
            }
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                ids.add(trimmed);
            }
        }
        return List.copyOf(ids);
    }

    private static String render(Observation obs, List<String> currentIds, int added) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("observation_id: ").append(obs.getId().getLocalId()).append('\n');
        sb.append("linked_ids: ").append(currentIds).append('\n');
        sb.append("added: ").append(added).append('\n');
        return sb.toString();
    }
}
