package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;

/**
 * TCH-01: guards the shell wiring — a {@link VirtualShell} must reach tool providers through
 * {@link OrcaToolProviderContext#getShell()} rather than being constructed inside a provider (which ArchUnit forbids,
 * since {@code at.aimon.core.shell.impl} is reachable only from the shell tree and the in-core assembler package).
 *
 * <p>
 * The other half of what these tests pin is <b>ownership</b>: whoever creates the shell closes it. An assembly that
 * passes one through {@code withShell(...)} keeps it — the runtime must not close it out from under a shell that is
 * also serving the skill hooks. When no shell is supplied, core builds the default and takes on that duty instead
 * (verified from the closing side by {@code OrcaAgentRuntimeCloseTest}).
 */
@DisplayName("OrcaAgentRuntimeFactory shell wiring and ownership (TCH-01)")
class OrcaAgentRuntimeFactoryShellWiringTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a shell supplied via withShell(...) reaches the providers and is NOT closed by the runtime")
    void assemblySuppliedShellIsBorrowedNotOwned() throws Exception {
        final VirtualShell assemblyShell = mock(VirtualShell.class);
        final CapturingToolProvider provider = new CapturingToolProvider();

        final OrcaAgentRuntime runtime = createRuntime(new OrcaAgentRuntimeFactory().withShell(assemblyShell),
                provider);

        assertThat(provider.captured()).isNotNull();
        assertThat(provider.captured().getShell()).as("providers see exactly the shell the assembly supplied")
                .isSameAs(assemblyShell);

        runtime.close();

        // Borrowed collaborators are not closed (docs/overview/scope-model.md §2). Closing this one would also break
        // the assembly's own users of it — the skill hooks share this instance in the bootstrap and CLI paths.
        verify(assemblyShell, never()).close();
    }

    @Test
    @DisplayName("with no shell supplied, core builds a default and hands it to the providers")
    void defaultShellIsBuiltWhenTheAssemblySuppliesNone() throws Exception {
        final CapturingToolProvider provider = new CapturingToolProvider();

        final OrcaAgentRuntime runtime = createRuntime(new OrcaAgentRuntimeFactory(), provider);

        assertThat(provider.captured()).isNotNull();
        assertThat(provider.captured().getShell()).as("a provider that needs a shell must not have to build one")
                .isNotNull();

        // Core owns this one, so closing the runtime closes it too; LocalShell.close() holds no resources today, so
        // the observable assertion is only that teardown stays clean.
        runtime.close();
    }

    private OrcaAgentRuntime createRuntime(OrcaAgentRuntimeFactory factory, OrcaToolProvider provider) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();

        final Agent agent = DefaultAgent.builder().name("ShellWiringAgent").maxIterations(10)
                .systemPrompt("You are a test agent").build();

        return factory.create(AgentRuntimeId.from(agent), createExecutor(), null, agent, fileSystem, null,
                List.of(provider), List.of());
    }

    private OrcaAgentExecutor createExecutor() {
        final StubLlmClient client = new StubLlmClient();
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(client);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(client, toolManager,
                hookManager);
        return new OrcaAgentExecutor(client, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, commandManager, subagentManager);
    }

    /** Registers nothing; it exists only to capture the context the factory assembles for providers. */
    private static final class CapturingToolProvider implements OrcaToolProvider {

        private final AtomicReference<OrcaToolProviderContext> captured = new AtomicReference<>();

        @Override
        public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
            captured.set(context);
        }

        OrcaToolProviderContext captured() {
            return captured.get();
        }
    }

    /** Minimal LLM client — never invoked; the factory only reads plumbing accessors off the executor. */
    private static final class StubLlmClient implements LlmClient {
        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return LlmResponse.text("unused");
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            return LlmResponse.text("unused");
        }

        @Override
        public String getProviderName() {
            return "Stub";
        }
    }
}
