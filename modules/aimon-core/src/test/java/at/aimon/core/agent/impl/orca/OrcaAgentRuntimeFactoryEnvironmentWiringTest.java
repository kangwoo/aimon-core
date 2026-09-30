package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
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
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.ExecutionEnvironmentProvider;
import at.aimon.core.environment.impl.LocalExecutionEnvironmentProvider;
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
import at.aimon.core.workflow.RunHandle;
import at.aimon.core.workflow.RunId;
import at.aimon.core.workflow.WorkflowRunner;

/**
 * Pins how {@link OrcaAgentRuntimeFactory} wires the execution environment (execution-environment design §4.3, §6).
 *
 * <p>
 * Tool providers get no working filesystem and no shell at registration time — only the control store — so no tool
 * can capture an environment in its constructor. The runtime borrows a shared provider and never closes it or the
 * shell behind it; a provider a per-runtime function returned is the runtime's, closed with it or with a failed
 * {@code create(...)} (EE-21). And a factory without a provider refuses to build a runtime rather than letting the
 * model's tools run somewhere nobody chose.
 */
@DisplayName("OrcaAgentRuntimeFactory execution environment wiring")
class OrcaAgentRuntimeFactoryEnvironmentWiringTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("create(...) without an ExecutionEnvironmentProvider throws")
    void createWithoutProviderThrows() {
        final CapturingToolProvider provider = new CapturingToolProvider();

        assertThatThrownBy(() -> createRuntime(new OrcaAgentRuntimeFactory(), provider))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ExecutionEnvironmentProvider");
    }

    @Test
    @DisplayName("providers see the control store and no working filesystem or shell; the runtime borrows the provider")
    void providerIsBorrowedAndToolProvidersSeeOnlyTheControlStore() throws Exception {
        final VirtualShell providerShell = mock(VirtualShell.class);
        final LocalFileSystem workspace = new LocalFileSystem(
                new LocalFileSystemConfig(tempDir.resolve("workspace").toString()));
        workspace.initialize();
        final LocalExecutionEnvironmentProvider environmentProvider = LocalExecutionEnvironmentProvider.builder()
                .fileSystem(workspace).shell(providerShell).contentSearch(false).build();
        final CapturingToolProvider provider = new CapturingToolProvider();

        final OrcaAgentRuntime runtime = createRuntime(
                new OrcaAgentRuntimeFactory().withExecutionEnvironmentProvider(environmentProvider), provider);

        assertThat(provider.captured()).isNotNull();
        assertThat(provider.captured().getControlFileSystem()).isSameAs(runtime.getControlFileSystem());
        assertThat(runtime.getExecutionEnvironmentProvider()).isSameAs(environmentProvider);

        runtime.close();

        // Borrowed collaborators are not closed (docs/overview/scope-model.md §2): a background command may still run
        // in this shell after the runtime is evicted.
        verify(providerShell, never()).close();
    }

    @Test
    @DisplayName("withExecutionEnvironmentProviderFactory(...) is asked once per runtime id, and the runtime owns the answer")
    void perRuntimeProviderFactory() {
        final ClosingProvider environmentProvider = new ClosingProvider();
        final List<AgentRuntimeId> asked = new ArrayList<>();

        final OrcaAgentRuntime runtime = createRuntime(
                new OrcaAgentRuntimeFactory().withExecutionEnvironmentProviderFactory(id -> {
                    asked.add(id);
                    return environmentProvider;
                }), new CapturingToolProvider());

        assertThat(asked).containsExactly(runtime.getId());
        assertThat(runtime.getExecutionEnvironmentProvider()).isSameAs(environmentProvider);
        assertThat(environmentProvider.closed()).isZero();

        runtime.close();

        // EE-21: nobody else knows when this runtime goes away, so the runtime closes what the function gave it.
        assertThat(environmentProvider.closed()).isOne();

        // Idempotent: the provider lives outside the runtime, and a second close must not reach it again.
        runtime.close();
        assertThat(environmentProvider.closed()).isOne();
    }

    @Test
    @DisplayName("a failed create(...) closes the provider the per-runtime function returned")
    void failedCreateClosesFactoryProvider() {
        final ClosingProvider environmentProvider = new ClosingProvider();
        final OrcaAgentRuntimeFactory factory = new OrcaAgentRuntimeFactory()
                .withExecutionEnvironmentProviderFactory(id -> environmentProvider);

        assertThatThrownBy(() -> createRuntime(factory, new FailingToolProvider()))
                .isInstanceOf(IllegalStateException.class).hasMessage("tool registration failed");

        assertThat(environmentProvider.closed()).isOne();
    }

    @Test
    @DisplayName("a close failure after a failed create(...) rides along as suppressed, not instead of the cause")
    void failedCreateKeepsCauseWhenProviderCloseFails() {
        final ClosingProvider environmentProvider = new ClosingProvider(new IllegalStateException("close failed"));
        final OrcaAgentRuntimeFactory factory = new OrcaAgentRuntimeFactory()
                .withExecutionEnvironmentProviderFactory(id -> environmentProvider);

        assertThatThrownBy(() -> createRuntime(factory, new FailingToolProvider()))
                .hasMessage("tool registration failed").satisfies(e -> assertThat(e.getSuppressed())
                        .extracting(Throwable::getMessage).containsExactly("close failed"));
    }

    @Test
    @DisplayName("a failed create(...) closes the agent-scoped workflow runner it had already built")
    void failedCreateClosesWorkflowRunner() {
        final ClosingProvider environmentProvider = new ClosingProvider();
        final AtomicReference<WorkflowRunner> runner = new AtomicReference<>();
        final OrcaAgentRuntimeFactory factory = new OrcaAgentRuntimeFactory()
                .withExecutionEnvironmentProvider(environmentProvider).withWorkflowRunnerEnabled(true);

        assertThatThrownBy(() -> createRuntime(factory, (registry, context) -> {
            runner.set(context.getWorkflowRunner());
            throw new IllegalStateException("tool registration failed");
        })).hasMessage("tool registration failed");

        // A closed runner's hosting pool rejects background runs; an open one would run this script to "ran".
        assertThat(runner.get()).isNotNull();
        final RunHandle<String> handle = runner.get().runInBackground(ctx -> "ran", RunId.from("after-failure"));
        assertThat(handle.future()).failsWithin(Duration.ofSeconds(5)).withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(RejectedExecutionException.class);
    }

    @Test
    @DisplayName("a shared provider stays open when create(...) fails or the runtime closes")
    void sharedProviderIsNeverClosed() {
        final ClosingProvider environmentProvider = new ClosingProvider();
        final OrcaAgentRuntimeFactory factory = new OrcaAgentRuntimeFactory()
                .withExecutionEnvironmentProviderFactory(id -> environmentProvider)
                // The later call wins, and with it the ownership: a shared provider is borrowed.
                .withExecutionEnvironmentProvider(environmentProvider);

        assertThatThrownBy(() -> createRuntime(factory, new FailingToolProvider()))
                .hasMessage("tool registration failed");
        createRuntime(factory, new CapturingToolProvider()).close();

        assertThat(environmentProvider.closed()).isZero();
    }

    private OrcaAgentRuntime createRuntime(OrcaAgentRuntimeFactory factory, OrcaToolProvider provider) {
        final LocalFileSystem fileSystem = new LocalFileSystem(
                new LocalFileSystemConfig(tempDir.resolve("control").toString()));
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

    /** Fails the way a misconfigured tool provider would, after the environment provider was resolved. */
    private static final class FailingToolProvider implements OrcaToolProvider {

        @Override
        public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
            throw new IllegalStateException("tool registration failed");
        }
    }

    /** A closeable provider that counts its closes; it never resolves, since no execution runs here. */
    private static final class ClosingProvider implements ExecutionEnvironmentProvider, AutoCloseable {

        private final RuntimeException closeFailure;
        private int closed;

        ClosingProvider() {
            this(null);
        }

        ClosingProvider(RuntimeException closeFailure) {
            this.closeFailure = closeFailure;
        }

        @Override
        public ExecutionEnvironment resolve(EnvironmentRequest request) {
            throw new UnsupportedOperationException("not resolved in these tests");
        }

        @Override
        public void close() {
            closed++;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        int closed() {
            return closed;
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
