package at.aimon.core.tools.code;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.tool.ConcurrencyBehavior;
import at.aimon.core.agent.tool.InterruptToolKeys;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolCallDispatcher;
import at.aimon.core.agent.tool.ToolCallOrigin;
import at.aimon.core.agent.tool.ToolCallQuota;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDescriptor;
import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.ToolCallBridge;
import at.aimon.core.tools.ToolContextKeys;

/**
 * The bridge of one {@code ExecuteCode} call ({@code docs/design/tool/programmatic-tool-calling.md} §8, §9, §13): the
 * part of running a program that does not depend on its language.
 *
 * <p>
 * Before a call reaches the dispatcher it is held to, in order: the {@code CLOSED} state, the nested-call limit (one
 * more stops the program), the frozen callable set, the argument size, the per-tool cap on {@code MUTATING} calls, and
 * the execution's {@link ToolCallQuota} (a refusal stops the program). It then runs on the {@code CODE} path through
 * the iteration's {@link ToolCallDispatcher} — the gates are the direct path's — on one of the bridge's threads:
 * {@code CONCURRENT_SAFE} tools side by side up to the concurrency limit, every other tool in one sequential lane.
 *
 * <p>
 * Each nested call runs with the parent's tool context, its own tool-use id ({@code <parent>#<n>}) and the bridge's
 * child cancellation signal, which trips when the parent's does, when the bridge stops the program, and when the
 * program ends ({@link #close(Duration)}). Notices and hook feedback are collected apart, for the observation, rather
 * than handed to the program inside a result.
 *
 * <p>
 * Thread-safe. One per {@code ExecuteCode} call; {@link #close(Duration)} once.
 */
final class DefaultToolCallBridge implements ToolCallBridge {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolCallBridge.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicLong THREAD_COUNTER = new AtomicLong();

    private final ToolCallDispatcher dispatcher;
    private final ToolContext parentContext;
    private final String parentToolUseId;
    private final String language;
    private final Map<String, ToolDescriptor> callable;
    private final CodeExecutionLimits limits;
    private final ToolCallQuota quota;

    private final DefaultInterruptCoordinator child = new DefaultInterruptCoordinator();
    private final CancellationSignal.Registration parentRegistration;
    /** Runs {@code CONCURRENT_SAFE} tools side by side. */
    private final ExecutorService executor;
    /** The sequential lane: every other tool, one at a time, on a thread of its own so it never parks a pool worker. */
    private final ExecutorService sequentialExecutor;

    private final AtomicInteger sequence = new AtomicInteger();
    private final Map<String, AtomicInteger> mutatingCalls = new ConcurrentHashMap<>();
    private final AtomicReference<String> limitExceeded = new AtomicReference<>();
    private final List<NestedToolCall> records = new ArrayList<>();
    private final Set<String> notices = new LinkedHashSet<>();
    private final Set<String> hookFeedback = new LinkedHashSet<>();
    private final Map<CompletableFuture<ToolDispatchResult>, String> inFlight = new ConcurrentHashMap<>();
    private volatile boolean closed;

    DefaultToolCallBridge(ToolCallDispatcher dispatcher, ToolContext parentContext, String parentToolUseId,
            String language, List<ToolDescriptor> callable, CodeExecutionLimits limits,
            CancellationSignal parentSignal) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher cannot be null");
        this.parentContext = Objects.requireNonNull(parentContext, "parentContext cannot be null");
        this.parentToolUseId = Objects.requireNonNull(parentToolUseId, "parentToolUseId cannot be null");
        this.language = Objects.requireNonNull(language, "language cannot be null");
        final Map<String, ToolDescriptor> byName = new LinkedHashMap<>();
        for (ToolDescriptor descriptor : Objects.requireNonNull(callable, "callable cannot be null")) {
            byName.put(descriptor.getName(), descriptor);
        }
        this.callable = Map.copyOf(byName);
        this.limits = Objects.requireNonNull(limits, "limits cannot be null");
        this.quota = parentContext.get(ToolContextKeys.TOOL_CALL_QUOTA).orElse(null);
        this.parentRegistration = parentSignal == null
                ? CancellationSignal.Registration.NONE
                : parentSignal.onCancel(() -> child.requestInterrupt(InterruptReason.PARENT_CANCELLED));
        // The concurrency limit covers both lanes: the sequential lane takes one of its slots.
        final int threads = Math.max(1, limits.getMaxConcurrentCalls() - 1);
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), DefaultToolCallBridge::daemon);
        pool.allowCoreThreadTimeOut(true);
        this.executor = pool;
        this.sequentialExecutor = Executors.newSingleThreadExecutor(DefaultToolCallBridge::daemon);
    }

    private static Thread daemon(Runnable runnable) {
        final Thread thread = new Thread(runnable, "execute-code-call-" + THREAD_COUNTER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }

    /**
     * Returns the signal a program is stopped by: tripped with the parent's, when the bridge stops the program for a
     * limit, and when the program has ended.
     */
    CancellationSignal childSignal() {
        return child.getSignal();
    }

    @Override
    public Optional<String> limitExceeded() {
        return Optional.ofNullable(limitExceeded.get());
    }

    @Override
    public CompletableFuture<ToolDispatchResult> callAsync(String toolName, Map<String, Object> input) {
        Objects.requireNonNull(toolName, "toolName cannot be null");
        Objects.requireNonNull(input, "input cannot be null");
        // Every call the program asks for counts toward the nested-call limit, refused ones too: the limit bounds
        // what the program attempts, not what the gates let through.
        final int number = sequence.incrementAndGet();
        final String toolUseId = parentToolUseId + "#" + number;
        if (closed) {
            return refuse(toolUseId, toolName, input, "closed", "ExecuteCode has ended; the call was not run");
        }
        if (number > limits.getMaxNestedCalls()) {
            final String reason = "the program made more than " + limits.getMaxNestedCalls() + " tool calls";
            stop(reason);
            return refuse(toolUseId, toolName, input, "call limit", "Tool call refused: " + reason);
        }
        final ToolDescriptor descriptor = callable.get(toolName);
        if (descriptor == null) {
            return refuse(toolUseId, toolName, input, "not callable", "Tool '" + toolName
                    + "' cannot be called from code here; callable tools: " + String.join(", ", callable.keySet()));
        }
        final int argumentBytes = argumentBytes(input);
        if (argumentBytes > limits.getMaxArgumentBytes()) {
            return refuse(toolUseId, toolName, input, "argument size", "Tool call refused: arguments of "
                    + argumentBytes + " bytes exceed the limit of " + limits.getMaxArgumentBytes() + " bytes");
        }
        final AtomicInteger writes = descriptor.getSideEffectLevel() == SideEffectLevel.MUTATING
                ? mutatingCalls.computeIfAbsent(toolName, name -> new AtomicInteger())
                : null;
        if (writes != null) {
            final int cap = limits.getMaxMutatingCallsPerTool();
            if (writes.incrementAndGet() > cap) {
                writes.decrementAndGet();
                return refuse(toolUseId, toolName, input, "write cap", "Tool call refused: limit " + cap
                        + " per script for " + toolName + "; " + cap + " already applied");
            }
        }
        if (quota != null && quota.admit(1) == 0) {
            if (writes != null) {
                writes.decrementAndGet(); // the call did not run, so it took no write slot
            }
            final String reason = "the execution's tool-call budget is used up"
                    + quota.getLimit().map(limit -> " (" + limit + " calls)").orElse("");
            stop(reason);
            return refuse(toolUseId, toolName, input, "quota", "Tool call refused: " + reason);
        }
        return dispatchAsync(toolUseId, descriptor, input);
    }

    private CompletableFuture<ToolDispatchResult> dispatchAsync(String toolUseId, ToolDescriptor descriptor,
            Map<String, Object> input) {
        final String toolName = descriptor.getName();
        // With a concurrency limit of 1 every call takes the sequential lane, so two never run at once.
        final boolean concurrent = descriptor.getConcurrencyBehavior() == ConcurrencyBehavior.CONCURRENT_SAFE
                && limits.getMaxConcurrentCalls() > 1;
        final CompletableFuture<ToolDispatchResult> result = new CompletableFuture<>();
        inFlight.put(result, toolName);
        try {
            (concurrent ? executor : sequentialExecutor).execute(() -> {
                final long started = System.nanoTime();
                ToolDispatchResult dispatched;
                try {
                    dispatched = dispatch(toolUseId, toolName, input);
                } catch (RuntimeException e) {
                    log.error("Nested tool call failed outside the dispatcher: tool={}, toolUseId={}", toolName,
                            toolUseId, e);
                    dispatched = error(toolUseId, "Tool execution error: " + e.getMessage());
                }
                collect(dispatched);
                record(new NestedToolCall(toolUseId, toolName, summarize(input), dispatched.getText().length(),
                        dispatched.isError(), null, (System.nanoTime() - started) / 1_000_000));
                inFlight.remove(result);
                // After CLOSED a result reaches the record and nothing else: the program it belonged to is gone.
                result.complete(closed ? error(toolUseId, "ExecuteCode has ended") : dispatched);
            });
        } catch (RejectedExecutionException e) {
            inFlight.remove(result);
            return refuse(toolUseId, toolName, input, "closed", "ExecuteCode has ended; the call was not run");
        }
        return result;
    }

    private ToolDispatchResult dispatch(String toolUseId, String toolName, Map<String, Object> input) {
        if (child.getSignal().isCancelled()) {
            return error(toolUseId, "Tool call cancelled before it ran");
        }
        final ToolContext context = ToolContext.builder().putAll(parentContext.getContext())
                .put(ToolContextKeys.CURRENT_TOOL_USE_ID_KEY, toolUseId)
                .put(InterruptToolKeys.CANCELLATION_SIGNAL, child.getSignal()).build();
        return dispatcher.dispatch(ToolUse.of(toolUseId, toolName, input),
                ToolCallOrigin.code(parentToolUseId, language), context);
    }

    /**
     * Ends the bridge: refuses every later call, trips the child signal, and waits — at most {@code wait} — for the
     * calls still running, as the direct path waits for a sequential tool before it moves on (§13.2).
     *
     * @param wait
     *            the most to wait (must not be null)
     * @return the names of the tools still running when the wait ran out, in no order (never null)
     */
    List<String> close(Duration wait) {
        Objects.requireNonNull(wait, "wait cannot be null");
        closed = true;
        parentRegistration.remove();
        child.requestInterrupt(InterruptReason.TASK_CANCELLED);
        final List<CompletableFuture<ToolDispatchResult>> pending = new ArrayList<>(inFlight.keySet());
        try {
            CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0])).get(Math.max(1, wait.toMillis()),
                    TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("ExecuteCode {}: {} nested call(s) still running when the script ended", parentToolUseId,
                    inFlight.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Waiting for nested calls of {} ended with {}", parentToolUseId, e.toString());
        } finally {
            executor.shutdown();
            sequentialExecutor.shutdown();
            child.close();
        }
        return List.copyOf(inFlight.values());
    }

    /** The calls the program made, in the order they finished. */
    synchronized List<NestedToolCall> records() {
        return List.copyOf(records);
    }

    /** The environment notices of the nested calls, deduplicated, in first-seen order. */
    synchronized List<String> notices() {
        return List.copyOf(notices);
    }

    /** The hook feedback of the nested calls, deduplicated, in first-seen order. */
    synchronized List<String> hookFeedback() {
        return List.copyOf(hookFeedback);
    }

    private void stop(String reason) {
        if (limitExceeded.compareAndSet(null, reason)) {
            log.info("ExecuteCode {} stopped: {}", parentToolUseId, reason);
            child.requestInterrupt(InterruptReason.TASK_CANCELLED);
        }
    }

    private CompletableFuture<ToolDispatchResult> refuse(String toolUseId, String toolName, Map<String, Object> input,
            String kind, String message) {
        final ToolDispatchResult refused = error(toolUseId, message);
        record(new NestedToolCall(toolUseId, toolName, summarize(input), message.length(), true, kind, 0));
        return CompletableFuture.completedFuture(refused);
    }

    private synchronized void record(NestedToolCall call) {
        records.add(call);
    }

    private synchronized void collect(ToolDispatchResult result) {
        notices.addAll(result.getNotices());
        hookFeedback.addAll(result.getHookFeedback());
    }

    private static ToolDispatchResult error(String toolUseId, String message) {
        return ToolDispatchResult.builder().toolUseId(toolUseId).text(message).error(true).build();
    }

    private static int argumentBytes(Map<String, Object> input) {
        try {
            return JSON.writeValueAsBytes(input).length;
        } catch (JsonProcessingException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static String summarize(Map<String, Object> input) {
        String text;
        try {
            text = JSON.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            text = String.valueOf(input);
        }
        return text.length() <= NestedToolCall.MAX_INPUT_SUMMARY_CHARS
                ? text
                : text.substring(0, NestedToolCall.MAX_INPUT_SUMMARY_CHARS) + "…";
    }
}
