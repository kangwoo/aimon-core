package at.aimon.cli.factory;

import static at.aimon.cli.factory.AgentModelProviderCheck.vendorOfModel;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import at.aimon.core.agent.impl.AdaptiveAgentBundleLoader;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.execution.SubagentLlmDefaults;

/**
 * #104's acceptance: no subagent a shipped bundle carries sends a model name its main agent's provider does not serve.
 *
 * <p>
 * This copies the Task tool's wiring rather than going through it. {@code OrcaSubagentToolProvider} builds the Task
 * tool with the main agent's {@code LlmModel} ({@code agent.getMetadata().getModel()}), and the subagent executor turns
 * that into the request's model with {@link SubagentLlmDefaults#resolveModel(Subagent, LlmModel)}. That call decides
 * the name the request carries, so it is the call made here — and a change to where the Task tool's default model
 * comes from has to be checked against this test.
 *
 * <p>
 * "Serves" is judged the way #92's startup check judges it: the resolved name is the main agent's own, or belongs to
 * the
 * same vendor family. A bare alias such as {@code haiku} belongs to no family, so it fails here.
 */
@DisplayName("Bundled subagents' resolved models")
class BundledSubagentModelTest {

    private final AdaptiveAgentBundleLoader loader = new AdaptiveAgentBundleLoader("agents");

    private static Optional<String> resolvedName(Subagent subagent, AgentBundle bundle) {
        return SubagentLlmDefaults.resolveModel(subagent, bundle.getAgent().getMetadata().getModel()).getName();
    }

    // `terra` is left out because it ships no subagents.
    @ParameterizedTest
    @ValueSource(strings = {"default", "default-anthropic", "default-openai", "ops-agent"})
    @DisplayName("every subagent resolves to its main agent's model, or to one of the same vendor's family")
    void everySubagentStaysWithTheMainAgentsVendor(String bundleName) {
        final AgentBundle bundle = loader.load(bundleName);
        final String mainModel = bundle.getAgent().getMetadata().getModel().getName().orElseThrow();
        final List<Subagent> subagents = bundle.getSubagentRegistry().orElseThrow().getAllSubagents();

        assertThat(subagents).as("%s ships subagents", bundleName).isNotEmpty();
        assertThat(subagents).allSatisfy(subagent -> {
            final Optional<String> resolved = resolvedName(subagent, bundle);
            assertThat(resolved).as("%s's %s names a model", bundleName, subagent.getName()).isPresent();
            if (!resolved.get().equals(mainModel)) {
                assertThat(vendorOfModel(resolved.get())).as("%s's %s resolves to %s beside %s", bundleName,
                        subagent.getName(), resolved.get(), mainModel).isPresent().isEqualTo(vendorOfModel(mainModel));
            }
        });
    }

    @Test
    @DisplayName("default-anthropic's explore runs on exactly the main agent's claude-sonnet-4-5")
    void defaultAnthropicExploreInheritsTheMainModel() {
        final AgentBundle bundle = loader.load("default-anthropic");
        final Subagent explore = bundle.getSubagentRegistry().orElseThrow().getSubagent("explore").orElseThrow();

        assertThat(explore.getMetadata().getModel()).as("explore names no model of its own").isNull();
        assertThat(resolvedName(explore, bundle)).contains("claude-sonnet-4-5");
    }
}
