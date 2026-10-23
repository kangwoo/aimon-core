package at.aimon.cli.repl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import at.aimon.cli.config.CliSettings;
import at.aimon.cli.factory.AgentSetupFactory;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionRequest;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutor;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.queue.DefaultMessageQueueManager;
import at.aimon.core.agent.queue.InMemoryMessageQueueRepository;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.queue.QueuedInput;
import at.aimon.core.agent.session.DefaultLiveSession;
import at.aimon.core.agent.session.LiveSession;
import at.aimon.core.agent.session.LiveSessionOptions;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.command.execution.ExecutionMetadata;

@DisplayName("ReplSession — waking the idle REPL for its background notifications")
class ReplSessionWakeTest {

    private static final AgentRuntimeId RUNTIME = AgentRuntimeId.of("agent:cli-wake");
    private static final SessionId SESSION = SessionId.of("default");

    private final MessageQueueManager queue = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());
    private final OrcaAgentExecutor executor = mock(OrcaAgentExecutor.class);
    private ReplSession repl;

    @AfterEach
    void tearDown() {
        if (repl != null) {
            repl.stopWaking();
        }
    }

    private ReplSession session(boolean wake) {
        final OrcaAgentRuntime runtime = mock(OrcaAgentRuntime.class);
        when(runtime.getId()).thenReturn(RUNTIME);
        when(executor.executeAsync(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(
                CompletableFuture.completedFuture(OrcaAgentExecutionResult.success("on it", SessionSnapshot.of(SESSION),
                        ExecutionMetadata.simple(Duration.ZERO, Instant.EPOCH, Instant.EPOCH))));
        final CliSettings settings = new CliSettings();
        settings.setEnableWake(wake);
        final LiveSession liveSession = new DefaultLiveSession(SESSION, runtime, executor,
                LiveSessionOptions.defaults(), queue);
        repl = new ReplSession(AgentSetupFactory.AgentSetup.builder().agentExecutor(executor).agentRuntime(runtime)
                .outputFormatter(new OutputFormatter(settings)).messageQueueManager(queue).liveSession(liveSession)
                .build(), settings, null);
        // What start() does once it has a terminal.
        repl.startWaking();
        return repl;
    }

    private static QueuedInput monitorEvent(String text) {
        return QueuedInput.builder().agentRuntimeId(RUNTIME).sessionId(SESSION.value()).inputText(text)
                .metadata(Map.of("kind", "monitor-event", "monitorId", "mon_1")).build();
    }

    @Test
    @DisplayName("with waking on, waiting monitor events become one turn whose input they are")
    void dueWakeRunsATurn() {
        final ReplSession repl = session(true);
        queue.enqueue(monitorEvent("Monitor mon_1 (errors) — 1 event:\nERROR connection refused"));

        repl.runDueWake();

        final ArgumentCaptor<OrcaAgentExecutionRequest> request = ArgumentCaptor
                .forClass(OrcaAgentExecutionRequest.class);
        Mockito.verify(executor).executeAsync(Mockito.any(), request.capture(), Mockito.any());
        assertThat(request.getValue().getUserInput().asText()).contains("background-wake")
                .contains("ERROR connection refused");
        assertThat(request.getValue().getSubmitOptions().getExecutionAttributes()).containsEntry("aimon.wake.reason",
                "monitor-event");
        assertThat(queue.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("with waking off, the events wait for the next prompt")
    void offLeavesTheEvents() {
        final ReplSession repl = session(false);
        queue.enqueue(monitorEvent("ERROR"));

        repl.runDueWake();

        Mockito.verifyNoInteractions(executor);
        assertThat(queue.snapshot()).hasSize(1);
    }

    @Test
    @DisplayName("a prompt of the user's ends the streak of wakes")
    void userPromptResetsTheStreak() {
        final ReplSession repl = session(true);
        for (int i = 0; i < 3; i++) {
            queue.enqueue(monitorEvent("event " + i));
            repl.runDueWake();
        }
        queue.enqueue(monitorEvent("over the default streak of three"));
        repl.runDueWake();
        Mockito.verify(executor, Mockito.times(3)).executeAsync(Mockito.any(), Mockito.any(), Mockito.any());

        repl.processTyped("what happened?");
        // The prompt's own turn ran; the waiting event is still for a wake, and the streak is over.
        repl.runDueWake();
        Mockito.verify(executor, Mockito.times(5)).executeAsync(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("a queued entry replayed after a turn is not a prompt of the user's: the streak holds")
    void replayDoesNotResetTheStreak() {
        final ReplSession repl = session(true);
        for (int i = 0; i < 3; i++) {
            queue.enqueue(monitorEvent("event " + i));
            repl.runDueWake();
        }
        repl.processInput("replayed from the queue");
        queue.enqueue(monitorEvent("still over the streak"));
        repl.runDueWake();

        Mockito.verify(executor, Mockito.times(4)).executeAsync(Mockito.any(), Mockito.any(), Mockito.any());
        assertThat(queue.snapshot()).hasSize(1);
    }

    @Test
    @DisplayName("JLine contract: a widget called from another thread can interrupt the reader out of readLine")
    void widgetInterruptEndsTheRead() throws Exception {
        try (PipedOutputStream keys = new PipedOutputStream();
                PipedInputStream input = new PipedInputStream(keys);
                Terminal terminal = TerminalBuilder.builder().system(false).dumb(true)
                        .streams(input, new ByteArrayOutputStream()).build()) {
            final LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            final AtomicReference<Thread> readingThread = new AtomicReference<>();
            reader.getWidgets().put(ReplSession.WAKE_WIDGET, () -> {
                readingThread.get().interrupt();
                return true;
            });
            final CompletableFuture<Throwable> outcome = new CompletableFuture<>();
            final Thread thread = new Thread(() -> {
                try {
                    reader.readLine("> ");
                    outcome.complete(null);
                } catch (Throwable t) {
                    outcome.complete(t);
                }
            });
            readingThread.set(thread);
            thread.start();
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!reader.isReading() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            reader.callWidget(ReplSession.WAKE_WIDGET);

            assertThat(outcome.get(5, TimeUnit.SECONDS)).isInstanceOf(UserInterruptException.class);
        }
    }
}
