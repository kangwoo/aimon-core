/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.scheduling.quartz;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.RoutineExecutor;
import at.aimon.core.scheduling.RoutineResult;
import at.aimon.core.scheduling.RoutineStep;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.SchedulingEngine;
import at.aimon.core.scheduling.SchedulingEngineBuilder;
import at.aimon.core.scheduling.event.SimpleScheduledTaskEventPublisher;

/**
 * Multi-instance simulation: verifies that agent-derived {@link AgentRuntimeId}s are stable across nodes and
 * that a {@link ScheduledTask} captured on one node can be resolved against another node's context registry, provided
 * both nodes have registered the same agent.
 *
 * <p>
 * Two independent {@link SchedulingEngine} instances (each with its own
 * {@link DefaultAgentRuntimeRegistry}) stand in for two production nodes. We deliberately do <i>not</i> spin
 * up Testcontainers / a shared Quartz JDBC job store: the property under test &mdash; the determinism of
 * {@code agent:<name>} ids and their resolvability via per-node registries &mdash; is fully exercised in a single JVM,
 * and adding a containerised database here would only test Quartz's clustering, not the agent-scoped contract that
 * this PR introduces. The decision is documented in
 * {@code docs/design/agent-execution/agent-runtime-scope.md} §2.1.
 */
class AgentScopedRuntimeIdAcrossInstancesTest {

    private SchedulingEngine engineA;
    private SchedulingEngine engineB;

    @AfterEach
    void tearDown() {
        if (engineA != null) {
            engineA.close();
        }
        if (engineB != null) {
            engineB.close();
        }
    }

    @Test
    void agentDerivedRuntimeIdsAreIdenticalAcrossInstances() {
        final Agent agentNodeA = DefaultAgent.builder().name("ops-bot").systemPrompt("p").build();
        final Agent agentNodeB = DefaultAgent.builder().name("ops-bot").systemPrompt("p").build();

        final AgentRuntimeId idOnA = AgentRuntimeId.from(agentNodeA);
        final AgentRuntimeId idOnB = AgentRuntimeId.from(agentNodeB);

        assertThat(idOnA).isEqualTo(idOnB);
        assertThat(idOnA.value()).isEqualTo("agent:ops-bot");

        final AgentRuntimeId tenantA = AgentRuntimeId.from(agentNodeA, "acme");
        final AgentRuntimeId tenantB = AgentRuntimeId.from(agentNodeB, "acme");
        assertThat(tenantA).isEqualTo(tenantB);
        assertThat(tenantA.value()).isEqualTo("agent:ops-bot:acme");
    }

    /**
     * Cross-node bootstrap: both nodes register their own AEC for the same agent (deterministic id). A
     * {@link SchedulingEngine} on each side wires
     * {@link SchedulingEngineBuilder#agentRuntimeRegistry(at.aimon.core.agent.AgentRuntimeRegistry)}
     * to its <i>local</i> registry, and the same {@code boundRuntimeId} is therefore resolvable from either side.
     */
    @Test
    void bothNodesResolveTheSameBoundRuntimeId() {
        final Agent agent = DefaultAgent.builder().name("shared-agent").systemPrompt("p").build();
        final AgentRuntimeId sharedId = AgentRuntimeId.from(agent);

        final DefaultAgentRuntimeRegistry registryA = new DefaultAgentRuntimeRegistry();
        final DefaultAgentRuntimeRegistry registryB = new DefaultAgentRuntimeRegistry();

        registryA.register(
                new StubAgentRuntime(sharedId, agent, List.of(new RecordingTool("noop", new AtomicInteger()))));
        registryB.register(
                new StubAgentRuntime(sharedId, agent, List.of(new RecordingTool("noop", new AtomicInteger()))));

        engineA = SchedulingEngineBuilder.create().agentRuntimeRegistry(registryA).build();
        engineB = SchedulingEngineBuilder.create().agentRuntimeRegistry(registryB).build();
        engineA.start();
        engineB.start();

        // The bound id is identical on both sides (agent-derived), and each node resolves it against its own registry.
        assertThat(registryA.get(sharedId)).isPresent();
        assertThat(registryB.get(sharedId)).isPresent();
        assertThat(registryA.get(sharedId).orElseThrow().getAgent().getName()).isEqualTo("shared-agent");
        assertThat(registryB.get(sharedId).orElseThrow().getAgent().getName()).isEqualTo("shared-agent");
    }

    /**
     * Smoke test: drive RoutineExecutor on node B directly with the task A produced. Demonstrates "node A produces a
     * task, node B resolves and executes it" at the routine layer.
     */
    @Test
    void routineProducedOnNodeAExecutesOnNodeB() {
        final Agent agent = DefaultAgent.builder().name("shared-agent-2").systemPrompt("p").build();
        final AgentRuntimeId sharedId = AgentRuntimeId.from(agent);

        // Node A's registry (the AEC that captured the task) contains the agent but no tools wired -- it never runs
        // the routine itself.
        final DefaultAgentRuntimeRegistry registryA = new DefaultAgentRuntimeRegistry();
        registryA.register(new StubAgentRuntime(sharedId, agent, List.of()));

        // Node B's registry has the same agent registered, with the tools needed to run the routine.
        final DefaultAgentRuntimeRegistry registryB = new DefaultAgentRuntimeRegistry();
        final AtomicInteger nodeBInvocations = new AtomicInteger();
        registryB.register(new StubAgentRuntime(sharedId, agent, List.of(new RecordingTool("noop", nodeBInvocations))));

        // Task is built on node A using the agent-scoped id -- crucially, the same id resolves on node B.
        final ScheduledTask task = newTask(sharedId, "noop");

        // Node B's RoutineExecutor resolves the AEC out of registryB and runs the tool.
        final RoutineExecutor executorOnB = new RoutineExecutor(registryB, new SimpleScheduledTaskEventPublisher());
        try {
            final RoutineResult result = executorOnB.execute(task);
            assertThat(result.isSuccess()).isTrue();
            assertThat(nodeBInvocations.get()).isEqualTo(1);
        } finally {
            executorOnB.shutdown();
        }
    }

    private static ScheduledTask newTask(AgentRuntimeId boundRuntimeId, String toolName) {
        return ScheduledTask.builder().id(ScheduledTaskId.generate()).name("cross-node-task")
                .cronExpression("0 0 * * *")
                .routine(List.of(RoutineStep.builder().tool(toolName).toolParams("{}").maxRetries(0)
                        .timeout(Duration.ofSeconds(5)).build()))
                .owner(Principal.system()).boundRuntimeId(boundRuntimeId).enabled(false).build();
    }

    private static final class StubAgentRuntime implements AgentRuntime {

        private final AgentRuntimeId id;
        private final Agent agent;
        private final List<Tool> tools;

        StubAgentRuntime(AgentRuntimeId id, Agent agent, List<Tool> tools) {
            this.id = Objects.requireNonNull(id);
            this.agent = Objects.requireNonNull(agent);
            this.tools = List.copyOf(tools);
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
    }

    private static final class RecordingTool extends AbstractTool {

        private final AtomicInteger invocationCount;

        RecordingTool(String name, AtomicInteger invocationCount) {
            super(name, "test tool", Map.of("type", "object", "properties", Map.of()));
            this.invocationCount = invocationCount;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            invocationCount.incrementAndGet();
            return ToolResult.success("ok");
        }
    }
}
