package at.aimon.workflow.graaljs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.workflow.graaljs.exception.JsScriptException;

/**
 * EE-45: a script is model-authored, so the attributes it puts on a step — which an execution environment provider
 * reads to place the step — are refused unless the operator allowed the key. Pinning already stopped a script from
 * changing a key a registered definition sets; this closes the other half, a key nothing registered sets.
 */
@DisplayName("A script may only set the attribute keys the operator allowed (EE-45)")
class ScriptAttributeAllowListTest {

    private final InMemorySubagentRegistry registry = new InMemorySubagentRegistry();

    ScriptAttributeAllowListTest() {
        registry.register(Subagent.builder().name("untrusted-runner").systemPrompt("registered")
                .attributes(Map.of("sandbox.slot", "isolated")).build());
    }

    @Test
    @DisplayName("by default an unregistered agentType cannot be given a sandbox.slot")
    void unregisteredAgentTypeCannotChooseItsPlacement() {
        // The escape the item describes: rename the step so that nothing is pinned, then ask for the privileged slot.
        assertThatThrownBy(() -> SubagentResolver.inline(registry)
                .resolve(type("renamed-runner").attributes(Map.of("sandbox.slot", "privileged")).build()))
                .isInstanceOf(JsScriptException.class).hasMessageContaining("agent 'renamed-runner'")
                .hasMessageContaining("'sandbox.slot'").hasMessageContaining("scriptAttributeKeys");
    }

    @Test
    @DisplayName("by default a step with no agentType cannot be given attributes either")
    void aStepWithoutAnAgentTypeCannotChooseItsPlacement() {
        assertThatThrownBy(() -> SubagentResolver.inline(registry)
                .resolve(SubagentDescriptor.builder().systemPrompt("You run things.")
                        .attributes(Map.of("sandbox.slot", "privileged")).build()))
                .isInstanceOf(JsScriptException.class).hasMessageContaining("'sandbox.slot'")
                .hasMessageContaining("scriptAttributeKeys");
    }

    @Test
    @DisplayName("by default a registered agentType cannot be given a key its definition does not set")
    void aRegisteredAgentTypeCannotGainANewKey() {
        assertThatThrownBy(() -> SubagentResolver.inline(registry)
                .resolve(type("untrusted-runner").attributes(Map.of("sandbox.network", "open")).build()))
                .isInstanceOf(JsScriptException.class).hasMessageContaining("agent 'untrusted-runner'")
                .hasMessageContaining("'sandbox.network'").hasMessageContaining("scriptAttributeKeys");
    }

    @Test
    @DisplayName("the resolver with no registry refuses script attributes too")
    void theRegistrylessResolverRefusesToo() {
        assertThatThrownBy(() -> SubagentResolver.inline().resolve(type("a").attributes(Map.of("gpu", "true")).build()))
                .isInstanceOf(JsScriptException.class).hasMessageContaining("'gpu'");
    }

    @Test
    @DisplayName("a step that sets no attributes is unaffected, registered or not")
    void stepsWithoutScriptAttributesAreUnaffected() {
        assertThat(SubagentResolver.inline(registry).resolve(type("untrusted-runner").build()).getMetadata()
                .getAttributes()).containsExactly(Map.entry("sandbox.slot", "isolated"));
        assertThat(SubagentResolver.inline(registry).resolve(type("anything").build()).getMetadata().getAttributes())
                .isEmpty();
    }

    @Test
    @DisplayName("restating a registered key with its registered value is still a no-op, allowed or not")
    void restatingAPinnedValueIsStillANoOp() {
        assertThat(SubagentResolver.inline(registry)
                .resolve(type("untrusted-runner").attributes(Map.of("sandbox.slot", "isolated")).build()).getMetadata()
                .getAttributes()).containsExactly(Map.entry("sandbox.slot", "isolated"));
    }

    private static SubagentDescriptor.Builder type(String agentType) {
        return SubagentDescriptor.builder().agentType(agentType);
    }
}
