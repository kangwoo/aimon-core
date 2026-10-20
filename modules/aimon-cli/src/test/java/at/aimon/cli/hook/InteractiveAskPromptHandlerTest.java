package at.aimon.cli.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.PrintStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import at.aimon.core.hook.execution.AskPromptHandler;
import at.aimon.core.hook.execution.Decision;

class InteractiveAskPromptHandlerTest {

    @Test
    void nullConsoleDelegatesToFallback() {
        final InteractiveAskPromptHandler handler = new InteractiveAskPromptHandler(null,
                new PrintStream(new ByteArrayOutputStream()), AskPromptHandler.allowAll());
        assertThat(handler.resolve("Confirm?")).isEqualTo(Decision.ALLOW);
    }

    @Test
    void nullConsoleDenyFallback() {
        final InteractiveAskPromptHandler handler = new InteractiveAskPromptHandler(null,
                new PrintStream(new ByteArrayOutputStream()), AskPromptHandler.denyAll());
        assertThat(handler.resolve("Confirm?")).isEqualTo(Decision.DENY);
    }

    @Test
    void consolePromptsAreShownOneAtATime() throws Exception {
        final CountDownLatch firstReading = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final Console console = mock(Console.class);
        when(console.readLine()).thenAnswer(invocation -> {
            firstReading.countDown();
            releaseFirst.await(5, TimeUnit.SECONDS);
            return "y";
        }).thenReturn("n");
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final InteractiveAskPromptHandler handler = new InteractiveAskPromptHandler(console,
                new PrintStream(bytes, true), AskPromptHandler.denyAll());
        final ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            final Future<Decision> first = callers.submit(() -> handler.resolve("first?"));
            assertThat(firstReading.await(5, TimeUnit.SECONDS)).isTrue();
            final Future<Decision> second = callers.submit(() -> handler.resolve("second?"));
            Thread.sleep(200);
            // The second prompt waits for the console: it has not been printed while the first is being answered.
            assertThat(bytes.toString()).contains("first?").doesNotContain("second?");

            releaseFirst.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(Decision.ALLOW);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(Decision.DENY);
            assertThat(bytes.toString()).contains("second?");
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void aCallerInterruptedWhileWaitingForTheConsoleIsDeniedWithoutPrinting() throws Exception {
        final CountDownLatch firstReading = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final Console console = mock(Console.class);
        when(console.readLine()).thenAnswer(invocation -> {
            firstReading.countDown();
            releaseFirst.await(5, TimeUnit.SECONDS);
            return "y";
        });
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final InteractiveAskPromptHandler handler = new InteractiveAskPromptHandler(console,
                new PrintStream(bytes, true), AskPromptHandler.denyAll());
        final Thread first = new Thread(() -> handler.resolve("first?"));
        first.start();
        assertThat(firstReading.await(5, TimeUnit.SECONDS)).isTrue();

        final AtomicReference<Decision> secondDecision = new AtomicReference<>();
        final AtomicBoolean secondFlag = new AtomicBoolean();
        final Thread second = new Thread(() -> {
            secondDecision.set(handler.resolve("cancelled?"));
            secondFlag.set(Thread.currentThread().isInterrupted());
        });
        second.start();
        Thread.sleep(200);
        second.interrupt();
        second.join(5_000);

        assertThat(secondDecision.get()).isEqualTo(Decision.DENY);
        assertThat(secondFlag.get()).isTrue();
        releaseFirst.countDown();
        first.join(5_000);
        assertThat(bytes.toString()).doesNotContain("cancelled?");
    }

    @Test
    void aCallerAlreadyInterruptedIsDeniedWithoutPrinting() {
        final Console console = mock(Console.class);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final InteractiveAskPromptHandler handler = new InteractiveAskPromptHandler(console,
                new PrintStream(bytes, true), AskPromptHandler.allowAll());

        Thread.currentThread().interrupt();
        final Decision decision = handler.resolve("cancelled?");

        assertThat(Thread.interrupted()).isTrue();
        assertThat(decision).isEqualTo(Decision.DENY);
        assertThat(bytes.toString()).isEmpty();
    }
}
