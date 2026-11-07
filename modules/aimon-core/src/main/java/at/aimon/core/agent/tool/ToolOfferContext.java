package at.aimon.core.agent.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What one iteration offers the model, and the facts a tool may read to describe itself for that offer.
 *
 * <p>
 * Every loop that offers tools to a model — the main loop, a fork, a slash-command skill loop — builds one of these per
 * iteration and both filters its offer through it and binds it into the iteration's tool context under
 * {@link #CONTEXT_KEY} ({@code ToolContextKeys.TOOL_OFFER_CONTEXT}). The offer filter, the side-effect ceiling's
 * refusal and the approval gate then all read the same {@link Tool#describe(ToolOfferContext) descriptor}, so a tool
 * whose grade depends on its context cannot be offered under one grade and judged under another
 * ({@code docs/design/tool/programmatic-tool-calling.md} decision 9).
 *
 * <p>
 * It carries:
 *
 * <ul>
 * <li>the registry the offer is drawn from, the loop's admission filter (its allow-list) and its side-effect ceiling —
 * the offer's eligibility, which {@link #offer()} applies;
 * <li>typed {@link OfferKey attributes} for anything else, such as facts about the execution environment. This package
 * may not depend on the environment, so the keys for those are defined where the values are.
 * </ul>
 *
 * <p>
 * A caller that offers nothing — a scheduled routine, a hand-built context — uses {@link #none()}. In that context a
 * tool whose grade depends on the offer answers with its most conservative grade.
 *
 * <p>
 * Iteration-scoped and immutable in everything it says; the lazily written fields are a memo of which tools some
 * offered tool hosts for code ({@link #isCodeOnly(ToolDescriptor)}), computed from the immutable fields, and the
 * reasons the last {@link #offer()} recorded for tools it left out ({@link #getUnavailableReasons()}). The registry it
 * holds may itself grow (a deferred tool activated mid-turn), which is why it is rebuilt every iteration rather than
 * once per execution.
 *
 * @see Tool#describe(ToolOfferContext)
 * @see ToolDescriptor
 */
public final class ToolOfferContext {

    /**
     * The tool-context key the iteration's offer context is bound under. Write-once: a loop binds it once into each
     * iteration context it builds — never into the turn's or fork's base context, which every iteration context copies
     * — so a {@link ToolContextEnricher} cannot swap the offer out from under the ceiling and the approval gate.
     * {@code ToolContextKeys.TOOL_OFFER_CONTEXT} is this same key.
     */
    public static final ToolContextKey<ToolOfferContext> CONTEXT_KEY = ToolContextKey.writeOnce("toolOfferContext",
            ToolOfferContext.class);

    private static final Logger log = LoggerFactory.getLogger(ToolOfferContext.class);

    /** How many (agent, tool) pairs are remembered so the invalid-{@code programmatic-only} WARN is logged once. */
    static final int MAX_CODE_ONLY_WARNINGS = 256;

    /**
     * The (agent, tool) pairs already warned about for an invalid {@code programmatic-only} entry. Static on purpose,
     * as {@code DefaultShellActionExecutor}'s fallback set: this context is a per-iteration value, and the only
     * longer-lived owner it reaches is the {@link ToolRegistry} SPI, which would need a new method on every
     * implementation for a log line. No behaviour depends on the set, and it is bounded — past the cap the warning is
     * logged at DEBUG.
     */
    private static final Set<String> WARNED_CODE_ONLY = ConcurrentHashMap.newKeySet();

    private static final ToolOfferContext NONE = new ToolOfferContext(new Builder(), false);

    private final boolean offering;
    private final ToolRegistry registry;
    private final Predicate<Tool> admission;
    private final SideEffectLevel ceiling;
    private final Map<OfferKey<?>, Object> attributes;
    private final ProgrammaticToolSettings programmatic;
    private final String agentName;
    /** Memo of {@link #codeHosted()}; the offer context is otherwise immutable. */
    private volatile Set<String> codeHosted;
    /** What the last {@link #offer()} recorded: tool name to why it is not offerable. */
    private volatile Map<String, String> unavailableReasons = Map.of();

    private ToolOfferContext(Builder builder, boolean offering) {
        this.offering = offering;
        this.registry = builder.registry;
        this.admission = builder.admission;
        this.ceiling = builder.ceiling;
        this.attributes = new IdentityHashMap<>(builder.attributes);
        this.programmatic = builder.programmatic;
        this.agentName = builder.agentName;
    }

    /**
     * Returns the context of a caller that offers nothing. It has no registry, admits every tool, has the unrestricted
     * ceiling and no attributes.
     *
     * @return the shared no-offer context (never null)
     */
    public static ToolOfferContext none() {
        return NONE;
    }

    /**
     * Returns the offer context bound in {@code context}, or {@link #none()} when it carries none.
     *
     * @param context
     *            the tool context to read (must not be null)
     * @return the bound offer context (never null)
     * @throws NullPointerException
     *             if {@code context} is null
     */
    public static ToolOfferContext of(ToolContext context) {
        Objects.requireNonNull(context, "context cannot be null");
        return context.get(CONTEXT_KEY).orElse(NONE);
    }

    /**
     * Returns a builder for an offering context.
     *
     * @return a new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns whether this context belongs to an iteration that offers tools to a model.
     *
     * @return {@code false} only for {@link #none()}
     */
    public boolean isOffering() {
        return offering;
    }

    /**
     * Returns the registry the offer is drawn from.
     *
     * @return the registry, empty for {@link #none()} or a context built without one
     */
    public Optional<ToolRegistry> getRegistry() {
        return Optional.ofNullable(registry);
    }

    /**
     * Returns the side-effect ceiling of this offer.
     *
     * @return the ceiling (never null); {@link SideEffectLevel#MUTATING}, which permits everything, unless set
     */
    public SideEffectLevel getCeiling() {
        return ceiling;
    }

    /**
     * Returns whether the loop's allow-list admits {@code tool}. Says nothing about the ceiling, which needs the tool's
     * descriptor; {@link #offer()} applies both.
     *
     * @param tool
     *            the tool (must not be null)
     * @return whether the admission filter accepts it
     * @throws NullPointerException
     *             if {@code tool} is null
     */
    public boolean admits(Tool tool) {
        Objects.requireNonNull(tool, "tool cannot be null");
        return admission.test(tool);
    }

    /**
     * Returns whether {@code descriptor}'s level is within this offer's ceiling.
     *
     * @param descriptor
     *            a descriptor obtained in this context (must not be null)
     * @return whether the ceiling permits it
     * @throws NullPointerException
     *             if {@code descriptor} is null
     */
    public boolean permits(ToolDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor cannot be null");
        return ceiling.permits(descriptor.getSideEffectLevel());
    }

    /**
     * Returns what the agent this offer belongs to opens to model-written code.
     *
     * @return the settings (never null); {@link ProgrammaticToolSettings#none()} unless set
     */
    public ProgrammaticToolSettings getProgrammatic() {
        return programmatic;
    }

    /**
     * Returns the descriptors of the registry's tools this offer makes: admitted, described in this context, offerable,
     * within the ceiling, and not withheld as code-only ({@link #isCodeOnly(ToolDescriptor)}) — in the registry's
     * order. Each tool is described once.
     *
     * @return the offered descriptors (never null); empty without a registry
     * @throws NullPointerException
     *             naming the tool, when a tool's {@link Tool#describe(ToolOfferContext)} breaks its contract and
     *             returns null
     */
    public List<ToolDescriptor> offer() {
        if (registry == null) {
            return List.of();
        }
        final List<ToolDescriptor> eligible = new ArrayList<>();
        final Set<String> hosted = new HashSet<>();
        final Map<String, String> reasons = new LinkedHashMap<>();
        for (Tool tool : registry.findAll()) {
            if (!admission.test(tool)) {
                continue;
            }
            final ToolDescriptor descriptor = describe(tool);
            if (descriptor.isOfferable() && permits(descriptor)) {
                eligible.add(descriptor);
                hosted.addAll(descriptor.getCodeHostedTools());
            } else if (!descriptor.isOfferable()) {
                descriptor.getUnavailableReason().ifPresent(reason -> reasons.put(descriptor.getName(), reason));
            }
        }
        codeHosted = Set.copyOf(hosted);
        unavailableReasons = Collections.unmodifiableMap(reasons);
        final List<ToolDescriptor> offered = new ArrayList<>(eligible.size());
        for (ToolDescriptor descriptor : eligible) {
            if (!isCodeOnly(descriptor)) {
                offered.add(descriptor);
            }
        }
        return List.copyOf(offered);
    }

    /**
     * Returns the descriptors of the named tools that are eligible for this offer — in the registry, admitted,
     * described in this context, offerable and within the ceiling — before code-only tools are withheld from the direct
     * offer. This is step 1 of the callable set ({@code docs/design/tool/programmatic-tool-calling.md} §7.3). The
     * result follows the order of {@code names}; a name with no eligible tool is left out.
     *
     * @param names
     *            the tool names to describe (must not be null)
     * @return the eligible descriptors (never null); empty without a registry
     * @throws NullPointerException
     *             naming the tool, when a tool's {@link Tool#describe(ToolOfferContext)} breaks its contract and
     *             returns null
     */
    public List<ToolDescriptor> eligible(Collection<String> names) {
        Objects.requireNonNull(names, "names cannot be null");
        if (registry == null) {
            return List.of();
        }
        final List<ToolDescriptor> eligible = new ArrayList<>();
        for (String name : names) {
            final Optional<Tool> tool = registry.findByName(name);
            if (tool.isEmpty() || !admission.test(tool.get())) {
                continue;
            }
            final ToolDescriptor descriptor = describe(tool.get());
            if (descriptor.isOfferable() && permits(descriptor)) {
                eligible.add(descriptor);
            }
        }
        return List.copyOf(eligible);
    }

    /**
     * Returns whether this offer withholds {@code descriptor}'s tool from the direct offer because the agent opens it
     * only to code ({@code programmatic-only}) <em>and</em> code can actually reach it here: some tool of this same
     * offer — {@code ExecuteCode} — is offered and lists it among the tools it hosts
     * ({@link ToolDescriptor#getCodeHostedTools()}). Without that, the entry is ignored and the tool is offered and
     * callable directly, so an agent never loses a tool because no engine can run here.
     *
     * <p>
     * A tool named there that is not {@link SideEffectLevel#READ_ONLY} is never withheld: a code-only tool has no
     * direct call to store an approval through, so a mutating one may not be code-only (decision 12 ④) — a warning
     * says so once per agent and tool.
     *
     * @param descriptor
     *            a descriptor obtained in this context (must not be null)
     * @return {@code true} when the tool is offered only through code
     */
    public boolean isCodeOnly(ToolDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor cannot be null");
        return isNamedCodeOnly(descriptor) && codeHosted().contains(descriptor.getName());
    }

    /**
     * Returns whether {@code descriptor}'s tool is named in {@code programmatic-only} and may be code-only — it is
     * {@link SideEffectLevel#READ_ONLY} — without asking whether code can reach it in this offer. For the tool that
     * hosts code ({@code ExecuteCode}) while it describes itself, where asking would describe it again; everyone else
     * reads {@link #isCodeOnly(ToolDescriptor)}.
     *
     * @param descriptor
     *            a descriptor obtained in this context (must not be null)
     * @return {@code true} when the tool would be code-only wherever code can reach it
     */
    public boolean isNamedCodeOnly(ToolDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor cannot be null");
        if (!programmatic.getCodeOnly().contains(descriptor.getName())) {
            return false;
        }
        if (descriptor.getSideEffectLevel() != SideEffectLevel.READ_ONLY) {
            final String key = getAgentName().orElse("") + '\0' + descriptor.getName();
            if (WARNED_CODE_ONLY.size() < MAX_CODE_ONLY_WARNINGS && WARNED_CODE_ONLY.add(key)) {
                log.warn(
                        "Agent '{}': programmatic-only names '{}', which declares {}: a tool that is not READ_ONLY "
                                + "cannot be offered only through code, so it stays in the direct offer",
                        getAgentName().orElse("?"), descriptor.getName(), descriptor.getSideEffectLevel());
            } else if (!WARNED_CODE_ONLY.contains(key)) {
                log.debug("Agent '{}': programmatic-only names '{}', which is not READ_ONLY; it stays in the direct "
                        + "offer", getAgentName().orElse("?"), descriptor.getName());
            }
            return false;
        }
        return true;
    }

    /** Forgets which invalid {@code programmatic-only} entries were warned about; for tests. */
    static void resetCodeOnlyWarnings() {
        WARNED_CODE_ONLY.clear();
    }

    /**
     * Describes {@code tool} in this context. Every offer path reads the descriptor through here, so a tool that breaks
     * {@link Tool#describe(ToolOfferContext)}'s never-null contract fails each of them the same way, naming the tool.
     */
    private ToolDescriptor describe(Tool tool) {
        return Objects.requireNonNull(tool.describe(this),
                () -> tool.getDefinition().getName() + ".describe returned null");
    }

    /**
     * The tools some offered tool of this offer hosts for code, computed once per offer context — by {@link #offer()}
     * on the way, or here on first use (a refusal on the direct path).
     */
    private Set<String> codeHosted() {
        Set<String> hosted = codeHosted;
        if (hosted != null) {
            return hosted;
        }
        if (registry == null || programmatic.getCodeOnly().isEmpty()) {
            hosted = Set.of();
        } else {
            final Set<String> collected = new HashSet<>();
            for (Tool tool : registry.findAll()) {
                if (!admission.test(tool)) {
                    continue;
                }
                final ToolDescriptor described = describe(tool);
                if (described.isOfferable() && permits(described)) {
                    collected.addAll(described.getCodeHostedTools());
                }
            }
            hosted = Set.copyOf(collected);
        }
        codeHosted = hosted;
        return hosted;
    }

    /**
     * Returns why each admitted tool the last {@link #offer()} left out as not offerable is not offerable, for the
     * tools that said so ({@link ToolDescriptor#getUnavailableReason()}) — {@code ExecuteCode}'s reason in an agent
     * that opens tools to code, which the loop records on the iteration's span ({@code ptc.unavailable_reason}).
     *
     * @return tool name to reason, in registry order (never null); empty before the first {@code offer()}
     */
    public Map<String, String> getUnavailableReasons() {
        return unavailableReasons;
    }

    /**
     * Returns the name of the agent this offer belongs to, for operator warnings.
     *
     * @return the agent's name, empty when the loop did not set one
     */
    public Optional<String> getAgentName() {
        return Optional.ofNullable(agentName);
    }

    /**
     * Returns a typed attribute.
     *
     * @param key
     *            the key (must not be null)
     * @param <T>
     *            the value type
     * @return the value, empty when not set
     * @throws NullPointerException
     *             if {@code key} is null
     */
    public <T> Optional<T> get(OfferKey<T> key) {
        Objects.requireNonNull(key, "key cannot be null");
        return Optional.ofNullable(attributes.get(key)).map(key.type()::cast);
    }

    @Override
    public String toString() {
        return offering
                ? "ToolOfferContext{ceiling=" + ceiling + ", attributes=" + attributes.keySet()
                        + (programmatic.isEnabled() ? ", programmatic=" + programmatic : "") + '}'
                : "ToolOfferContext.none()";
    }

    /** Builder for an offering {@link ToolOfferContext}. */
    public static final class Builder {
        private ToolRegistry registry;
        private Predicate<Tool> admission = tool -> true;
        private SideEffectLevel ceiling = SideEffectLevel.MUTATING;
        private final Map<OfferKey<?>, Object> attributes = new IdentityHashMap<>();
        private ProgrammaticToolSettings programmatic = ProgrammaticToolSettings.none();
        private String agentName;

        private Builder() {
        }

        /**
         * @param agentName
         *            the name of the agent the offer belongs to, for operator warnings; {@code null} for none
         * @return this builder
         */
        public Builder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        /**
         * @param programmatic
         *            what the agent opens to model-written code (must not be null); defaults to
         *            {@link ProgrammaticToolSettings#none()}
         * @return this builder
         */
        public Builder programmatic(ProgrammaticToolSettings programmatic) {
            this.programmatic = Objects.requireNonNull(programmatic, "programmatic cannot be null");
            return this;
        }

        /**
         * @param registry
         *            the registry the offer is drawn from (must not be null)
         * @return this builder
         */
        public Builder registry(ToolRegistry registry) {
            this.registry = Objects.requireNonNull(registry, "registry cannot be null");
            return this;
        }

        /**
         * @param admission
         *            the loop's allow-list as a filter (must not be null); defaults to admitting every tool
         * @return this builder
         */
        public Builder admission(Predicate<Tool> admission) {
            this.admission = Objects.requireNonNull(admission, "admission cannot be null");
            return this;
        }

        /**
         * @param ceiling
         *            the loop's side-effect ceiling (must not be null); defaults to {@link SideEffectLevel#MUTATING}
         * @return this builder
         */
        public Builder ceiling(SideEffectLevel ceiling) {
            this.ceiling = Objects.requireNonNull(ceiling, "ceiling cannot be null");
            return this;
        }

        /**
         * Sets a typed attribute, replacing any earlier value for the same key.
         *
         * @param key
         *            the key (must not be null)
         * @param value
         *            the value (must not be null)
         * @param <T>
         *            the value type
         * @return this builder
         */
        public <T> Builder put(OfferKey<T> key, T value) {
            Objects.requireNonNull(key, "key cannot be null");
            attributes.put(key, key.type().cast(Objects.requireNonNull(value, "value cannot be null")));
            return this;
        }

        /**
         * @return an offering context
         */
        public ToolOfferContext build() {
            return new ToolOfferContext(this, true);
        }
    }
}
