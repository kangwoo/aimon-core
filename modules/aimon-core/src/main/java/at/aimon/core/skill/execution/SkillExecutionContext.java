package at.aimon.core.skill.execution;

import java.util.List;
import java.util.Objects;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.skill.Skill;

/**
 * Encapsulates the runtime configuration for skill execution.
 *
 * <p>
 * Mirrors {@code CommandExecutionContext} but binds a {@link Skill} instead of a {@code Command}. Introduced in SK-08-C
 * to keep the user-invocation path independent of the (deprecating) {@code at.aimon.core.command.*} package.
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class SkillExecutionContext {

    /**
     * Creates a new builder.
     *
     * @return A new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    private final Skill skill;
    private final LlmModel defaultModel;
    private final ToolRegistry toolRegistry;
    private final ExecutionId executionId;
    private final TranscriptBuffer transcriptBuffer;
    private final ToolContext toolContext;

    private SkillExecutionContext(Builder builder) {
        this.skill = Objects.requireNonNull(builder.skill, "Skill cannot be null");
        this.defaultModel = Objects.requireNonNull(builder.defaultModel, "Default model cannot be null");
        this.toolRegistry = Objects.requireNonNull(builder.toolRegistry, "Tool registry cannot be null");
        this.executionId = Objects.requireNonNull(builder.executionId, "Execution id cannot be null");
        this.transcriptBuffer = builder.transcriptBuffer;
        this.toolContext = builder.toolContext == null ? ToolContext.empty() : builder.toolContext;
    }

    /**
     * Gets the skill to execute.
     *
     * @return The skill (never null)
     */
    public Skill getSkill() {
        return skill;
    }

    /**
     * Gets the default LLM model configuration.
     *
     * @return The model config (never null)
     */
    public LlmModel getDefaultModel() {
        return defaultModel;
    }

    /**
     * Gets the tool registry used for resolving tool implementations.
     *
     * @return The tool registry (never null)
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /**
     * Gets the correlation id of this skill run.
     *
     * <p>
     * A skill invocation has no session of its own &mdash; it runs inside whoever invoked it &mdash; so its identity is
     * an {@link ExecutionId} and not a {@link at.aimon.core.agent.session.SessionId}: node-local, never persisted,
     * granting no lease. The session it acts <em>for</em> travels separately, on the forwarded
     * {@link #getToolContext() tool context}.
     *
     * <p>
     * Mandatory on purpose. The executor used to mint {@code new SessionId(UUID.randomUUID().toString())} for its
     * transcript, an id that neither a log reader nor the type system could tell apart from a real user session;
     * requiring the caller to supply a run id is what removes the temptation to invent one.
     *
     * @return the execution id (never null)
     */
    public ExecutionId getExecutionId() {
        return executionId;
    }

    /**
     * Gets the available tools (delegates to the bound tool registry).
     *
     * @return Immutable list of available tools (never null)
     */
    public List<Tool> getAvailableTools() {
        return toolRegistry.findAll();
    }

    /**
     * Gets the transcript buffer if one was supplied.
     *
     * @return The transcript buffer or {@code null} if not provided
     */
    public TranscriptBuffer getTranscriptBuffer() {
        return transcriptBuffer;
    }

    /**
     * Gets the {@link ToolContext} associated with this skill execution.
     *
     * <p>
     * Surfaces the parent agent runtime ID, execution attributes, and LLM call metadata that fork-mode skills need
     * to propagate to spawned subagents. Defaults to {@link ToolContext#empty()} when callers do not supply one — in
     * that case fork-mode skills will fail with a clear "agent runtime ID not available" error rather than running
     * inline.
     *
     * @return The tool context (never null; defaults to {@link ToolContext#empty()})
     */
    public ToolContext getToolContext() {
        return toolContext;
    }

    /** Builder for {@link SkillExecutionContext}. */
    public static final class Builder {
        private Skill skill;
        private LlmModel defaultModel;
        private ToolRegistry toolRegistry;
        private ExecutionId executionId;
        private TranscriptBuffer transcriptBuffer;
        private ToolContext toolContext;

        private Builder() {
        }

        /**
         * Sets the skill to execute (required).
         *
         * @param skill
         *            The skill (must not be null)
         * @return This builder
         */
        public Builder skill(Skill skill) {
            this.skill = skill;
            return this;
        }

        /**
         * Sets the default LLM model (required).
         *
         * @param defaultModel
         *            The default model (must not be null)
         * @return This builder
         */
        public Builder defaultModel(LlmModel defaultModel) {
            this.defaultModel = defaultModel;
            return this;
        }

        /**
         * Sets the tool registry (required).
         *
         * @param toolRegistry
         *            The tool registry (must not be null)
         * @return This builder
         */
        public Builder toolRegistry(ToolRegistry toolRegistry) {
            this.toolRegistry = toolRegistry;
            return this;
        }

        /**
         * Sets the correlation id of this run (required).
         *
         * <p>
         * Generate one per invocation rather than per skill &mdash; two runs of the same skill must not share per-run
         * state. {@code ExecutionId.generate("skill:" + name)} gives the log reader the name and the random tail the
         * uniqueness.
         *
         * @param executionId
         *            The execution id (must not be null)
         * @return This builder
         */
        public Builder executionId(ExecutionId executionId) {
            this.executionId = executionId;
            return this;
        }

        /**
         * Sets the transcript buffer (optional).
         *
         * @param transcriptBuffer
         *            The transcript buffer (may be null)
         * @return This builder
         */
        public Builder transcriptBuffer(TranscriptBuffer transcriptBuffer) {
            this.transcriptBuffer = transcriptBuffer;
            return this;
        }

        /**
         * Sets the {@link ToolContext} forwarded to the executor (optional).
         *
         * <p>
         * Required for fork-mode skills invoked through the user-slash path: the {@link ToolContext} carries the
         * parent agent runtime ID consumed by {@link at.aimon.core.skill.fork.SkillForkExecutor}. Defaults to
         * {@link ToolContext#empty()} when not supplied.
         *
         * @param toolContext
         *            The tool context (may be null; treated as {@link ToolContext#empty()})
         * @return This builder
         */
        public Builder toolContext(ToolContext toolContext) {
            this.toolContext = toolContext;
            return this;
        }

        /**
         * Builds the context.
         *
         * @return A new {@link SkillExecutionContext} instance (never null)
         */
        public SkillExecutionContext build() {
            return new SkillExecutionContext(this);
        }
    }
}
