package at.aimon.core.agent.impl.orca;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import at.aimon.core.agent.AgentExecutionRequest;
import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.budget.BudgetTracker;
import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.input.UserInput;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.LlmCallMetadata;

/**
 * Encapsulates the request data for agent execution.
 *
 * <p>
 * This class contains the data that defines what the agent should execute, including user input, optional user
 * information, and conversation context.
 *
 * <p>
 * Supports multimodal input including text, images, audio, and combinations.
 *
 * <p>
 * For multi-turn conversations, you can use {@code previousSnapshot} to preserve the system prompt that was used in
 * the previous turn. This ensures temporal consistency even if the agent's configuration changes between turns.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Simple text request
 *     AgentExecutionRequest request = AgentExecutionRequest.of("What files are here?");
 *
 *     // Multimodal request
 *     AgentExecutionRequest request = AgentExecutionRequest.builder()
 *             .userInput(
 *                     MultimodalInput.of(TextInput.of("What's in this image?"), ImageInput.of(imageData, "image/png")))
 *             .principal(principal).build();
 *
 *     // Multi-turn conversation
 *     AgentExecutionRequest followUp = AgentExecutionRequest.builder()
 *             .userInput(TextInput.of("Can you show me the first file?"))
 *             .previousSnapshot(result.getSnapshot()).build();
 * }
 * </pre>
 */
public final class OrcaAgentExecutionRequest implements AgentExecutionRequest {
    /**
     * Creates a new builder.
     *
     * @return A new AgentExecutionRequest.Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final UserInput userInput;
    private final SubmitOptions submitOptions;
    private final Principal principal;
    private final SessionId sessionId;
    private final Map<String, Object> systemPromptVariables;
    private final Map<String, Object> executionAttributes;
    private final LlmCallMetadata llmCallMetadata;
    private final ExecutionBudget budget;
    private final boolean userContextInjection;
    private final Consumer<InterruptCoordinator> interruptObserver;
    private final Consumer<BudgetTracker> budgetObserver;

    /**
     * Creates a new AgentExecutionRequest.
     *
     * @throws NullPointerException
     *             if userInput is null
     */
    private OrcaAgentExecutionRequest(Builder builder) {
        userInput = Objects.requireNonNull(builder.userInput, "User input cannot be null");
        submitOptions = builder.submitOptions;
        principal = builder.principal;
        sessionId = builder.sessionId;
        systemPromptVariables = builder.systemPromptVariables != null
                ? Map.copyOf(builder.systemPromptVariables)
                : Map.of();
        executionAttributes = builder.executionAttributes != null ? Map.copyOf(builder.executionAttributes) : Map.of();
        llmCallMetadata = builder.llmCallMetadata != null ? builder.llmCallMetadata : LlmCallMetadata.empty();
        budget = builder.budget;
        userContextInjection = builder.userContextInjection;
        interruptObserver = builder.interruptObserver != null ? builder.interruptObserver : c -> {
        };
        budgetObserver = builder.budgetObserver != null ? builder.budgetObserver : c -> {
        };
    }

    /**
     * Returns the per-turn options this request was built from, for a rewind point to remember.
     *
     * <p>
     * The individual values are already spread across this request — principal, system-prompt variables, execution
     * attributes, LLM call metadata, user-context injection — so this looks redundant, and for execution it is: no
     * code path reads it to run the turn. It is carried because those fields cannot be folded back into the options
     * they came from. Defaults have been applied by then, so an option that was never named is indistinguishable from
     * one named with the default value, and a retry rebuilt from the flattened fields would pin a component and trace
     * id that were meant to be re-derived. Keeping the original is the only way a second attempt can be submitted the
     * way the first one was.
     *
     * @return the submit options, {@link SubmitOptions#empty()} when the caller supplied none (never null)
     */
    public SubmitOptions getSubmitOptions() {
        return submitOptions;
    }

    /**
     * Gets the user input.
     *
     * @return The user input (never null)
     */
    @Override
    public UserInput getUserInput() {
        return userInput;
    }

    /**
     * Gets the principal (caller identity).
     *
     * @return the principal, or {@link java.util.Optional#empty()} if not set
     */
    @Override
    public Optional<Principal> getPrincipal() {
        return Optional.ofNullable(principal);
    }

    /**
     * Gets the session id for multi-turn conversations.
     *
     * @return The session id (can be null)
     */
    public SessionId getSessionId() {
        return sessionId;
    }

    /**
     * Gets the system prompt variables.
     *
     * @return The system prompt variables (never null, may be empty)
     */
    public Map<String, Object> getSystemPromptVariables() {
        return systemPromptVariables;
    }

    /**
     * Gets the execution attributes.
     *
     * <p>
     * Execution attributes are arbitrary key-value data passed by the caller. They are propagated to ToolContext and
     * HookContext during execution, including subagent propagation.
     *
     * @return The execution attributes (never null, may be empty)
     */
    public Map<String, Object> getExecutionAttributes() {
        return executionAttributes;
    }

    /**
     * Gets the caller-supplied LLM call metadata for usage attribution.
     *
     * <p>
     * The agent executor merges this value with auto-derived defaults (component=agent name, feature="react-loop",
     * traceId=sessionId) before passing it to the LLM client. Caller-supplied fields always win.
     *
     * @return the metadata (never null, may be {@link LlmCallMetadata#empty()})
     */
    public LlmCallMetadata getLlmCallMetadata() {
        return llmCallMetadata;
    }

    /**
     * Gets the execution budget applied to this request.
     *
     * <p>
     * Returns {@link Optional#empty()} when the caller did not configure a budget. The Orca executor treats an empty
     * value as unbounded (equivalent to {@link ExecutionBudget#unlimited()}), preserving legacy behavior for requests
     * built without {@link Builder#budget(ExecutionBudget)}.
     *
     * @return the budget, or {@link Optional#empty()} if none is configured
     */
    @Override
    public Optional<ExecutionBudget> getBudget() {
        return Optional.ofNullable(budget);
    }

    /**
     * Returns whether the synthetic {@code messages[0]} user-context injection is enabled for this request.
     *
     * <p>
     * When {@code true} (the default), the executor injects a single synthetic user message at the start of a fresh
     * conversation that wraps session-level context (working directory, current date, CLAUDE.md-style extensions) in
     * {@code <system-reminder>} blocks. Resumed conversations (memory already contains a user message) skip injection
     * regardless of this flag.
     *
     * <p>
     * Set to {@code false} via {@link Builder#userContextInjection(boolean)} to fully opt out (legacy behavior).
     *
     * @return {@code true} when injection is enabled; {@code false} when the caller opted out
     */
    public boolean isUserContextInjectionEnabled() {
        return userContextInjection;
    }

    /**
     * Returns the observer invoked with the turn's fresh {@link InterruptCoordinator} at loop entry.
     *
     * <p>
     * Interrupt seam: the Orca executor creates a per-turn coordinator inside
     * {@code executeReActLoop} and publishes it to this observer exactly once before the first LLM call. Session
     * implementations use this to capture the coordinator so external actors (REPL SIGINT, priority-queue preemption,
     * parent-agent cascade) can subsequently trip the turn via
     * {@link InterruptCoordinator#requestInterrupt(at.aimon.core.agent.interrupt.InterruptReason)}.
     *
     * <p>
     * Defaults to a no-op when the caller did not configure an observer. Never returns null.
     *
     * @return the observer (never null)
     */
    public Consumer<InterruptCoordinator> getInterruptObserver() {
        return interruptObserver;
    }

    /**
     * Returns the observer invoked with the turn's {@link BudgetTracker} at loop entry.
     *
     * <p>
     * Live-metrics seam (mirrors the {@link #getInterruptObserver() interrupt observer}): the Orca executor
     * publishes the per-turn {@link BudgetTracker} to this observer exactly once, right after the interrupt
     * coordinator, before the first LLM call. Session implementations use this to capture the tracker so callers can
     * read live iteration / token / elapsed counters via {@link at.aimon.core.agent.session.LiveSession#status()}.
     *
     * <p>
     * Defaults to a no-op when the caller did not configure an observer. Never returns null. The published tracker is
     * not thread-safe; observers must only retain the reference and read it best-effort (see
     * {@link at.aimon.core.agent.session.LiveSessionStatus.TurnProgress}).
     *
     * @return the observer (never null)
     */
    public Consumer<BudgetTracker> getBudgetObserver() {
        return budgetObserver;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final OrcaAgentExecutionRequest that = (OrcaAgentExecutionRequest) o;
        return userInput.equals(that.userInput) && Objects.equals(principal, that.principal)
                && Objects.equals(sessionId, that.sessionId)
                && Objects.equals(systemPromptVariables, that.systemPromptVariables)
                && Objects.equals(executionAttributes, that.executionAttributes)
                && Objects.equals(llmCallMetadata, that.llmCallMetadata) && Objects.equals(budget, that.budget)
                && userContextInjection == that.userContextInjection
                // Part of the state even though nothing executes from it: two requests whose flattened fields agree
                // can still have been built from options that differ in what they left unset, and those produce
                // different rewind points, hence different retries.
                && Objects.equals(submitOptions, that.submitOptions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userInput, submitOptions, principal, sessionId, systemPromptVariables, executionAttributes,
                llmCallMetadata, budget, userContextInjection);
    }

    @Override
    public String toString() {
        return "AgentExecutionRequest{" + "userInput=" + userInput + ", principal=" + principal + ", sessionId="
                + (sessionId != null ? sessionId : "none") + ", executionAttributes=" + executionAttributes
                + ", budget=" + budget + '}';
    }

    /** Builder for AgentExecutionRequest. */
    public static final class Builder {
        private UserInput userInput;
        private SubmitOptions submitOptions = SubmitOptions.empty();
        private Principal principal;
        private SessionId sessionId;
        private Map<String, Object> systemPromptVariables;
        private Map<String, Object> executionAttributes;
        private LlmCallMetadata llmCallMetadata;
        private ExecutionBudget budget;
        private boolean userContextInjection = true;
        private Consumer<InterruptCoordinator> interruptObserver;
        private Consumer<BudgetTracker> budgetObserver;

        private Builder() {
        }

        /**
         * Sets the user input from text.
         *
         * <p>
         * Convenience method that wraps the text in a TextInput.
         *
         * @param text
         *            The user's text (must not be null)
         * @return This builder
         */
        public Builder userInput(String text) {
            userInput = TextInput.of(text);
            return this;
        }

        /**
         * Sets the user input.
         *
         * @param userInput
         *            The user input (must not be null)
         * @return This builder
         */
        public Builder userInput(UserInput userInput) {
            this.userInput = userInput;
            return this;
        }

        /**
         * Records the per-turn options this request is being built from, so a rewind point can remember them.
         *
         * <p>
         * Set by the session facade alongside the fields it flattens the options into; nothing reads it to execute
         * the turn. See {@link OrcaAgentExecutionRequest#getSubmitOptions()} for why the flattened fields are not
         * enough.
         *
         * @param submitOptions
         *            the options (must not be null)
         * @return this builder
         */
        public Builder submitOptions(SubmitOptions submitOptions) {
            this.submitOptions = Objects.requireNonNull(submitOptions, "submitOptions cannot be null");
            return this;
        }

        /**
         * Sets the principal (caller identity).
         *
         * @param principal
         *            the principal (can be null)
         * @return this builder
         */
        public Builder principal(Principal principal) {
            this.principal = principal;
            return this;
        }

        /**
         * Sets the previous session snapshot for multi-turn conversations.
         *
         * <p>
         * This is the way to continue a conversation. It preserves both the conversation history and the system prompt
         * that was used in the previous turn, ensuring temporal consistency.
         *
         * @param sessionId
         *            The session id
         * @return This builder
         */
        public Builder sessionId(SessionId sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /**
         * Sets the system prompt variables.
         *
         * @param systemPromptVariables
         *            The system prompt variables (can be null)
         * @return This builder
         */
        public Builder systemPromptVariables(Map<String, Object> systemPromptVariables) {
            this.systemPromptVariables = systemPromptVariables;
            return this;
        }

        /**
         * Sets the execution attributes.
         *
         * <p>
         * Execution attributes are arbitrary key-value data that will be propagated to ToolContext and HookContext
         * during execution, including subagent propagation.
         *
         * <p>
         * <b>Note:</b> The map is stored using {@code Map.copyOf()}, which creates a shallow copy. Map values should be
         * effectively immutable types (e.g., {@code String}, {@code Integer}).
         *
         * @param executionAttributes
         *            The execution attributes (can be null)
         * @return This builder
         */
        public Builder executionAttributes(Map<String, Object> executionAttributes) {
            this.executionAttributes = executionAttributes;
            return this;
        }

        /**
         * Sets the LLM call metadata for usage attribution.
         *
         * <p>
         * The agent executor merges this value with auto-derived defaults (component=agent name, feature="react-loop",
         * traceId=sessionId) before passing it to the LLM client. Caller-supplied fields always win on collision.
         *
         * @param llmCallMetadata
         *            the metadata (can be null, defaults to {@link LlmCallMetadata#empty()})
         * @return this builder
         */
        public Builder llmCallMetadata(LlmCallMetadata llmCallMetadata) {
            this.llmCallMetadata = llmCallMetadata;
            return this;
        }

        /**
         * Sets the execution budget that applies to this request.
         *
         * <p>
         * When null or unset the executor treats the request as unbounded (equivalent to
         * {@link ExecutionBudget#unlimited()}), preserving legacy behavior. When set, the Orca executor consults a
         * budget tracker at each iteration boundary and finalises execution with a matching
         * {@link at.aimon.core.agent.budget.CompletionReason} once any dimension is exhausted.
         *
         * @param budget
         *            the budget (can be null)
         * @return this builder
         */
        public Builder budget(ExecutionBudget budget) {
            this.budget = budget;
            return this;
        }

        /**
         * Enables or disables the synthetic {@code messages[0]} user-context injection.
         *
         * <p>
         * Defaults to {@code true} so fresh conversations receive a synthetic user-role message that wraps
         * session-level
         * context (working directory, current date, CLAUDE.md-style extensions) in {@code <system-reminder>} blocks,
         * mirroring the reference implementation's behaviour. Resumed conversations (memory already contains user
         * messages) skip
         * injection regardless of this flag.
         *
         * <p>
         * Set to {@code false} to fully opt out and preserve legacy behaviour (no synthetic context message).
         *
         * @param userContextInjection
         *            {@code true} to enable injection (default), {@code false} to opt out
         * @return this builder
         */
        public Builder userContextInjection(boolean userContextInjection) {
            this.userContextInjection = userContextInjection;
            return this;
        }

        /**
         * Registers an observer that receives the turn's fresh {@link InterruptCoordinator} at loop entry.
         *
         * <p>
         * Interrupt seam: the Orca executor creates a per-turn coordinator inside
         * {@code executeReActLoop} and publishes it to this observer exactly once before the first LLM call. Session
         * implementations (notably {@code DefaultLiveSession}) use this to capture the coordinator so external actors
         * (REPL SIGINT, priority-queue preemption, parent-agent cascade) can trip the turn via
         * {@link InterruptCoordinator#requestInterrupt(at.aimon.core.agent.interrupt.InterruptReason)}.
         *
         * <p>
         * The observer is invoked synchronously on the executor thread. Implementations must be non-blocking and must
         * not throw; any exception raised by the observer propagates out of the executor and aborts the turn.
         *
         * <p>
         * When left unset (the default) the executor calls a no-op observer — unchanged behavior for all legacy
         * callers.
         *
         * @param interruptObserver
         *            the observer (can be null to keep the default no-op)
         * @return this builder
         */
        public Builder interruptObserver(Consumer<InterruptCoordinator> interruptObserver) {
            this.interruptObserver = interruptObserver;
            return this;
        }

        /**
         * Registers an observer that receives the turn's {@link BudgetTracker} at loop entry.
         *
         * <p>
         * Live-metrics seam (mirrors {@link #interruptObserver(Consumer)}): the Orca executor publishes the per-turn
         * {@link BudgetTracker} to this observer exactly once, right after the interrupt coordinator, before the first
         * LLM call. Session implementations (notably {@code DefaultLiveSession}) use this to capture the tracker so
         * callers can read live iteration / token / elapsed counters via
         * {@link at.aimon.core.agent.session.LiveSession#status()}.
         *
         * <p>
         * The observer is invoked synchronously on the executor thread. Implementations must be non-blocking and must
         * not throw. The published tracker is not thread-safe; observers must only retain the reference and read it
         * best-effort.
         *
         * <p>
         * When left unset (the default) the executor calls a no-op observer — unchanged behavior for all legacy
         * callers.
         *
         * @param budgetObserver
         *            the observer (can be null to keep the default no-op)
         * @return this builder
         */
        public Builder budgetObserver(Consumer<BudgetTracker> budgetObserver) {
            this.budgetObserver = budgetObserver;
            return this;
        }

        /**
         * Builds the AgentExecutionRequest.
         *
         * @return A new AgentExecutionRequest
         */
        public OrcaAgentExecutionRequest build() {
            return new OrcaAgentExecutionRequest(this);
        }
    }
}
