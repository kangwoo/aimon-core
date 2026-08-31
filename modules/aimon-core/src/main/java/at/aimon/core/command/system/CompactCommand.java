package at.aimon.core.command.system;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.compact.CompactionEngine;
import at.aimon.core.agent.compact.CompactionGuard;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionRequest;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.base.Principal;
import at.aimon.core.command.SystemCommand;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.command.execution.direct.DirectExecutable;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.OnStopContext;
import at.aimon.core.llm.LlmCallMetadata;

/**
 * Built-in command that performs a MANUAL conversation compaction.
 *
 * <p>
 * The compact command:
 *
 * <ul>
 * <li>Builds a {@link CompactionRequest} with {@link CompactionTrigger#MANUAL} and caller-supplied
 * {@link LlmCallMetadata} (principal + traceId) so the summary LLM call is properly attributed to the invoking user
 * <li>Runs it through the configured {@link CompactionEngine}
 * <li>Resets the {@link CompactionGuard}'s circuit breaker on success so AUTO compactions can resume
 * <li>Fires {@code OnStopHook} after the compaction completes — observability tools that listen for execution-stop
 * events therefore see MANUAL compactions on the same channel as agent runs
 * <li>Returns a short summary describing the resulting token reduction
 * </ul>
 *
 * <p>
 * Usage:
 *
 * <pre>
 * /compact                            - Compact with the default summary prompt
 * /compact &lt;custom instructions&gt;     - Forward custom advisory guidance to the summary LLM call
 * </pre>
 *
 * <p>
 * MANUAL trigger semantics differ from AUTO: PreCompactHook blocks are downgraded to advisory warnings rather than
 * aborting the request, so the user can always force a compaction interactively.
 *
 * <p>
 * Thread-safe: collaborators are themselves thread-safe and the command holds no mutable state.
 */
public final class CompactCommand extends SystemCommand implements DirectExecutable {

    public static final String COMMAND_NAME = "compact";

    /** {@code component} value attached to the LLM call metadata so usage attribution can identify /compact calls. */
    static final String COMPONENT_NAME = "compact-command";

    /** {@code invokerName} attached to the OnStop context for observability tools. */
    static final String ON_STOP_INVOKER_NAME = "/compact";

    private static final Logger log = LoggerFactory.getLogger(CompactCommand.class);

    private final CompactionEngine compactionEngine;
    private final CompactionGuard compactionGuard;
    private final HookRegistry hookRegistry;
    private final HookExecutionManager hookExecutionManager;
    private final Environment environment;

    /**
     * Creates a new CompactCommand.
     *
     * @param compactionEngine
     *            the engine that performs the L3 summarization (must not be null)
     * @param compactionGuard
     *            the guard whose circuit-breaker counter is reset on successful MANUAL compactions (must not be null)
     * @param hookRegistry
     *            the hook registry consulted for PreCompact / PostCompact hooks (must not be null)
     * @param hookExecutionManager
     *            invoked to fire {@code OnStopHook} after the compaction completes (must not be null)
     * @param environment
     *            the runtime environment forwarded to hook contexts (must not be null)
     */
    public CompactCommand(CompactionEngine compactionEngine, CompactionGuard compactionGuard, HookRegistry hookRegistry,
            HookExecutionManager hookExecutionManager, Environment environment) {
        super(COMMAND_NAME, "Manually compact the current conversation");
        this.compactionEngine = Objects.requireNonNull(compactionEngine, "compactionEngine cannot be null");
        this.compactionGuard = Objects.requireNonNull(compactionGuard, "compactionGuard cannot be null");
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "hookRegistry cannot be null");
        this.hookExecutionManager = Objects.requireNonNull(hookExecutionManager, "hookExecutionManager cannot be null");
        this.environment = Objects.requireNonNull(environment, "environment cannot be null");
    }

    @Override
    public CommandExecutionResult execute(CommandExecutionContext context, DirectCommandExecutionRequest request) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(request, "request cannot be null");

        final TranscriptBuffer memory = context.getTranscriptBuffer();
        if (memory == null) {
            return CommandExecutionResult.success("No active conversation to compact.");
        }
        if (memory.getMessages().isEmpty()) {
            return CommandExecutionResult.success("Conversation is empty; nothing to compact.");
        }

        final String customInstructions = request.getArguments().map(String::trim).filter(s -> !s.isEmpty())
                .orElse(null);

        final LlmCallMetadata callMetadata = buildCallMetadata(memory, request.getPrincipal().orElse(null));

        final CompactionRequest compactionRequest = CompactionRequest.builder().transcriptBuffer(memory)
                .trigger(CompactionTrigger.MANUAL).model(context.getDefaultModel()).hookRegistry(hookRegistry)
                .environment(environment).customInstructions(customInstructions).callMetadata(callMetadata).build();

        final Instant startedAt = Instant.now();
        CompactionResult result = null;
        RuntimeException unexpected = null;
        try {
            result = compactionEngine.compact(compactionRequest);
        } catch (RuntimeException e) {
            unexpected = e;
            log.error("MANUAL compaction failed unexpectedly: {}", e.getMessage(), e);
        }
        final Instant completedAt = Instant.now();

        final CommandExecutionResult commandResult = buildCommandResult(result, unexpected);
        invokeOnStop(commandResult, startedAt, completedAt);

        if (commandResult.isSuccess()) {
            compactionGuard.recordExternalSuccess(memory.getSessionId());
        }
        return commandResult;
    }

    private CommandExecutionResult buildCommandResult(CompactionResult result, RuntimeException unexpected) {
        if (unexpected != null) {
            return CommandExecutionResult.failure("Compaction failed: " + unexpected.getMessage(), unexpected);
        }
        if (result == null) {
            // Defensive: CompactionEngine#compact contract does not explicitly forbid null. Treat as a failure rather
            // than NPE-ing on result.isFailure() so a misbehaving engine cannot silently break the /compact response.
            final String reason = "engine returned null result";
            log.error("MANUAL compaction returned null: {}", reason);
            return CommandExecutionResult.failure("Compaction failed: " + reason, new IllegalStateException(reason));
        }
        if (result.isFailure()) {
            final String reason = result.getError().map(err -> err.getClass().getSimpleName() + ": " + err.getMessage())
                    .orElse("unknown error");
            log.warn("MANUAL compaction returned failure: {}", reason);
            return CommandExecutionResult.failure("Compaction failed: " + reason,
                    result.getError().orElse(new IllegalStateException(reason)));
        }
        return CommandExecutionResult.success(formatSuccessMessage(result.getMetadata()));
    }

    private LlmCallMetadata buildCallMetadata(TranscriptBuffer memory, Principal principal) {
        final LlmCallMetadata.Builder builder = LlmCallMetadata.builder().component(COMPONENT_NAME)
                .feature(LlmCallMetadata.Feature.COMPACTION).traceId(memory.getSessionId().toString());
        if (principal != null) {
            builder.principal(principal);
        }
        return builder.build();
    }

    private void invokeOnStop(CommandExecutionResult commandResult, Instant startedAt, Instant completedAt) {
        try {
            final ExecutionMetadata metadata = ExecutionMetadata.simple(safeDuration(startedAt, completedAt), startedAt,
                    completedAt);
            final OnStopContext onStopContext = OnStopContext.builder().executorType(InvokerType.mainAgent())
                    .invokerName(ON_STOP_INVOKER_NAME).hookRegistry(hookRegistry).environment(environment)
                    .success(commandResult.isSuccess()).finalAnswer(commandResult.getResponse()).metadata(metadata)
                    .timestamp(completedAt).build();
            hookExecutionManager.executeOnStop(onStopContext);
        } catch (RuntimeException e) {
            // OnStopHooks are observability-only; never let them break a /compact response.
            log.warn("OnStopHook execution after MANUAL compaction failed: {}", e.getMessage(), e);
        }
    }

    private static Duration safeDuration(Instant start, Instant end) {
        final Duration d = Duration.between(start, end);
        return d.isNegative() ? Duration.ZERO : d;
    }

    private static String formatSuccessMessage(CompactionMetadata metadata) {
        final long durationMs = safeDuration(metadata.getStartedAt(), metadata.getCompletedAt()).toMillis();
        return String.format("Conversation compacted: %d messages summarized, %d → %d tokens (%dms).",
                metadata.getMessagesSummarized(), metadata.getPreCompactTokenCount(),
                metadata.getPostCompactTokenCount(), durationMs);
    }
}
