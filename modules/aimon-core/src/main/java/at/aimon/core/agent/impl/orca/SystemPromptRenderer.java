package at.aimon.core.agent.impl.orca;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentContent;
import at.aimon.core.agent.AgentContentRenderer;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.context.ContextBlock;
import at.aimon.core.agent.context.ContextBlockKind;
import at.aimon.core.agent.prompt.Staticness;
import at.aimon.core.agent.prompt.SystemPromptPart;
import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.memory.MemoryContextProvider;
import at.aimon.core.memory.MemoryContextRequest;

/**
 * Renders the dynamic system prompt for a turn, both as the structured {@link SystemPromptParts} form (passed to the
 * parts-aware LLM client) and as the legacy concatenated single-{@code String} form (stored in transcript buffer).
 *
 * <p>
 * Extracted from {@link OrcaAgentExecutor} (a god class) as a cohesive, independently-testable collaborator owning a
 * single responsibility: assembling the system prompt from the agent-content template, the runtime environment block,
 * the optional memory-context part, and any assembled {@link ContextBlockKind#SYSTEM SYSTEM}-kind context blocks.
 *
 * <p>
 * <b>Package-private by design:</b> this is an implementation detail of the Orca executor and is not part of any
 * public API.
 *
 * <p>
 * <b>Invariant.</b> {@code buildSystemPromptParts(...).concatenated()} equals the legacy single-{@code String} form the
 * executor previously produced; the system-prompt regression tests enforce that the LLM sees bit-equal text.
 */
final class SystemPromptRenderer {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptRenderer.class);

    private final AgentContentRenderer agentContentRenderer;
    private final MemoryContextProvider memoryContextProvider;

    /**
     * Creates a renderer.
     *
     * @param agentContentRenderer
     *            renders the agent-content template into prompt parts (must not be null)
     * @param memoryContextProvider
     *            supplies the optional memory-context part, or {@code null} to omit it
     * @throws NullPointerException
     *             if {@code agentContentRenderer} is null
     */
    SystemPromptRenderer(AgentContentRenderer agentContentRenderer, MemoryContextProvider memoryContextProvider) {
        this.agentContentRenderer = Objects.requireNonNull(agentContentRenderer, "agentContentRenderer cannot be null");
        this.memoryContextProvider = memoryContextProvider;
    }

    /**
     * Derives the legacy single-{@code String} system prompt from already-built {@code parts}.
     *
     * <p>
     * Deriving it from the parts (rather than re-rendering) ensures {@code memoryContextProvider.provide()} is invoked
     * exactly once per turn. The {@code concatenated()} form is required to be bit-equal to the previous
     * {@link StringBuilder}-based implementation — see {@code OrcaAgentExecutorSystemPromptTest}.
     *
     * @param systemPromptParts
     *            the structured prompt already built for this turn
     * @param agentContent
     *            the agent content containing the base system prompt
     * @param systemPromptVariables
     *            the system prompt variables (can be empty)
     * @param environment
     *            the runtime environment (can be null)
     * @return the system prompt with dynamically injected information
     */
    String renderSystemPromptString(SystemPromptParts systemPromptParts, AgentContent agentContent,
            Map<String, Object> systemPromptVariables, Environment environment) {
        // Edge case: when the rendered agent content is empty, the previous implementation produced
        // "\n\n" + envBlock (empty template joined to env with a blank-line separator). Preserve that
        // by checking the rendered content here rather than relying on SystemPromptParts.concatenated(),
        // which would elide the empty-content part entirely.
        final String renderedAgentContent = agentContentRenderer.render(agentContent, systemPromptVariables);
        if (renderedAgentContent.isEmpty() && environment != null) {
            return "\n\n" + buildEnvironmentBlock(environment);
        }
        return systemPromptParts.concatenated();
    }

    /**
     * Builds the structured system prompt as an ordered list of {@link SystemPromptParts parts}.
     *
     * <p>
     * Each segment of the prompt is emitted as its own part with a conventional {@code kind} label and a
     * {@link Staticness} classification, so downstream LLM provider adapters can later attach cache boundaries at
     * sensible seams (tracked separately as CTX-05).
     *
     * @param agentContent
     *            the agent content containing the base system prompt (must not be null)
     * @param systemPromptVariables
     *            the system prompt variables (must not be null, may be empty)
     * @param environment
     *            the runtime environment, or {@code null} to omit the environment segment
     * @return the structured prompt; never {@code null}
     */
    SystemPromptParts buildSystemPromptParts(AgentContent agentContent, Map<String, Object> systemPromptVariables,
            Environment environment) {
        return buildSystemPromptParts(agentContent, systemPromptVariables, environment, List.of(),
                MemoryContextRequest.empty());
    }

    /**
     * Overload of {@link #buildSystemPromptParts(AgentContent, Map, Environment)} that additionally appends the
     * {@link ContextBlockKind#SYSTEM SYSTEM}-kind blocks assembled by the wired context assembler.
     *
     * <p>
     * The assembled blocks are appended after the memory segment as their own parts, tagged {@code context:<key>}.
     * Non-SYSTEM blocks are ignored here (they are injected as synthetic user messages). When
     * {@code assembledContext} is empty — always the case under the NOOP assembler — the output is bit-identical to the
     * 3-arg form, preserving the {@code concatenated()} invariant enforced by the system-prompt regression tests.
     *
     * @param agentContent
     *            the agent content containing the base system prompt (must not be null)
     * @param systemPromptVariables
     *            the system prompt variables (must not be null, may be empty)
     * @param environment
     *            the runtime environment, or {@code null} to omit the environment segment
     * @param assembledContext
     *            the blocks assembled for this turn (must not be null; SYSTEM blocks are appended, others ignored)
     * @param memoryContextRequest
     *            the identity of the execution being assembled, handed to the memory provider (must not be null; use
     *            {@link MemoryContextRequest#empty()} when there is neither a session nor a caller)
     * @return the structured prompt; never {@code null}
     */
    SystemPromptParts buildSystemPromptParts(AgentContent agentContent, Map<String, Object> systemPromptVariables,
            Environment environment, List<ContextBlock> assembledContext, MemoryContextRequest memoryContextRequest) {
        Objects.requireNonNull(agentContent, "Agent content cannot be null");
        Objects.requireNonNull(systemPromptVariables, "System prompt variables cannot be null");
        Objects.requireNonNull(assembledContext, "Assembled context cannot be null");
        Objects.requireNonNull(memoryContextRequest, "Memory context request cannot be null");

        final List<SystemPromptPart> collected = new ArrayList<>(2);

        // Segment 1: rendered agent content (template + variables).
        // Delegating to renderAsParts preserves any finer-grained segmentation a renderer chooses to emit.
        final SystemPromptParts contentParts = agentContentRenderer.renderAsParts(agentContent, systemPromptVariables);
        collected.addAll(contentParts.parts());

        // Segment 2: environment block, only when available.
        if (environment != null) {
            collected.add(SystemPromptPart.builder().content(buildEnvironmentBlock(environment))
                    .staticness(Staticness.DYNAMIC).kind("environment").build());
        }

        // Segment 3: SK-MEM Stage 9 memory part (latest peer Representation), only when a provider is configured
        // and yields a part. The request carries this execution's own session and caller, so one agent-scoped
        // provider serves every session without any of them seeing another's representation. Failures inside the
        // provider are swallowed so prompt assembly never breaks because of memory.
        if (memoryContextProvider != null) {
            try {
                memoryContextProvider.provide(memoryContextRequest).ifPresent(collected::add);
            } catch (RuntimeException e) {
                log.warn("MemoryContextProvider failed; skipping memory part: {}", e.getMessage());
            }
        }

        // Segment 4: assembled SYSTEM context blocks. Empty under the NOOP assembler, so the invariant holds.
        for (ContextBlock block : assembledContext) {
            if (block.getKind() == ContextBlockKind.SYSTEM) {
                collected.add(SystemPromptPart.builder().content(block.getBody())
                        .staticness(block.isCacheable() ? Staticness.STATIC : Staticness.DYNAMIC)
                        .kind("context:" + block.getKey()).build());
            }
        }

        return SystemPromptParts.of(collected);
    }

    /**
     * Builds the verbatim environment block, identical to the block the pre-CTX-04 {@link StringBuilder}-based
     * implementation appended.
     */
    private static String buildEnvironmentBlock(Environment environment) {
        return "Here is useful information about the environment you are running in:\n\n" + "**Environment:**\n"
                + "```\n" + "Working directory: " + environment.getWorkingDirectory() + '\n' + "Platform: "
                + environment.getPlatform() + '\n' + "OS Version: " + environment.getOsVersion() + '\n' + "```";
    }
}
