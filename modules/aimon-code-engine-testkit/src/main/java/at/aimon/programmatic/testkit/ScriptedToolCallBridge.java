package at.aimon.programmatic.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.programmatic.ToolCallBridge;

/**
 * A {@link ToolCallBridge} that answers from a script, for the engine contract suite. It completes every call on a
 * thread of its own, as the real bridge does, records what it was asked, and owns the cancellation signal the program
 * runs under — tripped by {@link #cancel()} (the execution was cancelled) or by a call past {@link #stopAfter(int)} (a
 * limit the bridge stops the program for, reported by {@link #limitExceeded()}).
 *
 * <p>
 * Thread-safe. Close it when the test ends.
 */
public final class ScriptedToolCallBridge implements ToolCallBridge, AutoCloseable {

    /** One call the program made. */
    public static final class Call {
        private final String toolName;
        private final Map<String, Object> input;

        Call(String toolName, Map<String, Object> input) {
            this.toolName = toolName;
            this.input = input;
        }

        /**
         * @return the tool's name
         */
        public String getToolName() {
            return toolName;
        }

        /**
         * @return the arguments as the engine detached them
         */
        public Map<String, Object> getInput() {
            return input;
        }
    }

    private final Function<Call, ToolDispatchResult> answers;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "scripted-bridge");
        thread.setDaemon(true);
        return thread;
    });
    private final DefaultInterruptCoordinator signal = new DefaultInterruptCoordinator();
    private final List<Call> calls = new ArrayList<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicReference<String> limitExceeded = new AtomicReference<>();
    private volatile int stopAfter = Integer.MAX_VALUE;
    private volatile boolean hold;

    private ScriptedToolCallBridge(Function<Call, ToolDispatchResult> answers) {
        this.answers = answers;
    }

    /**
     * Returns a bridge that answers each call with {@code answers}.
     *
     * @param answers
     *            the answer for a call (must not be null)
     * @return a new bridge (never null)
     */
    public static ScriptedToolCallBridge answering(Function<Call, ToolDispatchResult> answers) {
        return new ScriptedToolCallBridge(Objects.requireNonNull(answers, "answers cannot be null"));
    }

    /**
     * Returns a bridge that answers every call with the same successful text.
     *
     * @param text
     *            the result text (must not be null)
     * @return a new bridge (never null)
     */
    public static ScriptedToolCallBridge succeedingWith(String text) {
        Objects.requireNonNull(text, "text cannot be null");
        return answering(call -> success(text));
    }

    /**
     * Returns a successful result.
     *
     * @param text
     *            the text
     * @return the result
     */
    public static ToolDispatchResult success(String text) {
        return ToolDispatchResult.builder().toolUseId("t").text(text).build();
    }

    /**
     * Returns an error result.
     *
     * @param text
     *            the error text
     * @return the result
     */
    public static ToolDispatchResult error(String text) {
        return ToolDispatchResult.builder().toolUseId("t").text(text).error(true).build();
    }

    /**
     * Makes every later call stay pending forever (until the program is stopped).
     *
     * @return this bridge
     */
    public ScriptedToolCallBridge holdCalls() {
        this.hold = true;
        return this;
    }

    /**
     * Stops the program at the call after {@code count}: that call is refused, {@link #limitExceeded()} says why, and
     * the signal trips — as the real bridge does at its nested-call limit.
     *
     * @param count
     *            how many calls are answered
     * @return this bridge
     */
    public ScriptedToolCallBridge stopAfter(int count) {
        this.stopAfter = count;
        return this;
    }

    /**
     * Returns the signal to run the program under.
     *
     * @return the signal (never null)
     */
    public CancellationSignal signal() {
        return signal.getSignal();
    }

    /** Cancels the execution the program belongs to. */
    public void cancel() {
        signal.requestInterrupt(InterruptReason.USER_SIGINT);
    }

    /**
     * Returns the calls made so far, in arrival order.
     *
     * @return a snapshot (never null)
     */
    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    @Override
    public CompletableFuture<ToolDispatchResult> callAsync(String toolName, Map<String, Object> input) {
        Objects.requireNonNull(toolName, "toolName cannot be null");
        Objects.requireNonNull(input, "input cannot be null");
        final Call call = new Call(toolName, Map.copyOf(withoutNulls(input)));
        synchronized (this) {
            calls.add(call);
        }
        if (sequence.incrementAndGet() > stopAfter) {
            if (limitExceeded.compareAndSet(null, "stopped after " + stopAfter + " calls")) {
                signal.requestInterrupt(InterruptReason.TASK_CANCELLED);
            }
            return CompletableFuture.completedFuture(error("refused: call limit"));
        }
        if (hold) {
            return new CompletableFuture<>();
        }
        return CompletableFuture.supplyAsync(() -> answers.apply(call), executor);
    }

    @Override
    public Optional<String> limitExceeded() {
        return Optional.ofNullable(limitExceeded.get());
    }

    @Override
    public void close() {
        executor.shutdownNow();
        signal.close();
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> input) {
        final Map<String, Object> copy = new java.util.LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (value != null) {
                copy.put(key, value);
            }
        });
        return copy;
    }
}
