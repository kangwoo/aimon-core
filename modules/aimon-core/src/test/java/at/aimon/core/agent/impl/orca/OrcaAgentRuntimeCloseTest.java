package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.command.CommandRegistry;
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
 * agent-scoped resource it owns ({@link McpClientManager}, {@link WorkflowRunner}, and the default
 * {@link VirtualShell} when core built it) but must <b>not</b> close the application-scoped {@link KnowledgeStore}
 * (whose lifetime is owned outside the context).
 *
 * <p>
 * The {@code WorkflowRunner} cases matter because it is the second of three owned closables: a naive
 * {@code close()} that let the first delegate's failure escape would silently leak the runner's hosting/shared thread
 * pools for the lifetime of the JVM. {@code close()} therefore isolates each delegate in its own {@code try/catch}, and
 * the tests below pin that every delegate is reached and that no failure propagates to the caller.
 *
 * <p>
 * The shell is the third, and it is stored <em>only</em> when core created it (TCH-01). A shell handed in through
 * {@code OrcaAgentRuntimeFactory.withShell(...)} belongs to the assembly, so it never reaches this builder at all —
 * that half of the ownership rule is pinned by {@code OrcaAgentRuntimeFactoryShellWiringTest}.
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
    private VirtualShell ownedShell;

    /** Builds a context over the shared mocks; {@code null} delegates are simply left unset on the builder. */
    private OrcaAgentRuntime newContext(McpClientManager mcp, WorkflowRunner runner) {
        return newContext(mcp, runner, null);
    }

    /** As above, plus the shell core built for itself — {@code null} models a shell borrowed from the assembly. */
    private OrcaAgentRuntime newContext(McpClientManager mcp, WorkflowRunner runner, VirtualShell shell) {
        when(agent.getName()).thenReturn("close-test");
        final OrcaAgentRuntime.Builder builder = OrcaAgentRuntime.builder()
                .id(AgentRuntimeId.from(agent)).agent(agent).toolRegistry(toolRegistry)
                .hookRegistry(hookRegistry).commandRegistry(commandRegistry).subagentRegistry(subagentRegistry)
                .skillRegistry(skillRegistry).fileSystem(fileSystem).environment(Environment.createDefault())
                .knowledgeStore(knowledgeStore);
        if (mcp != null) {
            builder.mcpClientManager(mcp);
        }
        if (runner != null) {
            builder.workflowRunner(runner);
        }
        if (shell != null) {
            builder.ownedShell(shell);
        }
        return builder.build();
    }

    @Test
    @DisplayName("close() does NOT close the application-scoped KnowledgeStore")
    void close_doesNotCloseKnowledgeStore() {
        when(agent.getName()).thenReturn("close-test");
        OrcaAgentRuntime context = OrcaAgentRuntime.builder()
                .id(AgentRuntimeId.from(agent)).agent(agent).toolRegistry(toolRegistry)
                .hookRegistry(hookRegistry).commandRegistry(commandRegistry).subagentRegistry(subagentRegistry)
                .skillRegistry(skillRegistry).fileSystem(fileSystem).environment(Environment.createDefault())
                .mcpClientManager(mcpClientManager).knowledgeStore(knowledgeStore).build();

        context.close();

        verify(knowledgeStore, never()).close();
    }

    @Test
    @DisplayName("close() closes the agent-scoped McpClientManager")
    void close_closesMcpClientManager() {
        when(agent.getName()).thenReturn("close-test");
        OrcaAgentRuntime context = OrcaAgentRuntime.builder()
                .id(AgentRuntimeId.from(agent)).agent(agent).toolRegistry(toolRegistry)
                .hookRegistry(hookRegistry).commandRegistry(commandRegistry).subagentRegistry(subagentRegistry)
                .skillRegistry(skillRegistry).fileSystem(fileSystem).environment(Environment.createDefault())
                .mcpClientManager(mcpClientManager).knowledgeStore(knowledgeStore).build();

        context.close();

        verify(mcpClientManager).close();
    }

    @Test
    @DisplayName("close() closes McpClientManager exactly once and never closes KnowledgeStore (regression guard)")
    void close_closesMcpButNotKnowledgeStore() {
        when(agent.getName()).thenReturn("close-test");
        OrcaAgentRuntime context = OrcaAgentRuntime.builder()
                .id(AgentRuntimeId.from(agent)).agent(agent).toolRegistry(toolRegistry)
                .hookRegistry(hookRegistry).commandRegistry(commandRegistry).subagentRegistry(subagentRegistry)
                .skillRegistry(skillRegistry).fileSystem(fileSystem).environment(Environment.createDefault())
                .mcpClientManager(mcpClientManager).knowledgeStore(knowledgeStore).build();

        context.close();

        verify(mcpClientManager, org.mockito.Mockito.times(1)).close();
        verify(knowledgeStore, never()).close();
    }

    @Test
    @DisplayName("close() is a no-op when McpClientManager is absent (no NPE)")
    void close_noopWhenMcpClientManagerAbsent() {
        when(agent.getName()).thenReturn("close-test");
        OrcaAgentRuntime context = OrcaAgentRuntime.builder()
                .id(AgentRuntimeId.from(agent)).agent(agent).toolRegistry(toolRegistry)
                .hookRegistry(hookRegistry).commandRegistry(commandRegistry).subagentRegistry(subagentRegistry)
                .skillRegistry(skillRegistry).fileSystem(fileSystem).environment(Environment.createDefault())
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
    @DisplayName("close() closes the default shell core created for itself (TCH-01)")
    void close_closesOwnedShell() throws Exception {
        final OrcaAgentRuntime context = newContext(mcpClientManager, workflowRunner, ownedShell);

        context.close();

        // There is no fan-out over the AgentScoped marker: the shell is closed only because it was added to close()'s
        // hardcoded list by name. Drop it from that list and nothing else would notice — hence this guard.
        verify(ownedShell).close();
    }

    @Test
    @DisplayName("close() still closes the shell when McpClientManager and WorkflowRunner both throw")
    void close_closesOwnedShellEvenWhenEarlierDelegatesThrow() throws Exception {
        doThrow(new IllegalStateException("mcp shutdown boom")).when(mcpClientManager).close();
        doThrow(new IllegalStateException("runner shutdown boom")).when(workflowRunner).close();
        final OrcaAgentRuntime context = newContext(mcpClientManager, workflowRunner, ownedShell);

        // The shell is last in the list, so it is the delegate a shared try/catch would strand.
        assertThatCode(context::close).doesNotThrowAnyException();

        verify(ownedShell).close();
    }

    @Test
    @DisplayName("close() swallows a shell close() failure instead of propagating it to the caller")
    void close_swallowsOwnedShellCloseFailure() throws Exception {
        doThrow(new IllegalStateException("shell shutdown boom")).when(ownedShell).close();
        final OrcaAgentRuntime context = newContext(mcpClientManager, workflowRunner, ownedShell);

        assertThatCode(context::close).doesNotThrowAnyException();

        verify(workflowRunner).close();
    }

    @Test
    @DisplayName("close() is a no-op when the shell was supplied by the assembly (borrowed, so never stored)")
    void close_noopWhenShellIsBorrowed() {
        final OrcaAgentRuntime context = newContext(mcpClientManager, workflowRunner, null);

        assertThatCode(context::close).doesNotThrowAnyException();

        verify(workflowRunner).close();
    }

    @Test
    @DisplayName("close() called twice does not throw and delegates each time (close is pass-through, not latched)")
    void close_calledTwiceDelegatesEachTime() {
        final OrcaAgentRuntime context = newContext(mcpClientManager, workflowRunner);

        context.close();
        assertThatCode(context::close).doesNotThrowAnyException();

        // Characterisation: close() holds no "already closed" latch, so a double close reaches the delegates twice.
        // Both delegates document an idempotent close, so this is safe today — but it means the context relies on
        // that guarantee. If close() is ever made latching, update these counts deliberately rather than by accident.
        verify(mcpClientManager, org.mockito.Mockito.times(2)).close();
        verify(workflowRunner, org.mockito.Mockito.times(2)).close();
        verify(knowledgeStore, never()).close();
    }
}
