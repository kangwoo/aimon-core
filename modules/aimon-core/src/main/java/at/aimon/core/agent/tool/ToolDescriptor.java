package at.aimon.core.agent.tool;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.llm.ToolDefinition;

/**
 * What a {@link Tool} says about itself in one {@link ToolOfferContext}: its definition as offered and every axis its
 * consumers read.
 *
 * <p>
 * The consumers of those axes — the offer filters of the three loops, the side-effect ceiling, the approval gate, the
 * routine executor's retry rule — read one descriptor from {@link Tool#describe(ToolOfferContext)} instead of the
 * tool's constant getters, so a tool whose grade depends on its context ({@code ExecuteCode}, whose grade is folded
 * from its engine and the tools it lets code call) gives all of them the same answer
 * ({@code docs/design/tool/programmatic-tool-calling.md} decision 9). Every other tool's descriptor is its getters,
 * read
 * once.
 *
 * <p>
 * Beyond the existing axes it carries three things that only exist here:
 *
 * <ul>
 * <li>{@link #isApprovalDelegated()} — the approval gate never asks about this tool itself; it judges what the tool
 * dispatches instead, one nested call at a time. Default {@code false}.
 * <li>{@link #getApprovalKey()} — the name a remembered approval answer is stored under. Default the tool's name; a
 * tool
 * that is the same capability as another (the shell engine's {@code ExecuteCode} and {@code Bash}) returns the other's
 * name, so an answer about one is an answer about both.
 * <li>{@link #isCodeCallable()} and {@link #getDataReach()} — the declarations programmatic tool calling reads (§7.1).
 * </ul>
 *
 * <p>
 * Immutable and thread-safe.
 *
 * @see Tool#describe(ToolOfferContext)
 * @see ToolOfferContext
 */
public final class ToolDescriptor {

    private final ToolDefinition definition;
    private final SideEffectLevel sideEffectLevel;
    private final DestructiveBehavior destructiveBehavior;
    private final IdempotencyBehavior idempotencyBehavior;
    private final ConcurrencyBehavior concurrencyBehavior;
    private final InterruptBehavior interruptBehavior;
    private final boolean codeCallable;
    private final DataReach dataReach;
    private final boolean approvalDelegated;
    private final String approvalKey;
    private final String approvalVia;
    private final boolean offerable;
    private final Set<String> codeHostedTools;

    private ToolDescriptor(Builder builder) {
        this.definition = Objects.requireNonNull(builder.definition, "definition cannot be null");
        this.sideEffectLevel = Objects.requireNonNull(builder.sideEffectLevel, "sideEffectLevel cannot be null");
        this.destructiveBehavior = Objects.requireNonNull(builder.destructiveBehavior,
                "destructiveBehavior cannot be null");
        this.idempotencyBehavior = Objects.requireNonNull(builder.idempotencyBehavior,
                "idempotencyBehavior cannot be null");
        this.concurrencyBehavior = Objects.requireNonNull(builder.concurrencyBehavior,
                "concurrencyBehavior cannot be null");
        this.interruptBehavior = Objects.requireNonNull(builder.interruptBehavior, "interruptBehavior cannot be null");
        this.codeCallable = builder.codeCallable;
        this.dataReach = Objects.requireNonNull(builder.dataReach, "dataReach cannot be null");
        this.approvalDelegated = builder.approvalDelegated;
        this.approvalKey = builder.approvalKey != null ? builder.approvalKey : definition.getName();
        this.approvalVia = builder.approvalVia;
        this.offerable = builder.offerable;
        this.codeHostedTools = Set.copyOf(builder.codeHostedTools);
    }

    /**
     * Returns the descriptor made of a tool's own getters and {@link Tool#getDefinition()} — what
     * {@link Tool#describe(ToolOfferContext)} returns unless a tool overrides it.
     *
     * @param tool
     *            the tool to describe (must not be null)
     * @return the descriptor (never null)
     * @throws NullPointerException
     *             if {@code tool} is null
     */
    public static ToolDescriptor of(Tool tool) {
        return from(tool).build();
    }

    /**
     * Returns a builder pre-filled with a tool's own getters, for a tool that overrides
     * {@link Tool#describe(ToolOfferContext)} and changes only some of them.
     *
     * @param tool
     *            the tool to start from (must not be null)
     * @return a pre-filled builder (never null)
     * @throws NullPointerException
     *             if {@code tool} is null
     */
    public static Builder from(Tool tool) {
        Objects.requireNonNull(tool, "tool cannot be null");
        return new Builder().definition(tool.getDefinition()).sideEffectLevel(tool.getSideEffectLevel())
                .destructiveBehavior(tool.getDestructiveBehavior()).idempotencyBehavior(tool.getIdempotencyBehavior())
                .concurrencyBehavior(tool.getConcurrencyBehavior()).interruptBehavior(tool.getInterruptBehavior())
                .codeCallable(tool.isCodeCallable()).dataReach(tool.dataReach());
    }

    /**
     * Returns an empty builder. Every axis must be set except {@code codeCallable}, {@code approvalDelegated}
     * (both {@code false}) and {@code approvalKey} (the definition's name).
     *
     * @return a new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the definition offered to the model in this context.
     *
     * @return the definition (never null)
     */
    public ToolDefinition getDefinition() {
        return definition;
    }

    /**
     * Returns the tool's name — the definition's.
     *
     * @return the name (never null)
     */
    public String getName() {
        return definition.getName();
    }

    /**
     * Returns the side-effect level.
     *
     * @return the level (never null)
     * @see Tool#getSideEffectLevel()
     */
    public SideEffectLevel getSideEffectLevel() {
        return sideEffectLevel;
    }

    /**
     * Returns the destructiveness. Read only when the level is {@link SideEffectLevel#MUTATING}.
     *
     * @return the destructiveness (never null)
     * @see Tool#getDestructiveBehavior()
     */
    public DestructiveBehavior getDestructiveBehavior() {
        return destructiveBehavior;
    }

    /**
     * Returns the destructiveness as its consumers read it: {@link DestructiveBehavior#NON_DESTRUCTIVE} below
     * {@link SideEffectLevel#MUTATING}, where a tool that changes nothing can destroy nothing, otherwise
     * {@link #getDestructiveBehavior()}.
     *
     * @return the effective destructiveness (never null)
     */
    public DestructiveBehavior getEffectiveDestructiveBehavior() {
        return sideEffectLevel == SideEffectLevel.MUTATING ? destructiveBehavior : DestructiveBehavior.NON_DESTRUCTIVE;
    }

    /**
     * Returns the idempotency. Read only when the level is {@link SideEffectLevel#MUTATING}.
     *
     * @return the idempotency (never null)
     * @see Tool#getIdempotencyBehavior()
     */
    public IdempotencyBehavior getIdempotencyBehavior() {
        return idempotencyBehavior;
    }

    /**
     * Returns the concurrency behaviour.
     *
     * @return the concurrency behaviour (never null)
     * @see Tool#getConcurrencyBehavior()
     */
    public ConcurrencyBehavior getConcurrencyBehavior() {
        return concurrencyBehavior;
    }

    /**
     * Returns the interrupt behaviour.
     *
     * @return the interrupt behaviour (never null)
     * @see Tool#getInterruptBehavior()
     */
    public InterruptBehavior getInterruptBehavior() {
        return interruptBehavior;
    }

    /**
     * Returns whether model-written code may call this tool at all.
     *
     * @return {@code false} when calling the tool from code breaks its contract
     * @see Tool#isCodeCallable()
     */
    public boolean isCodeCallable() {
        return codeCallable;
    }

    /**
     * Returns how far the data and network the tool touches reach.
     *
     * @return the reach (never null)
     * @see Tool#dataReach()
     */
    public DataReach getDataReach() {
        return dataReach;
    }

    /**
     * Returns whether the approval gate delegates its question to the calls this tool dispatches instead of asking
     * about the tool itself.
     *
     * @return {@code true} when the gate never asks about this tool
     */
    public boolean isApprovalDelegated() {
        return approvalDelegated;
    }

    /**
     * Returns the name a remembered approval answer for this tool is stored and looked up under.
     *
     * @return the approval key (never null); the tool's name unless set otherwise
     */
    public String getApprovalKey() {
        return approvalKey;
    }

    /**
     * Returns how the path asking for approval is named in the prompt when the approval key is another tool's — for
     * the shell engine's {@code ExecuteCode}, {@code "ExecuteCode (Python)"}, so the user reads
     * {@code 'Bash' — via ExecuteCode (Python)} (decision 11).
     *
     * @return the label, empty when the prompt should name only the call
     */
    public Optional<String> getApprovalVia() {
        return Optional.ofNullable(approvalVia);
    }

    /**
     * Returns whether this tool may be offered in the context it was described in. A tool whose usefulness depends on
     * its context ({@code ExecuteCode} without an engine or without a tool to call) says {@code false}, and the offer
     * leaves it out. Default {@code true}.
     *
     * @return {@code false} when the offer must leave the tool out
     */
    public boolean isOfferable() {
        return offerable;
    }

    /**
     * Returns the tools model-written code can call through this tool in the context it was described in — for
     * {@code ExecuteCode}, its callable set. A tool the agent opens only to code is withheld from the direct offer only
     * when some offered tool hosts it ({@link ToolOfferContext#isCodeOnly(ToolDescriptor)}). Empty for every other
     * tool.
     *
     * @return the hosted tool names (never null, possibly empty)
     */
    public Set<String> getCodeHostedTools() {
        return codeHostedTools;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ToolDescriptor other)) {
            return false;
        }
        return codeCallable == other.codeCallable && approvalDelegated == other.approvalDelegated
                && definition.equals(other.definition) && sideEffectLevel == other.sideEffectLevel
                && destructiveBehavior == other.destructiveBehavior && idempotencyBehavior == other.idempotencyBehavior
                && concurrencyBehavior == other.concurrencyBehavior && interruptBehavior == other.interruptBehavior
                && dataReach == other.dataReach && approvalKey.equals(other.approvalKey)
                && Objects.equals(approvalVia, other.approvalVia) && offerable == other.offerable
                && codeHostedTools.equals(other.codeHostedTools);
    }

    @Override
    public int hashCode() {
        return Objects.hash(definition, sideEffectLevel, destructiveBehavior, idempotencyBehavior, concurrencyBehavior,
                interruptBehavior, codeCallable, dataReach, approvalDelegated, approvalKey, approvalVia, offerable,
                codeHostedTools);
    }

    @Override
    public String toString() {
        return "ToolDescriptor{" + getName() + ": " + sideEffectLevel + "/" + destructiveBehavior + "/"
                + idempotencyBehavior + ", " + concurrencyBehavior + "/" + interruptBehavior + ", codeCallable="
                + codeCallable + ", dataReach=" + dataReach + ", approvalDelegated=" + approvalDelegated
                + ", approvalKey=" + approvalKey + (approvalVia == null ? "" : ", approvalVia=" + approvalVia)
                + (offerable ? "" : ", offerable=false") + '}';
    }

    /** Builder for {@link ToolDescriptor}. */
    public static final class Builder {
        private ToolDefinition definition;
        private SideEffectLevel sideEffectLevel;
        private DestructiveBehavior destructiveBehavior;
        private IdempotencyBehavior idempotencyBehavior;
        private ConcurrencyBehavior concurrencyBehavior;
        private InterruptBehavior interruptBehavior;
        private boolean codeCallable;
        private DataReach dataReach;
        private boolean approvalDelegated;
        private String approvalKey;
        private String approvalVia;
        private boolean offerable = true;
        private Set<String> codeHostedTools = Set.of();

        private Builder() {
        }

        /**
         * @param codeHostedTools
         *            the tools code can call through this tool (must not be null); defaults to none
         * @return this builder
         */
        public Builder codeHostedTools(Set<String> codeHostedTools) {
            this.codeHostedTools = Objects.requireNonNull(codeHostedTools, "codeHostedTools cannot be null");
            return this;
        }

        /**
         * @param approvalVia
         *            how the prompt names the path asking under another tool's approval key; {@code null} for none
         * @return this builder
         */
        public Builder approvalVia(String approvalVia) {
            this.approvalVia = approvalVia;
            return this;
        }

        /**
         * @param offerable
         *            whether the tool may be offered in this context; defaults to {@code true}
         * @return this builder
         */
        public Builder offerable(boolean offerable) {
            this.offerable = offerable;
            return this;
        }

        /**
         * @param definition
         *            the definition offered in this context (must not be null)
         * @return this builder
         */
        public Builder definition(ToolDefinition definition) {
            this.definition = Objects.requireNonNull(definition, "definition cannot be null");
            return this;
        }

        /**
         * @param sideEffectLevel
         *            the side-effect level (must not be null)
         * @return this builder
         */
        public Builder sideEffectLevel(SideEffectLevel sideEffectLevel) {
            this.sideEffectLevel = Objects.requireNonNull(sideEffectLevel, "sideEffectLevel cannot be null");
            return this;
        }

        /**
         * @param destructiveBehavior
         *            the destructiveness (must not be null)
         * @return this builder
         */
        public Builder destructiveBehavior(DestructiveBehavior destructiveBehavior) {
            this.destructiveBehavior = Objects.requireNonNull(destructiveBehavior,
                    "destructiveBehavior cannot be null");
            return this;
        }

        /**
         * @param idempotencyBehavior
         *            the idempotency (must not be null)
         * @return this builder
         */
        public Builder idempotencyBehavior(IdempotencyBehavior idempotencyBehavior) {
            this.idempotencyBehavior = Objects.requireNonNull(idempotencyBehavior,
                    "idempotencyBehavior cannot be null");
            return this;
        }

        /**
         * @param concurrencyBehavior
         *            the concurrency behaviour (must not be null)
         * @return this builder
         */
        public Builder concurrencyBehavior(ConcurrencyBehavior concurrencyBehavior) {
            this.concurrencyBehavior = Objects.requireNonNull(concurrencyBehavior,
                    "concurrencyBehavior cannot be null");
            return this;
        }

        /**
         * @param interruptBehavior
         *            the interrupt behaviour (must not be null)
         * @return this builder
         */
        public Builder interruptBehavior(InterruptBehavior interruptBehavior) {
            this.interruptBehavior = Objects.requireNonNull(interruptBehavior, "interruptBehavior cannot be null");
            return this;
        }

        /**
         * @param codeCallable
         *            whether model-written code may call the tool
         * @return this builder
         */
        public Builder codeCallable(boolean codeCallable) {
            this.codeCallable = codeCallable;
            return this;
        }

        /**
         * @param dataReach
         *            how far the tool reaches (must not be null)
         * @return this builder
         */
        public Builder dataReach(DataReach dataReach) {
            this.dataReach = Objects.requireNonNull(dataReach, "dataReach cannot be null");
            return this;
        }

        /**
         * @param approvalDelegated
         *            whether the approval gate judges the tool's nested calls instead of the tool
         * @return this builder
         */
        public Builder approvalDelegated(boolean approvalDelegated) {
            this.approvalDelegated = approvalDelegated;
            return this;
        }

        /**
         * @param approvalKey
         *            the name remembered approval answers are stored under (must not be null)
         * @return this builder
         */
        public Builder approvalKey(String approvalKey) {
            this.approvalKey = Objects.requireNonNull(approvalKey, "approvalKey cannot be null");
            return this;
        }

        /**
         * @return the descriptor
         * @throws NullPointerException
         *             if a required axis was not set
         */
        public ToolDescriptor build() {
            return new ToolDescriptor(this);
        }
    }
}
