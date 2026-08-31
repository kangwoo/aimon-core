package at.aimon.workflow.graaljs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.workflow.RunId;
import at.aimon.core.workflow.WorkflowRunner;
import at.aimon.core.workflow.WorkflowRunnerOptions;
import at.aimon.core.workflow.WorkflowRunners;

/**
 * Shared harness for GraalJS run tests: a real {@link GraalJsEngineHolder}, a mocked
 * {@link SubagentExecutionManager}, and a foreground {@code WorkflowRunner} that executes a
 * {@link GraalJsWorkflowScript}. No LLM, no Docker.
 *
 * <p>
 * The mock's per-agent answer is customizable via {@link #behavior} so a test can, e.g., emit schema-shaped JSON.
 */
abstract class AbstractGraalJsRunTest {

    protected GraalJsEngineHolder engines;
    protected SubagentExecutionManager manager;

    /** {@code (subagent, augmentedGoal) -> finalAnswer}. Default echoes the goal. */
    protected BiFunction<Subagent, String, String> behavior = (subagent, goal) -> "ans:" + goal;

    @BeforeEach
    void baseSetUp() {
        engines = GraalJsEngineHolder.create();
        manager = mock(SubagentExecutionManager.class);
        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenAnswer(invocation -> {
                    final Subagent subagent = invocation.getArgument(1, Subagent.class);
                    final String goal = invocation.getArgument(2, String.class);
                    return success(behavior.apply(subagent, goal));
                });
    }

    @AfterEach
    void baseTearDown() {
        engines.close();
    }

    protected String run(String js) {
        return run(js, Map.of(), JsSandboxConfig.defaults());
    }

    protected String run(String js, Map<String, Object> args, JsSandboxConfig config) {
        final GraalJsWorkflowScript script = new GraalJsWorkflowScript(js, args, config, engines,
                SubagentResolver.inline(), null);
        try (WorkflowRunner runner = WorkflowRunners.create(manager, env(), WorkflowRunnerOptions.defaults())) {
            return runner.run(script, RunId.from("test-run"));
        }
    }

    protected String run(String js, WorkflowRunnerOptions options) {
        final GraalJsWorkflowScript script = new GraalJsWorkflowScript(js, Map.of(), JsSandboxConfig.defaults(),
                engines, SubagentResolver.inline(), null);
        try (WorkflowRunner runner = WorkflowRunners.create(manager, env(), options)) {
            return runner.run(script, RunId.from("test-run"));
        }
    }

    protected static SubagentExecutionResult success(String answer) {
        final Instant now = Instant.now();
        return SubagentExecutionResult.success(answer, SessionSnapshot.of(SessionId.generate()), ExecutionMetadata
                .builder().iterationCount(1).tokenUsage(TokenUsage.empty()).timestamps(now, now).build());
    }

    protected static SubagentExecutionEnvironment env() {
        return SubagentExecutionEnvironment.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(new InMemorySubagentRegistry()).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .defaultModel(LlmModel.builder().name("gpt-4").build()).build();
    }
}
