package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.ConcurrentScriptedLlmClient;
import at.aimon.core.agent.impl.orca.command.OrcaCommandProvider;
import at.aimon.core.agent.impl.orca.command.OrcaCommandProviderContext;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.command.MutableCommandRegistry;

/**
 * Context creation registers tools and commands by walking two provider lists, and neither loop guards an individual
 * provider:
 *
 * <pre>
 * for (OrcaToolProvider provider : toolProviders) { provider.registerTools(toolRegistry, context); }
 * for (OrcaCommandProvider provider : commandProviders) { provider.registerCommands(commandRegistry, commandContext); }
 * </pre>
 *
 * <p>
 * That is a deliberate <b>fail-fast</b> stance — a provider that cannot register its tools indicates a
 * misconfigured deployment, and a context that silently came up missing half its tool surface would be far worse than
 * a loud bootstrap failure. These tests pin that stance so it cannot be softened into a swallow-and-continue by
 * accident, and they document the cost it carries: everything {@code doCreate} has already built by that point (the
 * registries, the compaction engine, and — when {@code withWorkflowRunnerEnabled(true)} — a live
 * {@link at.aimon.core.workflow.WorkflowRunner} owning its own executor pools) is unreachable once the exception
 * unwinds, because the half-built context is never returned and never closed.
 *
 * <p>
 * The MCP-aware {@code create(..., McpClientFactory, McpServerConfigProvider)} overload already wraps this in a
 * {@code try/catch} that closes the {@code McpClientManager} before rethrowing; the {@code WorkflowRunner} built
 * inside {@code doCreate} has no equivalent, which is the gap these tests describe.
 */
@DisplayName("OrcaAgentRuntimeFactory provider failure (fail-fast) Tests")
class OrcaAgentRuntimeFactoryProviderFailureTest {

    @TempDir
    Path tempDir;

    private OrcaAgentExecutorTestSupport support;
    private OrcaAgentExecutor agentExecutor;
    private AgentBundle agentBundle;

    @BeforeEach
    void setUp() {
        support = new OrcaAgentExecutorTestSupport(tempDir);
        agentExecutor = support.newExecutor(new ConcurrentScriptedLlmClient());
        agentBundle = AgentBundle.builder().agent(DefaultAgent.builder().name("provider-failure-agent").maxIterations(3)
                .systemPrompt("You are a test agent").build()).build();
    }

    private OrcaAgentRuntime create(List<OrcaToolProvider> toolProviders, List<OrcaCommandProvider> commandProviders) {
        return new OrcaAgentRuntimeFactory().create(AgentRuntimeId.of("agent:provider-failure"), agentExecutor, null,
                agentBundle, support.fileSystem(), null, toolProviders, commandProviders);
    }

    @Test
    @DisplayName("a healthy provider list produces a context whose tools are registered")
    void healthyProvidersProduceAContext() {
        final RecordingToolProvider first = new RecordingToolProvider();
        final RecordingCommandProvider command = new RecordingCommandProvider();

        final OrcaAgentRuntime context = create(List.of(first), List.of(command));

        assertThat(context).isNotNull();
        assertThat(first.invoked).isTrue();
        assertThat(command.invoked).isTrue();
    }

    @Test
    @DisplayName("a throwing tool provider propagates its own exception unwrapped — creation is fail-fast, never "
            + "partial")
    void throwingToolProviderPropagatesUnwrapped() {
        final OrcaToolProvider failing = (registry, context) -> {
            throw new IllegalStateException("tool provider boom");
        };

        // Unwrapped on purpose: the bootstrap stack trace must point at the offending provider, not at a generic
        // "context creation failed" wrapper.
        assertThatThrownBy(() -> create(List.of(failing), List.of())).isInstanceOf(IllegalStateException.class)
                .hasMessage("tool provider boom");
    }

    @Test
    @DisplayName("a throwing tool provider skips every later tool provider AND the whole command-provider phase")
    void throwingToolProviderSkipsLaterProvidersAndAllCommandProviders() {
        final OrcaToolProvider failing = (registry, context) -> {
            throw new IllegalStateException("tool provider boom");
        };
        final RecordingToolProvider laterTool = new RecordingToolProvider();
        final RecordingCommandProvider command = new RecordingCommandProvider();

        assertThatThrownBy(() -> create(List.of(failing, laterTool), List.of(command)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(laterTool.invoked).as("tool registration is fail-fast within the list").isFalse();
        assertThat(command.invoked).as("the command phase never starts once tool registration failed").isFalse();
    }

    @Test
    @DisplayName("providers registered before the failure still ran — the abandoned context is genuinely half-built")
    void earlierProvidersAlreadyRanWhenTheFailureHits() {
        final RecordingToolProvider earlier = new RecordingToolProvider();
        final OrcaToolProvider failing = (registry, context) -> {
            throw new IllegalStateException("tool provider boom");
        };

        assertThatThrownBy(() -> create(List.of(earlier, failing), List.of()))
                .isInstanceOf(IllegalStateException.class);

        // The caller receives only the exception: it never gets a handle on the partially-populated registries or on
        // any resource doCreate opened before this point, so it cannot close them. Adding a try/catch around the two
        // provider loops that closes the in-flight WorkflowRunner before rethrowing would fix that half.
        assertThat(earlier.invoked).isTrue();
    }

    @Test
    @DisplayName("a throwing command provider propagates and skips every later command provider")
    void throwingCommandProviderPropagatesAndSkipsLaterProviders() {
        final OrcaCommandProvider failing = (registry, context) -> {
            throw new IllegalStateException("command provider boom");
        };
        final RecordingCommandProvider later = new RecordingCommandProvider();
        final RecordingToolProvider tool = new RecordingToolProvider();

        assertThatThrownBy(() -> create(List.of(tool), List.of(failing, later)))
                .isInstanceOf(IllegalStateException.class).hasMessage("command provider boom");

        assertThat(tool.invoked).as("the tool phase completed before the command phase failed").isTrue();
        assertThat(later.invoked).as("command registration is fail-fast within the list").isFalse();
    }

    /** Records that it was reached, without registering anything. */
    private static final class RecordingToolProvider implements OrcaToolProvider {
        private boolean invoked;

        @Override
        public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
            invoked = true;
        }
    }

    /** Records that it was reached, without registering anything. */
    private static final class RecordingCommandProvider implements OrcaCommandProvider {
        private boolean invoked;

        @Override
        public void registerCommands(MutableCommandRegistry registry, OrcaCommandProviderContext context) {
            invoked = true;
        }
    }
}
