package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.command.CommandRegistry;
import at.aimon.core.environment.ExecutionEnvironmentProvider;
import at.aimon.core.environment.impl.LocalExecutionEnvironmentProvider;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.mcp.McpClientManager;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.workflow.WorkflowRunner;

/**
 * Verifies the agent-scoped {@link OrcaAgentRuntime#close()} contract: it must release <b>every</b>
 * agent-scoped resource it owns ({@link McpClientManager}, {@link WorkflowRunner}) but must <b>not</b> close the
 * application-scoped {@link KnowledgeStore} (whose lifetime is owned outside the context), nor the
 * {@link ExecutionEnvironmentProvider} it borrows — the provider owns the shells and filesystems behind the
 * environments it resolves (execution-environment design §4.3).
 *
 * <p>
 * The {@code WorkflowRunner} cases matter because it is the second of three owned closables: a naive
 * {@code close()} that let the first delegate's failure escape would silently leak the runner's hosting/shared thread
 * pools for the lifetime of the JVM. {@code close()} therefore isolates each delegate in its own {@code try/catch}, and
 * the tests below pin that every delegate is reached and that no failure propagates to the caller.
 *
 * <p>
 * There used to be a third owned closable, the default shell core built when the assembly supplied none (TCH-01).
 * It is gone: shells belong to the execution environment provider, which the runtime never closes.
 */
@DisplayName("OrcaAgentRuntime close() Tests")
@ExtendWith(MockitoExtension.class)
class OrcaAgentRuntimeCloseTest {

    @Mock
    private Agent agent;
    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private HookRegistry hookRegistry;
    @Mock
    private CommandRegistry commandRegistry;
    @Mock
    private SubagentRegistry subagentRegistry;
    @Mock
    private SkillRegistry skillRegistry;
    @Mock
    private VirtualFileSystem fileSystem;
    @Mock
    private McpClientManager mcpClientManager;
    @Mock
    private KnowledgeStore knowledgeStore;
    @Mock
    private WorkflowRunner workflowRunner;
    @Mock
    private VirtualShell providerShell;

    /** Builds a context over the shared mocks; {@code null} delegates are simply left unset on the builder. */
    private OrcaAgentRuntime newContext(McpClientManager mcp, WorkflowRunner runner) {
        return newContext(mcp, runner, null);
    }

    /** As above, plus the execution environment provider the runtime borrows. */
    private OrcaAgentRuntime newContext(McpClientManager mcp, WorkflowRunner runner,
            ExecutionEnvironmentProvider provider) {
        when(agent.getName()).thenReturn("close-test");
        final OrcaAgentRuntime.Builder builder = OrcaAgentRuntime.builder().id(AgentRuntimeId.from(agent)).agent(agent)
                .toolRegistry(toolRegistry).hookRegistry(hookRegistry).commandRegistry(commandRegistry)
                .subagentRegistry(subagentRegistry).skillRegistry(skillRegistry).controlFileSystem(fileSystem)
                .knowledgeStore(knowledgeStore);
        if (mcp != null) {
            builder.mcpClientManager(mcp);
        }
        if (runner != null) {
            builder.workflowRunner(runner);
        }
        if (provider != null) {
            builder.executionEnvironmentProvider(provider);
        }
        return builder.build();
    }

    @Test
    @DisplayName("close() does NOT close the application-scoped KnowledgeStore")
    void close_doesNotCloseKnowledgeStore() {
        when(agent.getName()).thenReturn("close-test");
        OrcaAgentRuntime context = OrcaAgentRuntime.builder().id(AgentRuntimeId.from(agent)).agent(agent)
                .toolRegistry(toolRegistry).hookRegistry(hookRegistry).commandRegistry(commandRegistry)
                .subagentRegistry(subagentRegistry).skillRegistry(skillRegistry).controlFileSystem(fileSystem)
                .mcpClientManager(mcpClientManager).knowledgeStore(knowledgeStore).build();

        context.close();

        verify(knowledgeStore, never()).close();
    }

    @Test
    @DisplayName("close() closes the agent-scoped McpClientManager")
    void close_closesMcpClientManager() {
        when(agent.getName()).thenReturn("close-test");
        OrcaAgentRuntime context = OrcaAgentRuntime.builder().id(AgentRuntimeId.from(agent)).agent(agent)
                .toolRegistry(toolRegistry).hookRegistry(hookRegistry).commandRegistry(commandRegistry)
                .subagentRegistry(subagentRegistry).skillRegistry(skillRegistry).controlFileSystem(fileSystem)
                .mcpClientManager(mcpClientManager).knowledgeStore(knowledgeStore).build();

        context.close();

        verify(mcpClientManager).close();
    }

    @Test
    @DisplayName("close() closes McpClientManager exactly once and never closes KnowledgeStore (regression guard)")
    void close_closesMcpButNotKnowledgeStore() {
        when(agent.getName()).thenReturn("close-test");
        OrcaAgentRuntime context = OrcaAgentRuntime.builder().id(AgentRuntimeId.from(agent)).agent(agent)
                .toolRegistry(toolRegistry).hookRegistry(hookRegistry).commandRegistry(commandRegistry)
                .subagentRegistry(subagentRegistry).skillRegistry(skillRegistry).controlFileSystem(fileSystem)
                .mcpClientManager(mcpClientManager).knowledgeStore(knowledgeStore).build();

        context.close();

        verify(mcpClientManager, org.mockito.Mockito.times(1)).close();
        verify(knowledgeStore, never()).close();
    }

    @Test
    @DisplayName("close() is a no-op when McpClientManager is absent (no NPE)")
    void close_noopWhenMcpClientManagerAbsent() {
        when(agent.getName()).thenReturn("close-test");
        OrcaAgentRuntime context = OrcaAgentRuntime.builder().id(AgentRuntimeId.from(agent)).agent(agent)
                .toolRegistry(toolRegistry).hookRegistry(hookRegistry).commandRegistry(commandRegistry)
                .subagentRegistry(subagentRegistry).skillRegistry(skillRegistry).controlFileSystem(fileSystem)
                .knowledgeStore(knowledgeStore).build();

        context.close();

        verify(knowledgeStore, never()).close();
    }

    @Test
    @DisplayName("close() closes both owned delegates; today's order is MCP-then-runner (characterization)")
    void close_closesWorkflowRunner() {
        final OrcaAgentRuntime context = newContext(mcpClientManager, workflowRunner);

        context.close();

        // Characterization, NOT an endorsement of the order. WorkflowRunner.close() is a *draining* close — it
        // settles in-flight background runs over shutdownDrain (default 5s), and those runs execute leaf subagents
        // against this context's ToolRegistry, which carries the MCP tools bound to the manager closed on the line
        // before. The dependency direction is runner -> MCP, so draining after the MCP teardown means an in-flight
        // run's remaining MCP steps resolve to an empty client and settle as tool errors instead of completing.
        // If the order is corrected to runner-then-MCP, invert this InOrder and the DisplayName in the same commit.
        final InOrder order = inOrder(mcpClientManager, workflowRunner);
        order.verify(mcpClientManager).close();
        order.verify(workflowRunner).close();
        verify(knowledgeStore, never()).close();
    }

    @Test
    @DisplayName("close() still closes the WorkflowRunner when McpClientManager.close() throws")
    void close_closesWorkflowRunnerEvenWhenMcpCloseThrows() {
        doThrow(new IllegalStateException("mcp shutdown boom")).when(mcpClientManager).close();
        final OrcaAgentRuntime context = newContext(mcpClientManager, workflowRunner);

        // A single shared try/catch would let the MCP failure skip the runner, leaking its thread pools forever.
        assertThatCode(context::close).doesNotThrowAnyException();

        verify(workflowRunner).close();
    }

    @Test
    @DisplayName("close() swallows a WorkflowRunner.close() failure instead of propagating it to the caller")
    void close_swallowsWorkflowRunnerCloseFailure() {
        doThrow(new IllegalStateException("runner shutdown boom")).when(workflowRunner).close();
        final OrcaAgentRuntime context = newContext(mcpClientManager, workflowRunner);

        // close() runs at application shutdown / agent removal, where a throw would abort the remaining teardown.
        assertThatCode(context::close).doesNotThrowAnyException();

        verify(mcpClientManager).close();
    }

    @Test
    @DisplayName("close() is a no-op when the WorkflowRunner is absent (background runs disabled)")
    void close_noopWhenWorkflowRunnerAbsent() {
        final OrcaAgentRuntime context = newContext(mcpClientManager, null);

        assertThatCode(context::close).doesNotThrowAnyException();

        verify(mcpClientManager).close();
    }

    @Test
    @DisplayName("close() never closes the execution environment provider or the shell behind it (borrowed, §4.3)")
    void close_neverClosesTheExecutionEnvironmentProvider() throws Exception {
        final LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder()
                .fileSystem(fileSystem).shell(providerShell).contentSearch(false).pathRules(List.of()).build();
        final OrcaAgentRuntime context = newContext(mcpClientManager, workflowRunner, provider);

        context.close();

        // A background Bash task may still run in this shell after the runtime is gone; only the provider's owner
        // decides when it closes.
        verify(providerShell, never()).close();
        verify(fileSystem, never()).close();
        assertThat(context.getExecutionEnvironmentProvider()).isSameAs(provider);
    }

    @Test
    @DisplayName("close() does not touch a provider that is itself AutoCloseable")
    void close_doesNotCloseAnAutoCloseableProvider() throws Exception {
        final ClosableProvider provider = org.mockito.Mockito.mock(ClosableProvider.class);
        final OrcaAgentRuntime context = newContext(mcpClientManager, workflowRunner, provider);

        assertThatCode(context::close).doesNotThrowAnyException();

        verify(provider, never()).close();
        verify(workflowRunner).close();
    }

    @Test
    @DisplayName("close() closes an owned provider last, after the MCP clients and the workflow runner")
    void close_closesAnOwnedProviderLast() throws Exception {
        final ClosableProvider provider = org.mockito.Mockito.mock(ClosableProvider.class);
        when(agent.getName()).thenReturn("close-test");
        final OrcaAgentRuntime context = OrcaAgentRuntime.builder().id(AgentRuntimeId.from(agent)).agent(agent)
                .toolRegistry(toolRegistry).hookRegistry(hookRegistry).commandRegistry(commandRegistry)
                .subagentRegistry(subagentRegistry).skillRegistry(skillRegistry).controlFileSystem(fileSystem)
                .mcpClientManager(mcpClientManager).workflowRunner(workflowRunner)
                .executionEnvironmentProvider(provider).ownsExecutionEnvironmentProvider(true).build();

        context.close();

        // The runner resolves environments from the provider, so the provider must outlive it.
        final InOrder order = inOrder(mcpClientManager, workflowRunner, provider);
        order.verify(mcpClientManager).close();
        order.verify(workflowRunner).close();
        order.verify(provider).close();
    }

    /** A provider that owns resources, as the local one does. */
    interface ClosableProvider extends ExecutionEnvironmentProvider, AutoCloseable {
    }

    @Test
    @DisplayName("close() called twice does not throw and reaches the delegates only once (close is latched)")
    void close_calledTwiceClosesOnce() {
        final OrcaAgentRuntime context = newContext(mcpClientManager, workflowRunner);

        context.close();
        assertThatCode(context::close).doesNotThrowAnyException();

        // Latched since EE-21: close() may now reach an owned execution environment provider, which lives outside this
        // object and need not tolerate a second close, so the context no longer relies on its delegates' idempotence.
        verify(mcpClientManager).close();
        verify(workflowRunner).close();
        verify(knowledgeStore, never()).close();
    }
}
