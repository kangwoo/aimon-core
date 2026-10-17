/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.environment.EnvironmentProviding;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.ExecutionEnvironmentProvider;
import at.aimon.core.environment.UnavailableExecutionEnvironment;
import at.aimon.core.scheduling.event.SimpleScheduledTaskEventPublisher;
import at.aimon.core.tools.ToolContextKeys;

/**
 * A routine run asks the host's {@link RoutineExecutionAttributes} once, and its {@code environment.*} subset reaches
 * the run's {@link EnvironmentRequest#requestAttributes()} (issue #252).
 */
class RoutineExecutorExecutionAttributesTest {

    private DefaultAgentRuntimeRegistry agentRuntimeRegistry;
    private RoutineExecutor executor;
    private RecordingProvider provider;
    private ContextCapturingTool tool;
    private AgentRuntimeId boundRuntimeId;

    @BeforeEach
    void setUp() {
        agentRuntimeRegistry = new DefaultAgentRuntimeRegistry();
        executor = new RoutineExecutor(agentRuntimeRegistry, new SimpleScheduledTaskEventPublisher());
        provider = new RecordingProvider();
        tool = new ContextCapturingTool();
        final Agent agent = DefaultAgent.builder().name("orca-attrs").systemPrompt("test").build();
        boundRuntimeId = AgentRuntimeId.from(agent);
        agentRuntimeRegistry.register(new ProvidingRuntime(boundRuntimeId, agent, List.of(tool), provider));
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void withoutAResolverTheRequestCarriesNoAttributesAndStepsSeeNone() {
        assertThat(executor.execute(task(boundRuntimeId)).isSuccess()).isTrue();

        assertThat(provider.requests).singleElement()
                .satisfies(request -> assertThat(request.requestAttributes()).isEmpty());
        assertThat(tool.captured.get().get(ToolContextKeys.EXECUTION_ATTRIBUTES_KEY)).isEmpty();
    }

    @Test
    void theResolversEnvironmentSubsetReachesTheRequestAndTheWholeMapReachesTheSteps() {
        final AtomicReference<ExecutionId> asked = new AtomicReference<>();
        final Map<String, Object> attributes = Map.of("environment.sandbox.workspace", "ws-7", "feature.flag", true);
        executor.withExecutionAttributes((task, executionId) -> {
            asked.set(executionId);
            return attributes;
        });

        assertThat(executor.execute(task(boundRuntimeId)).isSuccess()).isTrue();

        assertThat(provider.requests).singleElement().satisfies(request -> {
            assertThat(request.requestAttributes()).containsExactly(Map.entry("sandbox.workspace", "ws-7"));
            assertThat(request.executionId()).contains(asked.get());
        });
        assertThat(tool.captured.get().get(ToolContextKeys.EXECUTION_ID)).contains(asked.get());
        assertThat(tool.captured.get().get(ToolContextKeys.EXECUTION_ATTRIBUTES_KEY)).contains(attributes);
    }

    @Test
    void theResolverIsAskedOncePerRunWithThatRunsTask() {
        final List<ScheduledTaskId> asked = new CopyOnWriteArrayList<>();
        executor.withExecutionAttributes((task, executionId) -> {
            asked.add(task.getId());
            return Map.of();
        });
        final ScheduledTask task = task(boundRuntimeId);

        executor.execute(task);
        executor.execute(task);

        assertThat(asked).containsExactly(task.getId(), task.getId());
    }

    @Test
    void aNonStringEnvironmentValueMakesTheEnvironmentUnavailableWithoutCallingTheProvider() {
        executor.withExecutionAttributes((task, executionId) -> Map.of("environment.sandbox.refuse", true));

        assertThat(executor.execute(task(boundRuntimeId)).isSuccess()).isTrue();

        assertThat(provider.requests).isEmpty();
        assertThat(environmentNotes()).contains("environment.sandbox.refuse").contains("java.lang.Boolean");
    }

    @Test
    void aThrowingResolverMakesTheEnvironmentUnavailableAndDoesNotAbortTheRun() {
        executor.withExecutionAttributes((task, executionId) -> {
            throw new IllegalStateException("placement store down");
        });

        final RoutineResult result = executor.execute(task(boundRuntimeId));

        assertThat(result.isSuccess()).isTrue();
        assertThat(provider.requests).isEmpty();
        assertThat(environmentNotes()).contains("placement store down");
        assertThat(tool.captured.get().get(ToolContextKeys.EXECUTION_ATTRIBUTES_KEY)).isEmpty();
        assertThat(executor.isRunning(task(boundRuntimeId).getId())).isFalse();
    }

    @Test
    void aResolverReturningNullOrANullValueIsTreatedAsAFailure() {
        executor.withExecutionAttributes((task, executionId) -> null);
        assertThat(executor.execute(task(boundRuntimeId)).isSuccess()).isTrue();
        assertThat(environmentNotes()).contains("returned no execution attributes");

        final Map<String, Object> withNull = new HashMap<>();
        withNull.put("feature.flag", null);
        executor.withExecutionAttributes((task, executionId) -> withNull);
        assertThat(executor.execute(task(boundRuntimeId)).isSuccess()).isTrue();

        assertThat(provider.requests).isEmpty();
        assertThat(tool.captured.get().get(ToolContextKeys.EXECUTION_ENVIRONMENT).orElseThrow())
                .isInstanceOf(UnavailableExecutionEnvironment.class);
    }

    @Test
    void theResolverIsAskedEvenWhenTheRuntimeIsNotRegistered() {
        final AtomicReference<ExecutionId> asked = new AtomicReference<>();
        executor.withExecutionAttributes((task, executionId) -> {
            asked.set(executionId);
            return Map.of();
        });

        executor.execute(task(AgentRuntimeId.fromName("missing")));

        assertThat(asked.get()).isNotNull();
    }

    private String environmentNotes() {
        final ExecutionEnvironment environment = tool.captured.get().get(ToolContextKeys.EXECUTION_ENVIRONMENT)
                .orElseThrow();
        return environment.descriptor().notes().orElse("");
    }

    static ScheduledTask task(AgentRuntimeId boundRuntimeId) {
        return ScheduledTask.builder().id(ScheduledTaskId.of("attrs-task")).name("attrs-task")
                .cronExpression("*/5 * * * *").routine(List.of(RoutineStep.of(ContextCapturingTool.NAME, "{}")))
                .owner(Principal.user("alice")).boundRuntimeId(boundRuntimeId).enabled(true).build();
    }

    /** Records every request and answers with an environment that only describes itself. */
    static final class RecordingProvider implements ExecutionEnvironmentProvider {
        final List<EnvironmentRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public ExecutionEnvironment resolve(EnvironmentRequest request) {
            requests.add(request);
            return UnavailableExecutionEnvironment.of("resolved");
        }
    }

    /** Captures the context the step runs with. */
    static final class ContextCapturingTool extends AbstractTool {
        static final String NAME = "Capture";
        final AtomicReference<ToolContext> captured = new AtomicReference<>();

        ContextCapturingTool() {
            super(NAME, "test tool", Map.of("type", "object", "properties", Map.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            captured.set(context);
            return ToolResult.success("ok");
        }
    }

    /** A runtime that carries an execution-environment provider, as the Orca runtime does. */
    static final class ProvidingRuntime implements AgentRuntime, EnvironmentProviding {
        private final AgentRuntimeId id;
        private final Agent agent;
        private final List<Tool> tools;
        private final ExecutionEnvironmentProvider provider;

        ProvidingRuntime(AgentRuntimeId id, Agent agent, List<Tool> tools, ExecutionEnvironmentProvider provider) {
            this.id = Objects.requireNonNull(id);
            this.agent = Objects.requireNonNull(agent);
            this.tools = List.copyOf(tools);
            this.provider = Objects.requireNonNull(provider);
        }

        @Override
        public AgentRuntimeId getId() {
            return id;
        }

        @Override
        public Agent getAgent() {
            return agent;
        }

        @Override
        public List<Tool> getAvailableTools() {
            return tools;
        }

        @Override
        public ExecutionEnvironmentProvider getExecutionEnvironmentProvider() {
            return provider;
        }
    }
}
