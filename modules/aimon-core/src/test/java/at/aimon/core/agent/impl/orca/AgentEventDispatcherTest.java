package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.IterationStarted;

@DisplayName("AgentEventDispatcher — per-turn dual-sink event fan-out (H1)")
class AgentEventDispatcherTest {

    private final AgentRuntimeId agentRuntimeId = AgentRuntimeId.fromName("ops");

    @Test
    @DisplayName("delivers each event to BOTH the shared emitter and the per-execution sink, stamped with the context id")
    void fansOutToBothSinks() {
        final EventEmitter shared = new EventEmitter();
        final EventEmitter perExecution = new EventEmitter();
        final List<AgentExecutionEvent> sharedSeen = new ArrayList<>();
        final List<AgentExecutionEvent> sinkSeen = new ArrayList<>();
        shared.addListener(sharedSeen::add);
        perExecution.addListener(sinkSeen::add);

        final AgentEventDispatcher dispatcher = new AgentEventDispatcher(shared, agentRuntimeId, Instant.now(),
                perExecution);
        dispatcher.emitIterationStarted(3);

        assertThat(sharedSeen).hasSize(1);
        assertThat(sinkSeen).hasSize(1);
        assertThat(sharedSeen.get(0)).isInstanceOf(IterationStarted.class);
        final IterationStarted event = (IterationStarted) sharedSeen.get(0);
        assertThat(event.getAgentRuntimeId()).isEqualTo(agentRuntimeId);
        assertThat(event.getIteration()).isEqualTo(3);
        // Same event instance is delivered to both sinks.
        assertThat(sinkSeen.get(0)).isSameAs(sharedSeen.get(0));
    }

    @Test
    @DisplayName("short-circuits (no event built) when neither sink has a listener")
    void shortCircuitsWithNoListeners() {
        final EventEmitter shared = new EventEmitter();
        final EventEmitter perExecution = new EventEmitter();
        final AgentEventDispatcher dispatcher = new AgentEventDispatcher(shared, agentRuntimeId, Instant.now(),
                perExecution);

        // No listeners on either sink: emit must be a safe no-op.
        dispatcher.emitIterationStarted(1);
        dispatcher.emitExecutionError(new IllegalStateException("boom"), "boom");

        assertThat(shared.isEmpty()).isTrue();
        assertThat(perExecution.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a subscriber on only one sink still receives the event")
    void singleSinkSubscriber() {
        final EventEmitter shared = new EventEmitter();
        final EventEmitter perExecution = new EventEmitter();
        final List<AgentExecutionEvent> sinkSeen = new ArrayList<>();
        perExecution.addListener(sinkSeen::add);

        final AgentEventDispatcher dispatcher = new AgentEventDispatcher(shared, agentRuntimeId, Instant.now(),
                perExecution);
        dispatcher.emitIterationStarted(7);

        assertThat(sinkSeen).hasSize(1);
    }
}
