package at.aimon.core.tools.bash;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCancellation;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellCancelledException;
import at.aimon.core.shell.exception.ShellExecutionException;

/**
 * A shell whose one command runs until the test ends it: {@link #finish} lets it return, and — when built to support
 * {@link ShellFeature#CANCELLATION} — tripping the command's signal makes it throw {@link ShellCancelledException}
 * with the output "printed so far". One instance stands for one command.
 */
final class ControllableShell implements VirtualShell {

    static final String PARTIAL_OUTPUT = "printed before the stop";

    private final boolean cancellable;
    private final CountDownLatch started = new CountDownLatch(1);
    private final CompletableFuture<ShellCommandResult> outcome = new CompletableFuture<>();
    private volatile ExecutionOptions lastOptions;

    private ControllableShell(boolean cancellable) {
        this.cancellable = cancellable;
    }

    static ControllableShell cancellable() {
        return new ControllableShell(true);
    }

    static ControllableShell uncancellable() {
        return new ControllableShell(false);
    }

    void finish(String output) {
        outcome.complete(new ShellCommandResult(0, output, "", Duration.ofMillis(1)));
    }

    void awaitStarted() throws InterruptedException {
        if (!started.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("the command never reached the shell");
        }
    }

    boolean wasStarted() {
        return started.getCount() == 0;
    }

    ExecutionOptions lastOptions() {
        return lastOptions;
    }

    @Override
    public ShellCommandResult execute(ShellCommand command) throws ShellExecutionException {
        return execute(command, ExecutionOptions.defaults());
    }

    @Override
    public ShellCommandResult execute(ShellCommand command, ExecutionOptions options) throws ShellExecutionException {
        lastOptions = options;
        final ShellCancellation.Registration registration = cancellable
                ? options.getCancellation()
                        .onCancel(() -> outcome.completeExceptionally(new ShellCancelledException(
                                "Process cancelled: " + command.asString(), PARTIAL_OUTPUT, "", false)))
                : () -> {
                };
        started.countDown();
        try {
            return outcome.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ShellExecutionException shellFailure) {
                throw shellFailure;
            }
            throw new ShellExecutionException("command failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ShellExecutionException("Interrupted while waiting for process", e);
        } finally {
            registration.remove();
        }
    }

    @Override
    public String getWorkingDirectory() {
        return null;
    }

    @Override
    public boolean supports(ShellFeature feature) {
        return cancellable && feature == ShellFeature.CANCELLATION;
    }

    @Override
    public void close() {
        // Nothing to release.
    }
}
