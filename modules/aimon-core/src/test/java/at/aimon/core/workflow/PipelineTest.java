package at.aimon.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

@DisplayName("Pipeline / Stage — type-preserving N-stage builder (Phase 4 §4.1)")
class PipelineTest {

    private SubagentExecutionManager manager;
    private SubagentExecutionEnvironment env;
    private Subagent sub;

    @BeforeEach
    void setUp() {
        manager = mock(SubagentExecutionManager.class);
        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenAnswer(invocation -> success("ans:" + invocation.getArgument(2, String.class)));
        env = env();
        sub = subagent("worker");
    }

    @Test
    @DisplayName("threads the intermediate type through each stage and preserves input order")
    void nStageTypeSafe() {
        final WorkflowRunner runner = WorkflowRunners.create(manager, env);
        try {
            final List<String> out = runner
                    .run(ctx -> Pipeline.over(List.of("a", "b")).then((s, orig) -> ctx.agent(sub, "s1-" + s)) // Stage<String,
                                                                                                              // AgentStepResult>
                            .then((r, orig) -> r.text()) // Stage<String, String>
                            .then((t, orig) -> t + "|" + orig) // Stage<String, String>, sees the original item
                            .run(ctx));

            assertThat(out).containsExactly("ans:s1-a|a", "ans:s1-b|b");
        } finally {
            runner.close();
        }
    }

    @Test
    @DisplayName("a single-stage pipeline is the identity map over ctx.parallel")
    void singleStage() {
        final WorkflowRunner runner = WorkflowRunners.create(manager, env);
        try {
            final List<String> out = runner
                    .run(ctx -> Pipeline.over(List.of("x", "y")).then((s, orig) -> ctx.agent(sub, s).text()).run(ctx));

            assertThat(out).containsExactly("ans:x", "ans:y");
        } finally {
            runner.close();
        }
    }

    @Test
    @DisplayName("over() tolerates null items (failed prior fan-out positions); null-safe stages see them in position")
    void nullItemsFlowThroughNullSafeStages() {
        final WorkflowRunner runner = WorkflowRunners.create(manager, env);
        try {
            final List<String> out = runner.run(ctx -> Pipeline.over(Arrays.asList("a", null, "b"))
                    .then((s, orig) -> s == null ? null : ctx.agent(sub, s))
                    .then((r, orig) -> r == null ? "skipped" : r.text() + "|" + orig).run(ctx));

            assertThat(out).containsExactly("ans:a|a", "skipped", "ans:b|b");
        } finally {
            runner.close();
        }
    }

    @Test
    @DisplayName("over(null) fails fast with NullPointerException")
    void overNullListThrows() {
        assertThatThrownBy(() -> Pipeline.over(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("items cannot be null");
    }

    @Test
    @DisplayName("over() defensively copies: mutating the source list afterwards does not affect the pipeline")
    void defensiveCopyIsolatesSourceMutation() {
        final List<String> source = new ArrayList<>(List.of("x", "y"));
        final Stage<String, String> pipeline = Pipeline.over(source).then((s, orig) -> "seen:" + s);

        source.set(0, "mutated");
        source.add("z");

        final WorkflowRunner runner = WorkflowRunners.create(manager, env);
        try {
            final List<String> out = runner.run(ctx -> pipeline.run(ctx));

            assertThat(out).containsExactly("seen:x", "seen:y");
        } finally {
            runner.close();
        }
    }

    private static Subagent subagent(String name) {
        return Subagent.builder().name(name).systemPrompt("(inline)").build();
    }

    private static SubagentExecutionResult success(String answer) {
        final Instant now = Instant.now();
        return SubagentExecutionResult.success(answer, SessionSnapshot.of(SessionId.generate()), ExecutionMetadata
                .builder().iterationCount(1).tokenUsage(TokenUsage.empty()).timestamps(now, now).build());
    }

    private static SubagentExecutionEnvironment env() {
        return SubagentExecutionEnvironment.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(new InMemorySubagentRegistry()).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .defaultModel(LlmModel.builder().name("gpt-4").build()).build();
    }
}
