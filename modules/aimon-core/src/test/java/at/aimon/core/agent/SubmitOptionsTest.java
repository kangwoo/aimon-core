package at.aimon.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.llm.LlmCallMetadata;

/**
 * Verifies {@link SubmitOptions} value semantics: builder defaults, single-key convenience setters, defensive map
 * copies, the shared {@link SubmitOptions#empty()} singleton, and equals/hashCode contracts.
 *
 * <p>
 * SubmitOptions exists strictly to forward per-turn metadata from the {@link LiveSession} facade through to
 * {@link at.aimon.core.agent.impl.orca.OrcaAgentExecutionRequest}; the tri-state
 * {@link SubmitOptions#getUserContextInjection()
 * getUserContextInjection()} and the "unset means executor default" semantics are part of that contract and are pinned
 * here.
 */
@DisplayName("SubmitOptions builder / equality / immutability")
class SubmitOptionsTest {

    @Nested
    @DisplayName("Defaults & empty()")
    class Defaults {

        @Test
        @DisplayName("empty(): every field unset; user-context-injection override is absent")
        void emptyHasNoOverrides() {
            final SubmitOptions opts = SubmitOptions.empty();
            assertThat(opts.getPrincipal()).isEmpty();
            assertThat(opts.getSystemPromptVariables()).isEmpty();
            assertThat(opts.getExecutionAttributes()).isEmpty();
            assertThat(opts.getLlmCallMetadata()).isEmpty();
            assertThat(opts.getUserContextInjection()).isEmpty();
        }

        @Test
        @DisplayName("empty() returns a shared singleton")
        void emptyIsShared() {
            assertThat(SubmitOptions.empty()).isSameAs(SubmitOptions.empty());
        }

        @Test
        @DisplayName("builder().build() equals empty() (value-equal, not necessarily same instance)")
        void builderEmptyEqualsEmpty() {
            assertThat(SubmitOptions.builder().build()).isEqualTo(SubmitOptions.empty());
        }
    }

    @Nested
    @DisplayName("Builder field round-trip")
    class FieldRoundTrip {

        @Test
        @DisplayName("all fields round-trip through getters")
        void allFieldsRoundTrip() {
            final Principal principal = Principal.user("u-123", "alice");
            final LlmCallMetadata metadata = LlmCallMetadata.builder().traceId("trace-9").tag("tenant", "acme").build();
            final Map<String, Object> systemVars = Map.of("region", "eu", "request_id", "req-1");
            final Map<String, Object> attrs = Map.of("ab.x", true, "feature.y", "on");

            final SubmitOptions opts = SubmitOptions.builder().principal(principal).llmCallMetadata(metadata)
                    .systemPromptVariables(systemVars).executionAttributes(attrs).userContextInjection(false).build();

            assertThat(opts.getPrincipal()).contains(principal);
            assertThat(opts.getLlmCallMetadata()).contains(metadata);
            assertThat(opts.getSystemPromptVariables()).containsExactlyInAnyOrderEntriesOf(systemVars);
            assertThat(opts.getExecutionAttributes()).containsExactlyInAnyOrderEntriesOf(attrs);
            assertThat(opts.getUserContextInjection()).contains(Boolean.FALSE);
        }

        @Test
        @DisplayName("userContextInjection(true) is preserved as Optional[true]")
        void userContextInjectionTrueRoundTrip() {
            final SubmitOptions opts = SubmitOptions.builder().userContextInjection(true).build();
            assertThat(opts.getUserContextInjection()).contains(Boolean.TRUE);
        }
    }

    @Nested
    @DisplayName("Single-key convenience setters")
    class SingleKeySetters {

        @Test
        @DisplayName("systemPromptVariable() merges into a previously set map")
        void systemPromptVariableMerges() {
            final SubmitOptions opts = SubmitOptions.builder().systemPromptVariables(Map.of("a", 1))
                    .systemPromptVariable("b", 2).systemPromptVariable("c", 3).build();
            assertThat(opts.getSystemPromptVariables()).containsEntry("a", 1).containsEntry("b", 2).containsEntry("c",
                    3);
        }

        @Test
        @DisplayName("systemPromptVariable() overwrites duplicate keys")
        void systemPromptVariableOverwrites() {
            final SubmitOptions opts = SubmitOptions.builder().systemPromptVariable("k", "first")
                    .systemPromptVariable("k", "second").build();
            assertThat(opts.getSystemPromptVariables()).containsEntry("k", "second").hasSize(1);
        }

        @Test
        @DisplayName("executionAttribute() merges into a previously set map")
        void executionAttributeMerges() {
            final SubmitOptions opts = SubmitOptions.builder().executionAttributes(Map.of("a", 1))
                    .executionAttribute("b", 2).build();
            assertThat(opts.getExecutionAttributes()).containsEntry("a", 1).containsEntry("b", 2);
        }

        @Test
        @DisplayName("systemPromptVariable() rejects null key")
        void systemPromptVariableNullKey() {
            assertThatThrownBy(() -> SubmitOptions.builder().systemPromptVariable(null, "v"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("executionAttribute() rejects null key")
        void executionAttributeNullKey() {
            assertThatThrownBy(() -> SubmitOptions.builder().executionAttribute(null, "v"))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Defensive copies / immutability")
    class Immutability {

        @Test
        @DisplayName("returned systemPromptVariables map is unmodifiable")
        void systemPromptVariablesIsUnmodifiable() {
            final SubmitOptions opts = SubmitOptions.builder().systemPromptVariable("k", "v").build();
            assertThatThrownBy(() -> opts.getSystemPromptVariables().put("k2", "v2"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("returned executionAttributes map is unmodifiable")
        void executionAttributesIsUnmodifiable() {
            final SubmitOptions opts = SubmitOptions.builder().executionAttribute("k", "v").build();
            assertThatThrownBy(() -> opts.getExecutionAttributes().put("k2", "v2"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("mutating the source map after build() does not affect the built options")
        void sourceMapMutationIsolated() {
            final Map<String, Object> source = new HashMap<>();
            source.put("a", 1);
            final SubmitOptions opts = SubmitOptions.builder().systemPromptVariables(source).executionAttributes(source)
                    .build();
            source.put("b", 2);

            assertThat(opts.getSystemPromptVariables()).containsOnlyKeys("a");
            assertThat(opts.getExecutionAttributes()).containsOnlyKeys("a");
        }
    }

    @Nested
    @DisplayName("Equals / hashCode / toString")
    class ValueSemantics {

        @Test
        @DisplayName("equal field-by-field options are equal")
        void equalsByValue() {
            final SubmitOptions a = SubmitOptions.builder().principal(Principal.user("u", "n"))
                    .systemPromptVariable("k", "v").executionAttribute("a", 1).userContextInjection(false).build();
            final SubmitOptions b = SubmitOptions.builder().principal(Principal.user("u", "n"))
                    .systemPromptVariable("k", "v").executionAttribute("a", 1).userContextInjection(false).build();

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("differing user-context-injection overrides are NOT equal")
        void unequalByUserContextInjection() {
            final SubmitOptions on = SubmitOptions.builder().userContextInjection(true).build();
            final SubmitOptions off = SubmitOptions.builder().userContextInjection(false).build();
            final SubmitOptions unset = SubmitOptions.builder().build();

            assertThat(on).isNotEqualTo(off);
            assertThat(on).isNotEqualTo(unset);
            assertThat(off).isNotEqualTo(unset);
        }

        @Test
        @DisplayName("toString() includes every field name")
        void toStringIncludesFields() {
            final SubmitOptions opts = SubmitOptions.builder().principal(Principal.user("u"))
                    .systemPromptVariable("k", "v").executionAttribute("a", 1).userContextInjection(false).build();
            assertThat(opts.toString()).contains("principal").contains("systemPromptVariables")
                    .contains("executionAttributes").contains("llmCallMetadata").contains("userContextInjection");
        }
    }
}
