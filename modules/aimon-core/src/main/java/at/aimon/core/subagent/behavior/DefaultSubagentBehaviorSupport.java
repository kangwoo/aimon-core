package at.aimon.core.subagent.behavior;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.invoke.LlmCallGateway;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.execution.SubagentExecutionContext;
import at.aimon.core.subagent.execution.SubagentExecutionRequest;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.subagent.execution.SubagentInterrupts;
import at.aimon.core.subagent.execution.SubagentLlmDefaults;

/**
 * Default {@link SubagentBehaviorSupport} created by {@link SubagentBehaviorRunner} once per code-behavior execution.
 *
 * <p>
 * Derives the parity values a behavior may want — the subagent-resolved model and the allow-list-scoped tool registry,
 * plus subagent-attributed metadata — from the execution context, so they match what the ReAct path uses. Result
 * shaping delegates to {@link SubagentExecutionResult#emptySuccess(String, java.time.Instant)} /
 * {@link SubagentExecutionResult#emptyFailure(String, java.time.Instant)} (empty session snapshot, zero-cost
 * metadata). Package-private; behaviors receive it only through the {@link SubagentBehaviorSupport} interface.
 */
final class DefaultSubagentBehaviorSupport implements SubagentBehaviorSupport {

    private final SubagentExecutionContext executionContext;
    private final Instant startTime;
    private final LlmCallGateway<TranscriptBuffer> llmGateway;
    private final LlmCallMetadata effectiveLlmCallMetadata;
    private final LlmModel resolvedModel;
    private final ToolRegistry scopedToolRegistry;

    DefaultSubagentBehaviorSupport(SubagentExecutionContext executionContext, SubagentExecutionRequest request,
            Instant startTime, LlmCallGateway<TranscriptBuffer> llmGateway) {
        this.executionContext = Objects.requireNonNull(executionContext, "executionContext cannot be null");
        Objects.requireNonNull(request, "request cannot be null");
        this.startTime = Objects.requireNonNull(startTime, "startTime cannot be null");
        this.llmGateway = llmGateway; // nullable: empty Optional when no LLM client is wired
        final Subagent subagent = executionContext.getSubagent();
        this.effectiveLlmCallMetadata = SubagentLlmDefaults.effectiveMetadata(subagent.getName(),
                request.getLlmCallMetadata());
        this.resolvedModel = SubagentLlmDefaults.resolveModel(subagent, executionContext.getDefaultModel(),
                executionContext.getModelOverride().orElse(null));
        this.scopedToolRegistry = scope(executionContext.getToolRegistry(), subagent);
    }

    @Override
    public CancellationSignal cancellationSignal() {
        return executionContext.getParentCancellationSignal();
    }

    @Override
    public boolean isCancelledOrInterrupted() {
        return SubagentInterrupts.isCancelledOrInterrupted(cancellationSignal());
    }

    @Override
    public SubagentExecutionResult success(String finalAnswer) {
        return SubagentExecutionResult.emptySuccess(finalAnswer, startTime);
    }

    @Override
    public SubagentExecutionResult failure(String errorMessage) {
        return SubagentExecutionResult.emptyFailure(errorMessage, startTime);
    }

    @Override
    public Optional<LlmCallGateway<TranscriptBuffer>> llmGateway() {
        return Optional.ofNullable(llmGateway);
    }

    @Override
    public LlmCallMetadata effectiveLlmCallMetadata() {
        return effectiveLlmCallMetadata;
    }

    @Override
    public LlmModel resolvedModel() {
        return resolvedModel;
    }

    @Override
    public ToolRegistry scopedToolRegistry() {
        return scopedToolRegistry;
    }

    /**
     * Returns a registry containing only the subagent's allowed tools (matched by name), or the full registry when the
     * subagent declares no tool restrictions. This exposes — but does not enforce — the same allow-list the ReAct path
     * applies at dispatch; a behavior is trusted code and may still reach the full registry via
     * {@code context.getToolRegistry()}.
     */
    private static ToolRegistry scope(ToolRegistry full, Subagent subagent) {
        if (!subagent.hasToolRestrictions()) {
            return full;
        }
        final Set<String> allowedNames = subagent.getAllowedTools().stream().map(AllowedTool::getToolName)
                .collect(Collectors.toUnmodifiableSet());
        final DefaultToolRegistry scoped = new DefaultToolRegistry();
        for (Tool tool : full.findAll()) {
            if (allowedNames.contains(tool.getDefinition().getName())) {
                scoped.register(tool);
            }
        }
        return scoped;
    }
}
