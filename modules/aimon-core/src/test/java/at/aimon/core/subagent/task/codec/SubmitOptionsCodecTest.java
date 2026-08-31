package at.aimon.core.subagent.task.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.LlmCallMetadata;

/**
 * Pins the encoding three session backends already write and a rewind point now reuses.
 *
 * <p>
 * The distinction that carries the most weight here is <b>set versus unset</b>. A turn that never named an
 * {@code llmCallMetadata} has one re-derived from its agent and session at execution time; a turn that named one is
 * honoured as given. An encoding that could not tell those apart would turn every retried turn into the second kind,
 * pinning a component and a trace id that were meant to be computed — which is why the assertions below check for
 * absence as often as for value.
 */
@DisplayName("SubmitOptionsCodec")
class SubmitOptionsCodecTest {

    @Test
    @DisplayName("empty options encode to nothing at all, so the key can be omitted")
    void emptyOptionsEncodeToNull() {
        assertThat(SubmitOptionsCodec.encode(SubmitOptions.empty())).isNull();
        assertThat(SubmitOptionsCodec.decode(null)).isEqualTo(SubmitOptions.empty());
    }

    @Test
    @DisplayName("every field round-trips, nested values included")
    void everyFieldRoundTrips() {
        final SubmitOptions options = SubmitOptions.builder().principal(Principal.user("operator-7"))
                .systemPromptVariables(Map.of("tenant", "acme", "retries", 3))
                .executionAttributes(Map.of("ticket", "INC-42"))
                .llmCallMetadata(LlmCallMetadata.builder().component("incident-bot").feature("triage")
                        .traceId("trace-1").principal(Principal.user("operator-7")).tags(Map.of("env", "prod")).build())
                .userContextInjection(false).build();

        assertThat(SubmitOptionsCodec.decode(SubmitOptionsCodec.encode(options))).isEqualTo(options);
    }

    /**
     * The one that matters most. Naming nothing must come back as naming nothing, not as naming the defaults.
     */
    @Test
    @DisplayName("a field that was never set comes back unset, not defaulted")
    void unsetFieldsStayUnset() {
        final SubmitOptions onlyPrincipal = SubmitOptions.builder().principal(Principal.user("operator-7")).build();

        final SubmitOptions decoded = SubmitOptionsCodec.decode(SubmitOptionsCodec.encode(onlyPrincipal));

        assertThat(decoded.getLlmCallMetadata()).as("an unset metadata must not materialise as a default one")
                .isEmpty();
        assertThat(decoded.getUserContextInjection()).as("the tri-state must stay tri-state").isEmpty();
        assertThat(decoded.getSystemPromptVariables()).isEmpty();
        assertThat(decoded.getExecutionAttributes()).isEmpty();
        assertThat(decoded).isEqualTo(onlyPrincipal);
    }

    /**
     * The stated bargain, tested so it stays stated. Jackson's scalar mapping round-trips; anything else comes back
     * as the JSON shape it was written as. A caller keeping domain objects in these maps gets values, not identities.
     */
    @Test
    @DisplayName("heterogeneous map values round-trip as JSON shapes, which is documented rather than hidden")
    void heterogeneousMapValuesRoundTripAsJson() {
        final SubmitOptions options = SubmitOptions.builder()
                .executionAttributes(Map.of("text", "v", "number", 7, "flag", true, "list", List.of("a", "b"))).build();

        final Map<String, Object> decoded = SubmitOptionsCodec.decode(SubmitOptionsCodec.encode(options))
                .getExecutionAttributes();

        assertThat(decoded).containsEntry("text", "v").containsEntry("number", 7).containsEntry("flag", true)
                .containsEntry("list", List.of("a", "b"));
    }

    /**
     * Malformed content surfaces as this codec's own exception, never as a raw {@code NullPointerException} from
     * {@link Principal}'s builder or an {@code IllegalArgumentException} from {@code Type.valueOf}. Callers catch the
     * codec exception to decide what an unreadable document costs them; a rewind point decides it costs one retry,
     * and it can only make that decision about an exception it is able to catch.
     */
    @Test
    @DisplayName("a principal that cannot be rebuilt fails as a codec exception, not a raw NPE")
    void aMalformedPrincipalFailsAsACodecException() throws Exception {
        final ObjectMapper mapper = new ObjectMapper();
        final JsonNode noDisplayName = mapper.readTree("{\"principal\":{\"type\":\"USER\",\"id\":\"u\"}}");
        final JsonNode unknownType = mapper
                .readTree("{\"principal\":{\"type\":\"ROBOT\",\"id\":\"u\",\"displayName\":\"u\"}}");

        assertThatThrownBy(() -> SubmitOptionsCodec.decode(noDisplayName))
                .isInstanceOf(SessionSnapshotCodecException.class).hasMessageContaining("principal");
        assertThatThrownBy(() -> SubmitOptionsCodec.decode(unknownType))
                .isInstanceOf(SessionSnapshotCodecException.class).hasMessageContaining("principal");
    }

    /** An unreadable subtree is the options a turn without any had — never a failure that loses the record. */
    @Test
    @DisplayName("a non-object subtree decodes as empty options")
    void aNonObjectSubtreeDecodesAsEmpty() {
        final ObjectNode encoded = SubmitOptionsCodec
                .encode(SubmitOptions.builder().principal(Principal.user("u")).build());

        assertThat(encoded).isNotNull();
        assertThat(SubmitOptionsCodec.decode(encoded.get("principal").get("id"))).isEqualTo(SubmitOptions.empty());
    }

    /**
     * A fully populated instance — every property of {@link SubmitOptions} named, and every property of the two types
     * it nests named inside it.
     *
     * <p>
     * Shared by the completeness tests below, which is the point: they assert that this leaves nothing at its default,
     * so a property added to any of the three types fails here first and cannot reach the round-trip assertions
     * looking healthy because the fixture never mentioned it.
     */
    private static SubmitOptions fullyPopulated() {
        return SubmitOptions.builder().principal(Principal.user("operator-7", "Operator Seven"))
                .systemPromptVariables(Map.of("tenant", "acme")).executionAttributes(Map.of("ticket", "INC-42"))
                .llmCallMetadata(LlmCallMetadata.builder().component("incident-bot").parentComponent("dispatcher")
                        .feature("triage").principal(Principal.user("operator-7", "Operator Seven")).traceId("trace-1")
                        .tags(Map.of("env", "prod")).build())
                .userContextInjection(false).build();
    }

    private static Set<String> declaredFieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(Field::getName).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The guard against the failure this codec exists to prevent, one step earlier than a round-trip can see it.
     *
     * <p>
     * A round-trip test cannot fail for a field nobody wrote: add a sixth property to {@link SubmitOptions}, encode
     * an instance that does not set it, decode it, and the two are still equal. This compares the declared properties
     * of the three encoded types against the name sets the codec publishes, so the omission is caught by the type
     * rather than by whether a fixture happened to mention it.
     *
     * <p>
     * It holds because the wire names were chosen to equal the Java field names. That is not an accident worth losing
     * quietly — if a field ever has to be stored under a different name, this test is where that decision surfaces,
     * and it should be given an explicit exception rather than deleted.
     */
    @Test
    @DisplayName("the published field-name sets cover every property of the three types encoded")
    void publishedFieldNamesCoverEveryProperty() {
        assertThat(SubmitOptionsCodec.TOP_LEVEL_FIELDS).isEqualTo(declaredFieldNames(SubmitOptions.class));
        assertThat(SubmitOptionsCodec.PRINCIPAL_FIELDS).isEqualTo(declaredFieldNames(Principal.class));
        assertThat(SubmitOptionsCodec.LLM_CALL_METADATA_FIELDS).isEqualTo(declaredFieldNames(LlmCallMetadata.class));
    }

    /**
     * The other half: the names are published, and this is what the encoder actually writes when asked for everything.
     * The Mongo inbox — the one representation that cannot call this class — asserts its own key sets against the same
     * three constants, so the two shapes are compared to one thing rather than to each other.
     */
    @Test
    @DisplayName("a fully populated instance writes every published key, and nothing else")
    void aFullyPopulatedInstanceWritesEveryPublishedKey() {
        final ObjectNode encoded = SubmitOptionsCodec.encode(fullyPopulated());

        assertThat(encoded.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet()))
                .isEqualTo(SubmitOptionsCodec.TOP_LEVEL_FIELDS);
        assertThat(encoded.get(SubmitOptionsCodec.FIELD_PRINCIPAL).properties().stream().map(Map.Entry::getKey)
                .collect(Collectors.toSet())).isEqualTo(SubmitOptionsCodec.PRINCIPAL_FIELDS);
        assertThat(encoded.get(SubmitOptionsCodec.FIELD_LLM_CALL_METADATA).properties().stream().map(Map.Entry::getKey)
                .collect(Collectors.toSet())).isEqualTo(SubmitOptionsCodec.LLM_CALL_METADATA_FIELDS);
    }

    /**
     * Why {@link SubmitOptionsCodec#encode(SubmitOptions, ObjectMapper)} takes a mapper at all.
     *
     * <p>
     * {@code systemPromptVariables} and {@code executionAttributes} are {@code Map<String, Object>}, so what reaches
     * the wire depends on the mapper's configuration rather than on this class. The session inboxes let the
     * application supply a mapper and encode the rest of the envelope with it; had converging them onto this class
     * hard-wired the private one, one subtree of a document would have started following different rules from the
     * document around it, for temporal values only, with nothing to say so.
     *
     * <p>
     * The knob used here is {@code WRITE_DATES_AS_TIMESTAMPS} on a {@code java.util.Date}, because plain
     * {@code jackson-databind} is all this module depends on. In production the difference arrives as a registered
     * {@code JavaTimeModule} — which is what both inboxes default to, and what
     * {@code RedisInboundMessageCodecTest} checks end-to-end on the classpath that actually has it.
     */
    @Test
    @DisplayName("the caller's mapper configuration reaches heterogeneous map values")
    void theCallersMapperConfigurationReachesHeterogeneousMapValues() {
        final Date deadline = Date.from(Instant.parse("2026-08-28T09:15:00Z"));
        final SubmitOptions options = SubmitOptions.builder().executionAttributes(Map.of("deadline", deadline)).build();

        final ObjectNode asIsoString = SubmitOptionsCodec.encode(options,
                new ObjectMapper().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
        assertThat(asIsoString.get(SubmitOptionsCodec.FIELD_EXECUTION_ATTRIBUTES).get("deadline").asText())
                .isEqualTo("2026-08-28T09:15:00.000+00:00");

        final ObjectNode asTimestamp = SubmitOptionsCodec.encode(options, new ObjectMapper());
        assertThat(asTimestamp.get(SubmitOptionsCodec.FIELD_EXECUTION_ATTRIBUTES).get("deadline").isNumber())
                .as("the default mapper writes the same value as epoch millis — the difference the parameter carries")
                .isTrue();
    }
}
