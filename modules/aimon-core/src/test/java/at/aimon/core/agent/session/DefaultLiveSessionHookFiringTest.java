package at.aimon.core.agent.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentExecutor;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionRequest;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * Phase 3 WI-3.3.d — verifies that {@link DefaultLiveSession} fires {@code OnSessionStart} on construction and
 * {@code OnSessionEnd} on close when a {@link HookExecutionManager} is wired.
 */
class DefaultLiveSessionHookFiringTest {

    @TempDir
    Path tempDir;

    @Test
    void firesOnSessionStartAndEndWhenHookManagerWired() {
        final DefaultHookRegistry registry = new DefaultHookRegistry();
        final AtomicInteger startCount = new AtomicInteger();
        final AtomicInteger endCount = new AtomicInteger();
        registry.register(HookEventType.ON_SESSION_START, ctx -> {
            startCount.incrementAndGet();
            return HookResult.allow();
        });
        registry.register(HookEventType.ON_SESSION_END, ctx -> {
            endCount.incrementAndGet();
            return HookResult.allow();
        });

        final OrcaAgentRuntime context = createContext(registry);
        final HookExecutionManager hookExecutionManager = new DefaultHookExecutionManager();

        final DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), context, noopExecutor(),
                LiveSessionOptions.defaults(), null, hookExecutionManager);

        assertThat(startCount.get()).isEqualTo(1);
        assertThat(endCount.get()).isZero();

        session.close();
        assertThat(endCount.get()).isEqualTo(1);

        // Idempotent close — second invocation must not re-fire the end hook.
        session.close();
        assertThat(endCount.get()).isEqualTo(1);
    }

    @Test
    void doesNotFireWhenHookManagerOmitted() {
        final DefaultHookRegistry registry = new DefaultHookRegistry();
        final AtomicInteger startCount = new AtomicInteger();
        registry.register(HookEventType.ON_SESSION_START, ctx -> {
            startCount.incrementAndGet();
            return HookResult.allow();
        });

        final OrcaAgentRuntime context = createContext(registry);

        final DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), context, noopExecutor(),
                LiveSessionOptions.defaults());
        try {
            assertThat(startCount.get()).isZero();
        } finally {
            session.close();
        }
    }

    private OrcaAgentRuntime createContext(DefaultHookRegistry hookRegistry) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder().id(AgentRuntimeId.of("agent:hook-firing-test"))
                .agent(DefaultAgent.builder().name("HookFiringTestAgent").maxIterations(1)
                        .systemPrompt("You are a test agent").build())
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(hookRegistry)
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).fileSystem(fileSystem)
                .environment(Environment.createDefault()).build();
    }

    private static AgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult> noopExecutor() {
        return (ctx, request) -> {
            throw new UnsupportedOperationException("Not used in this test");
        };
    }
}
