package at.aimon.core.subagent.task.codec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.LlmCallMetadata;

/**
 * The JSON shape of a {@link SubmitOptions}, and of the two values it nests.
 *
 * <p>
 * <b>Why this is a class and not four private methods.</b> This mapping existed three times over — the Redis,
 * Postgres and Mongo session backends each hand-mapped it for their inbox — and a fourth consumer arrived when a
 * {@code SessionRewindPoint} started carrying the options its turn was submitted with. That is the situation
 * {@link at.aimon.core.agent.session.store.SessionRecordCodec} exists to prevent for records: copies of one mapping
 * agree only by coincidence.
 *
 * <p>
 * Two of the three are now gone. The Redis and Postgres inboxes call this class, which cost nothing on the wire
 * because the field names and shapes here were already identical to what they wrote. The Mongo inbox cannot: its
 * currency is a BSON {@code org.bson.Document} rather than an {@code ObjectNode}, so it keeps its own encode and
 * decode and shares only the field names published below. What is left is therefore one shared mapping and one
 * deliberate second representation, not three copies.
 *
 * <p>
 * It lives beside {@link JsonSessionSnapshotCodec} because that is its first caller here and because the reverse
 * direction would be a package cycle — {@code agent.session.store} already depends on this package. The package name
 * records a first consumer, not a constraint on later ones.
 *
 * <p>
 * <b>Set is distinguished from unset.</b> Every field is written only when present, so options that never named a
 * value decode as still not naming one. That matters for {@code llmCallMetadata} in particular: an absent one is
 * re-derived from the agent and session at execution time, while a present one is honoured as given, and turning the
 * first into the second would pin a component and trace id that were meant to be computed.
 *
 * <p>
 * <b>Heterogeneous maps are best-effort.</b> {@code systemPromptVariables} and {@code executionAttributes} are
 * {@code Map<String, Object>}, so Jackson's default scalar mapping (String, Number, Boolean, Map, List) round-trips
 * cleanly while a custom POJO comes back as a nested {@code Map}. This is the same bargain
 * {@link JsonSessionSnapshotCodec} already strikes for a tool use's arbitrary input map, and it is stated rather than
 * hidden because a caller storing domain objects in those maps should know they are values, not identities, by the
 * time they come back.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class SubmitOptionsCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> OBJECT_MAP_TYPE = new TypeReference<>() {
    };

    /*
     * The field names, public because one representation of this shape cannot be expressed here: the Mongo inbox
     * writes a BSON `org.bson.Document`, not an ObjectNode, so it keeps its own encode and decode. Sharing the names
     * means the two representations can disagree about a *name* only if someone changes it in one place, and the
     * three sets below let that codec's test assert its key sets against these rather than against literals it also
     * owns.
     *
     * This is deliberately weaker than what Redis and Postgres get, which is this class itself. A field *added* to
     * SubmitOptions still has to be handled twice; the sets only make the second omission visible, by failing the
     * Mongo key-set test rather than surfacing as a value silently missing from a routed turn.
     */
    public static final String FIELD_PRINCIPAL = "principal";
    public static final String FIELD_SYSTEM_PROMPT_VARIABLES = "systemPromptVariables";
    public static final String FIELD_EXECUTION_ATTRIBUTES = "executionAttributes";
    public static final String FIELD_LLM_CALL_METADATA = "llmCallMetadata";
    public static final String FIELD_USER_CONTEXT_INJECTION = "userContextInjection";

    public static final String FIELD_TYPE = "type";
    public static final String FIELD_ID = "id";
    public static final String FIELD_DISPLAY_NAME = "displayName";

    public static final String FIELD_COMPONENT = "component";
    public static final String FIELD_PARENT_COMPONENT = "parentComponent";
    public static final String FIELD_FEATURE = "feature";
    public static final String FIELD_TRACE_ID = "traceId";
    public static final String FIELD_TAGS = "tags";

    /** Every key {@link #encode(SubmitOptions)} can write at the top level — one per {@link SubmitOptions} property. */
    public static final Set<String> TOP_LEVEL_FIELDS = Set.of(FIELD_PRINCIPAL, FIELD_SYSTEM_PROMPT_VARIABLES,
            FIELD_EXECUTION_ATTRIBUTES, FIELD_LLM_CALL_METADATA, FIELD_USER_CONTEXT_INJECTION);

    /** Every key a nested {@link Principal} carries. */
    public static final Set<String> PRINCIPAL_FIELDS = Set.of(FIELD_TYPE, FIELD_ID, FIELD_DISPLAY_NAME);

    /** Every key a nested {@link LlmCallMetadata} can carry — {@link #FIELD_PRINCIPAL} included, as it nests one. */
    public static final Set<String> LLM_CALL_METADATA_FIELDS = Set.of(FIELD_COMPONENT, FIELD_PARENT_COMPONENT,
            FIELD_FEATURE, FIELD_PRINCIPAL, FIELD_TRACE_ID, FIELD_TAGS);

    private SubmitOptionsCodec() {
        // Utility class
    }

    /**
     * Encodes the options, or returns {@code null} when they carry nothing.
     *
     * <p>
     * Returning {@code null} for empty options lets a caller omit the key entirely, so a turn submitted without any
     * per-turn metadata — the common case, and every turn the CLI submits — encodes exactly as it did before options
     * were written at all.
     *
     * @param options
     *            the options to encode (must not be null)
     * @return the encoded subtree, or null when {@code options} equals {@link SubmitOptions#empty()}
     */
    public static ObjectNode encode(SubmitOptions options) {
        return encode(options, MAPPER);
    }

    /**
     * Encodes the options with a caller-supplied mapper.
     *
     * <p>
     * <b>Why the mapper is a parameter and not just the field above.</b> Two of the five fields are
     * {@code Map<String, Object>}, so the mapper's configuration is part of what reaches the wire — a registered
     * {@code JavaTimeModule} is the difference between an {@code Instant} in {@code executionAttributes} landing as an
     * ISO-8601 string and landing as an object. The session inboxes take their mapper from the application
     * ({@code RedisSessionInbox} and {@code PostgresSessionInbox} both expose a constructor for it, and both default to
     * one with that module registered), and they encode the rest of the envelope with it. Had converging them onto this
     * class hard-wired the private mapper, one subtree of a document would have started following different rules from
     * the document around it, for temporal values only, with nothing to say so.
     *
     * @param options
     *            the options to encode (must not be null)
     * @param mapper
     *            the mapper to build nodes and convert heterogeneous map values with (must not be null)
     * @return the encoded subtree, or null when {@code options} equals {@link SubmitOptions#empty()}
     */
    public static ObjectNode encode(SubmitOptions options, ObjectMapper mapper) {
        Objects.requireNonNull(options, "options cannot be null");
        Objects.requireNonNull(mapper, "mapper cannot be null");
        if (options.equals(SubmitOptions.empty())) {
            return null;
        }
        final ObjectNode node = mapper.createObjectNode();
        options.getPrincipal().ifPresent(p -> node.set(FIELD_PRINCIPAL, encodePrincipal(p, mapper)));
        if (!options.getSystemPromptVariables().isEmpty()) {
            node.set(FIELD_SYSTEM_PROMPT_VARIABLES, mapper.valueToTree(options.getSystemPromptVariables()));
        }
        if (!options.getExecutionAttributes().isEmpty()) {
            node.set(FIELD_EXECUTION_ATTRIBUTES, mapper.valueToTree(options.getExecutionAttributes()));
        }
        options.getLlmCallMetadata()
                .ifPresent(m -> node.set(FIELD_LLM_CALL_METADATA, encodeLlmCallMetadata(m, mapper)));
        options.getUserContextInjection().ifPresent(b -> node.put(FIELD_USER_CONTEXT_INJECTION, b));
        return node;
    }

    /**
     * Decodes options previously written by {@link #encode(SubmitOptions)}.
     *
     * <p>
     * <b>Malformed content is an exception, not an empty result.</b> An absent subtree means the turn carried no
     * options and yields {@link SubmitOptions#empty()}; a subtree that is present but cannot be read means the
     * document says something this cannot honour, and silently returning empty would run a turn as nobody when it was
     * recorded as running under a principal. Callers that would rather lose the options than the document — a rewind
     * point, whose whole value is one turn's retry — catch this and decide that for themselves.
     *
     * @param node
     *            the subtree, or null / non-object when the key was absent
     * @return the decoded options, never null — an absent subtree yields {@link SubmitOptions#empty()}, which is what
     *         a turn submitted without per-turn metadata had
     * @throws SessionSnapshotCodecException
     *             if the subtree is present but cannot be reconstructed
     */
    public static SubmitOptions decode(JsonNode node) {
        return decode(node, MAPPER);
    }

    /**
     * Decodes options with a caller-supplied mapper.
     *
     * @param node
     *            the subtree, or null / non-object when the key was absent
     * @param mapper
     *            the mapper to convert heterogeneous map values with (must not be null) — see
     *            {@link #encode(SubmitOptions, ObjectMapper)} for why it is a parameter
     * @return the decoded options, never null — an absent subtree yields {@link SubmitOptions#empty()}
     * @throws SessionSnapshotCodecException
     *             if the subtree is present but cannot be reconstructed
     */
    public static SubmitOptions decode(JsonNode node, ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper cannot be null");
        if (node == null || !node.isObject()) {
            return SubmitOptions.empty();
        }
        final SubmitOptions.Builder builder = SubmitOptions.builder();
        final JsonNode principal = node.get(FIELD_PRINCIPAL);
        if (principal != null && principal.isObject()) {
            builder.principal(decodePrincipal(principal));
        }
        final JsonNode variables = node.get(FIELD_SYSTEM_PROMPT_VARIABLES);
        if (variables != null && variables.isObject()) {
            builder.systemPromptVariables(mapper.convertValue(variables, OBJECT_MAP_TYPE));
        }
        final JsonNode attributes = node.get(FIELD_EXECUTION_ATTRIBUTES);
        if (attributes != null && attributes.isObject()) {
            builder.executionAttributes(mapper.convertValue(attributes, OBJECT_MAP_TYPE));
        }
        final JsonNode metadata = node.get(FIELD_LLM_CALL_METADATA);
        if (metadata != null && metadata.isObject()) {
            builder.llmCallMetadata(decodeLlmCallMetadata(metadata));
        }
        final JsonNode injection = node.get(FIELD_USER_CONTEXT_INJECTION);
        if (injection != null && injection.isBoolean()) {
            builder.userContextInjection(injection.asBoolean());
        }
        return builder.build();
    }

    private static ObjectNode encodePrincipal(Principal principal, ObjectMapper mapper) {
        final ObjectNode node = mapper.createObjectNode();
        node.put(FIELD_TYPE, principal.getType().name());
        node.put(FIELD_ID, principal.getId());
        node.put(FIELD_DISPLAY_NAME, principal.getDisplayName());
        return node;
    }

    /**
     * Rebuilds a principal, turning every way this can fail into the codec's own exception.
     *
     * <p>
     * {@link Principal} rejects a null id or display name and {@code Type.valueOf} rejects an unknown name, so a
     * subtree missing a field or carrying a type this build does not know would otherwise surface as a raw
     * {@code NullPointerException} or {@code IllegalArgumentException} from inside a decode. Callers catch the codec's
     * exception to decide what a document they cannot read is worth; they cannot be asked to catch those.
     */
    private static Principal decodePrincipal(JsonNode node) {
        try {
            return Principal.builder().type(Principal.Type.valueOf(requiredText(node, FIELD_TYPE)))
                    .id(requiredText(node, FIELD_ID)).displayName(requiredText(node, FIELD_DISPLAY_NAME)).build();
        } catch (IllegalArgumentException | NullPointerException | SessionSnapshotCodecException e) {
            // Re-wrapped even when it is already this type: "Missing field 'displayName'" does not say which of the
            // document's several principals it came from, and a decode failure is read by someone who has only the
            // message.
            throw new SessionSnapshotCodecException("Cannot reconstruct the principal: " + e.getMessage(), e);
        }
    }

    private static ObjectNode encodeLlmCallMetadata(LlmCallMetadata metadata, ObjectMapper mapper) {
        final ObjectNode node = mapper.createObjectNode();
        metadata.getComponent().ifPresent(v -> node.put(FIELD_COMPONENT, v));
        metadata.getParentComponent().ifPresent(v -> node.put(FIELD_PARENT_COMPONENT, v));
        metadata.getFeature().ifPresent(v -> node.put(FIELD_FEATURE, v));
        metadata.getPrincipal().ifPresent(p -> node.set(FIELD_PRINCIPAL, encodePrincipal(p, mapper)));
        metadata.getTraceId().ifPresent(v -> node.put(FIELD_TRACE_ID, v));
        if (!metadata.getTags().isEmpty()) {
            final ObjectNode tags = node.putObject(FIELD_TAGS);
            metadata.getTags().forEach(tags::put);
        }
        return node;
    }

    private static LlmCallMetadata decodeLlmCallMetadata(JsonNode node) {
        try {
            return buildLlmCallMetadata(node);
        } catch (IllegalArgumentException | NullPointerException | SessionSnapshotCodecException e) {
            throw new SessionSnapshotCodecException("Cannot reconstruct the LLM call metadata: " + e.getMessage(), e);
        }
    }

    private static LlmCallMetadata buildLlmCallMetadata(JsonNode node) {
        final LlmCallMetadata.Builder builder = LlmCallMetadata.builder();
        final String component = text(node, FIELD_COMPONENT);
        if (component != null) {
            builder.component(component);
        }
        final String parentComponent = text(node, FIELD_PARENT_COMPONENT);
        if (parentComponent != null) {
            builder.parentComponent(parentComponent);
        }
        final String feature = text(node, FIELD_FEATURE);
        if (feature != null) {
            builder.feature(feature);
        }
        final JsonNode principal = node.get(FIELD_PRINCIPAL);
        if (principal != null && principal.isObject()) {
            builder.principal(decodePrincipal(principal));
        }
        final String traceId = text(node, FIELD_TRACE_ID);
        if (traceId != null) {
            builder.traceId(traceId);
        }
        final JsonNode tags = node.get(FIELD_TAGS);
        if (tags != null && tags.isObject()) {
            final Map<String, String> decoded = new LinkedHashMap<>();
            tags.fields().forEachRemaining(entry -> decoded.put(entry.getKey(), entry.getValue().asText()));
            builder.tags(decoded);
        }
        return builder.build();
    }

    private static String text(JsonNode node, String field) {
        final JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String requiredText(JsonNode node, String field) {
        final String value = text(node, field);
        if (value == null) {
            throw new SessionSnapshotCodecException("Missing field '" + field + "'");
        }
        return value;
    }
}
