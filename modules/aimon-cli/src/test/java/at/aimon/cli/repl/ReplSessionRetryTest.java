package at.aimon.cli.repl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import at.aimon.cli.config.CliSettings;
import at.aimon.cli.factory.AgentSetupFactory;
import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutor;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.input.UserInput;
import at.aimon.core.agent.queue.DefaultMessageQueueManager;
import at.aimon.core.agent.queue.InMemoryMessageQueueRepository;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.session.LiveSession;
import at.aimon.core.agent.session.RewoundTurn;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.SubmitOutcome;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.base.Principal;
import at.aimon.core.command.execution.ExecutionMetadata;

/**
 * Pins the REPL's half of {@code /retry}: that it goes through the ordinary turn path rather than the session's
 * one-shot {@code retryLastTurn}, and that the two cases where there is nothing to run come back as a line of text
 * instead of a stack trace.
 *
 * <p>
 * Taking the ordinary path is the assertion with teeth. A retried turn is the one the user just stopped, so it needs
 * the streaming listener and — above all — the Ctrl+C handler that {@code awaitAndRender} binds around the wait.
 * Calling {@link LiveSession#retryLastTurn()} here would have been one line shorter and would have silently dropped
 * both.
 */
@DisplayName("ReplSession /retry")
class ReplSessionRetryTest {

    private LiveSession liveSession;
    private MessageQueueManager queueManager;

    private ReplSession replSessionFor(LiveSession session) {
        final OrcaAgentRuntime runtime = mock(OrcaAgentRuntime.class);
        when(runtime.getId()).thenReturn(AgentRuntimeId.of("agent:retry-test"));
        when(runtime.getWorkflowRunner()).thenReturn(Optional.empty());

        queueManager = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());
        final CliSettings settings = new CliSettings();
        final AgentSetupFactory.AgentSetup agentSetup = AgentSetupFactory.AgentSetup.builder()
                .agentExecutor(mock(OrcaAgentExecutor.class)).agentRuntime(runtime)
                .outputFormatter(new OutputFormatter(settings)).messageQueueManager(queueManager).liveSession(session)
                .build();
        liveSession = session;
        return new ReplSession(agentSetup, settings, null);
    }

    /** Deliberately something the REPL never sets, so seeing it proves it came back from the rewind. */
    private static final SubmitOptions RETRY_OPTIONS = SubmitOptions.builder().principal(Principal.user("operator-7"))
            .build();

    private static AgentExecutionResult anyResult() {
        return OrcaAgentExecutionResult.success("done", SessionSnapshot.of(SessionId.of("default")),
                ExecutionMetadata.simple(Duration.ZERO, Instant.EPOCH, Instant.EPOCH));
    }

    @Test
    @DisplayName("resubmits the rewound input through the ordinary turn path")
    void retryResubmitsTheRewoundInputThroughTheTurnPath() {
        final LiveSession session = mock(LiveSession.class);
        when(session.rewindLastTurn())
                .thenReturn(Optional.of(RewoundTurn.of(TextInput.of("summarise the incident"), RETRY_OPTIONS)));
        // The UserInput overload is the one executeAgent calls, and on a mock its default body never runs.
        when(session.offerAsync(Mockito.any(UserInput.class), Mockito.any(),
                Mockito.<Consumer<AgentExecutionEvent>>any()))
                .thenReturn(SubmitOutcome.executed(CompletableFuture.completedFuture(anyResult())));

        replSessionFor(session).processInput("/retry");

        final ArgumentCaptor<UserInput> submitted = ArgumentCaptor.forClass(UserInput.class);
        final ArgumentCaptor<SubmitOptions> options = ArgumentCaptor.forClass(SubmitOptions.class);
        verify(liveSession).offerAsync(submitted.capture(), options.capture(),
                Mockito.<Consumer<AgentExecutionEvent>>any());
        assertThat(submitted.getValue()).isEqualTo(TextInput.of("summarise the incident"));
        // The options go back with the input. A retry submitted as nobody would be the same words from a different
        // caller — and the REPL never sets a principal itself, so this can only have come from the rewind.
        assertThat(options.getValue()).isEqualTo(RETRY_OPTIONS);

        // The one-shot convenience would have skipped the streaming listener and the Ctrl+C handler with it.
        verify(liveSession, never()).retryLastTurn();
        verify(liveSession, never()).retryLastTurn(Mockito.any());
    }

    @Test
    @DisplayName("says so and runs nothing when the last turn was not interrupted")
    void retryWithNothingToRewindRunsNoTurn() {
        final LiveSession session = mock(LiveSession.class);
        when(session.rewindLastTurn()).thenReturn(Optional.empty());

        replSessionFor(session).processInput("/retry");

        verify(liveSession, never()).offerAsync(Mockito.any(UserInput.class), Mockito.any(),
                Mockito.<Consumer<AgentExecutionEvent>>any());
        assertThat(queueManager.snapshot()).as("a refused retry must not leave the input queued instead").isEmpty();
    }

    /**
     * {@code LiveSession} defaults {@code rewindLastTurn} to throwing, so a REPL wired to an implementation that has
     * not overridden it must report that rather than let the exception end the read-eval loop.
     */
    @Test
    @DisplayName("reports a session that cannot rewind instead of failing the loop")
    void retryOnASessionThatCannotRewindIsReported() {
        final LiveSession session = mock(LiveSession.class);
        when(session.rewindLastTurn()).thenThrow(new UnsupportedOperationException("not supported"));

        replSessionFor(session).processInput("/retry");

        verify(liveSession, never()).offerAsync(Mockito.any(UserInput.class), Mockito.any(),
                Mockito.<Consumer<AgentExecutionEvent>>any());
    }

    @Test
    @DisplayName("a rewind that fails is reported rather than thrown out of the loop")
    void retryThatFailsIsReported() {
        final LiveSession session = mock(LiveSession.class);
        when(session.rewindLastTurn()).thenThrow(new IllegalStateException("store unreachable"));

        replSessionFor(session).processInput("/retry");

        verify(liveSession, never()).offerAsync(Mockito.any(UserInput.class), Mockito.any(),
                Mockito.<Consumer<AgentExecutionEvent>>any());
    }
}
