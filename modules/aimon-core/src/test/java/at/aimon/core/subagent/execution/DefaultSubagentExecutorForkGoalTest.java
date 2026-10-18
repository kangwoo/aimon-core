package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.ExecutionEnvironmentProvider;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.environment.exception.StagingException;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.OnStartHook;
import at.aimon.core.hook.event.OnStopHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentMetadata;

/**
 * EE-81 and EE-52 on the executor: a fork placed in an environment other than its spawner's renders its goal there,
 * and every result names the environment the fork ran in.
 */
@DisplayName("DefaultSubagentExecutor: the fork's goal and environment")
class DefaultSubagentExecutorForkGoalTest {

    private final ExecutionEnvironment spawnerEnvironment = TestExecutionEnvironments.builder()
            .workingDirectory("/spawner").build();
    private final ExecutionEnvironment forkEnvironment = TestExecutionEnvironments.builder().workingDirectory("/fork")
            .build();
    private final DefaultHookRegistry hooks = new DefaultHookRegistry();
    private final RecordingLlmClient llm = new RecordingLlmClient();

    @Test
    @DisplayName("a fork placed elsewhere is given the goal rendered in its own environment")
    void aForkPlacedElsewhereRendersItsGoalThere() {
        final List<ExecutionEnvironment> renderedIn = new ArrayList<>();

        final SubagentExecutionResult result = execute(request -> forkEnvironment, environment -> {
            renderedIn.add(environment);
            return "Run /fork/.aimon-staged/skill/x";
        });

        assertThat(renderedIn).containsExactly(forkEnvironment);
        assertThat(llm.firstUserMessage).isEqualTo("Run /fork/.aimon-staged/skill/x");
        assertThat(result.getExecutionEnvironment()).containsSame(forkEnvironment);
    }

    @Test
    @DisplayName("a fork answered with its parent keeps the spawner's text; the renderer is not called")
    void aForkInTheSpawnersEnvironmentIsNotRenderedAgain() {
        final AtomicInteger calls = new AtomicInteger();

        final SubagentExecutionResult result = execute(request -> request.parent().orElseThrow(), environment -> {
            calls.incrementAndGet();
            return "never";
        });

        assertThat(calls).hasValue(0);
        assertThat(llm.firstUserMessage).isEqualTo("Run /spawner/.aimon-staged/skill/x");
        assertThat(result.getExecutionEnvironment()).containsSame(spawnerEnvironment);
    }

    @Test
    @DisplayName("a goal that cannot be staged in the fork fails the fork before onStart, still naming the environment")
    void aGoalThatCannotBeStagedFailsTheForkBeforeItStarts() {
        final AtomicInteger onStarts = new AtomicInteger();
        final AtomicInteger onStops = new AtomicInteger();
        hooks.register(HookEventType.ON_START, (OnStartHook) context -> {
            onStarts.incrementAndGet();
            return HookResult.success();
        });
        hooks.register(HookEventType.ON_STOP, (OnStopHook) context -> {
            onStops.incrementAndGet();
            return HookResult.success();
        });

        final SubagentExecutionResult result = execute(request -> forkEnvironment, environment -> {
            throw new StagingException("Cannot stage 'x': over the limit");
        });

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("over the limit");
        assertThat(result.getExecutionEnvironment()).containsSame(forkEnvironment);
        assertThat(result.getExecutionId()).isPresent();
        assertThat(onStarts).hasValue(0);
        assertThat(onStops).hasValue(0);
        assertThat(llm.calls).hasValue(0);
    }

    @Test
    @DisplayName("the environment is a live handle: it is not part of the result's value")
    void theEnvironmentIsNotPartOfTheValue() {
        final SubagentExecutionResult plain = SubagentExecutionResult.emptySuccess("ok", java.time.Instant.EPOCH);

        final SubagentExecutionResult stamped = plain.withExecutionEnvironment(forkEnvironment);

        assertThat(stamped).isEqualTo(plain).hasSameHashCodeAs(plain);
        assertThat(stamped.toString()).isEqualTo(plain.toString());
        assertThat(plain.getExecutionEnvironment()).isEmpty();
    }

    private SubagentExecutionResult execute(ExecutionEnvironmentProvider provider, ForkGoalRenderer renderer) {
        final Subagent subagent = Subagent.of("explorer",
                SubagentMetadata.builder().description("d").maxIterations(3).build(),
                SubagentContent.of("you are explorer"));
        final SubagentExecutionContext context = SubagentExecutionContext.builder()
                .agentRuntimeId(AgentRuntimeId.of("agent:test-1")).subagent(subagent)
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(hooks).executionEnvironment(spawnerEnvironment).executionEnvironmentProvider(provider)
                .parentCancellationSignal(NoopCancellationSignal.INSTANCE).build();
        return new DefaultSubagentExecutor(llm, new DefaultToolExecutionManager(), new DefaultHookExecutionManager())
                .execute(context, SubagentExecutionRequest.builder().taskId("task-1")
                        .goal("Run /spawner/.aimon-staged/skill/x").forkGoalRenderer(renderer).build());
    }

    /** Answers once and records the first user message it was sent. */
    private static final class RecordingLlmClient implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile String firstUserMessage;

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            if (calls.getAndIncrement() == 0) {
                firstUserMessage = messages.stream().filter(m -> m.getRole() == Role.USER).map(Message::getContent)
                        .findFirst().orElse(null);
            }
            return LlmResponse.text("done");
        }

        @Override
        public String getProviderName() {
            return "Recording";
        }
    }
}
