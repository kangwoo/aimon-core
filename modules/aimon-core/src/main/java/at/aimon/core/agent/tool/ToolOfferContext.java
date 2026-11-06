package at.aimon.core.agent.tool;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * What one iteration offers the model, and the facts a tool may read to describe itself for that offer.
 *
 * <p>
 * Every loop that offers tools to a model — the main loop, a fork, a slash-command skill loop — builds one of these per
 * iteration and both filters its offer through it and binds it into the iteration's tool context under
 * {@link #CONTEXT_KEY} ({@code ToolContextKeys.TOOL_OFFER_CONTEXT}). The offer filter, the side-effect ceiling's
 * refusal
 * and the approval gate then all read the same {@link Tool#describe(ToolOfferContext) descriptor}, so a tool whose
 * grade depends on its context cannot be offered under one grade and judged under another
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
 * Iteration-scoped and immutable. The registry it holds may itself grow (a deferred tool activated mid-turn), which is
 * why it is rebuilt every iteration rather than once per execution.
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

    private static final ToolOfferContext NONE = new ToolOfferContext(new Builder(), false);

    private final boolean offering;
    private final ToolRegistry registry;
    private final Predicate<Tool> admission;
    private final SideEffectLevel ceiling;
    private final Map<OfferKey<?>, Object> attributes;

    private ToolOfferContext(Builder builder, boolean offering) {
        this.offering = offering;
        this.registry = builder.registry;
        this.admission = builder.admission;
        this.ceiling = builder.ceiling;
        this.attributes = new IdentityHashMap<>(builder.attributes);
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
     * Returns the descriptors of the registry's tools this offer makes: admitted, described in this context, and
     * within the ceiling — in the registry's order. Each tool is described once.
     *
     * @return the offered descriptors (never null); empty without a registry
     */
    public List<ToolDescriptor> offer() {
        if (registry == null) {
            return List.of();
        }
        final List<ToolDescriptor> offered = new ArrayList<>();
        for (Tool tool : registry.findAll()) {
            if (!admission.test(tool)) {
                continue;
            }
            final ToolDescriptor descriptor = tool.describe(this);
            if (permits(descriptor)) {
                offered.add(descriptor);
            }
        }
        return List.copyOf(offered);
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
                ? "ToolOfferContext{ceiling=" + ceiling + ", attributes=" + attributes.keySet() + '}'
                : "ToolOfferContext.none()";
    }

    /** Builder for an offering {@link ToolOfferContext}. */
    public static final class Builder {
        private ToolRegistry registry;
        private Predicate<Tool> admission = tool -> true;
        private SideEffectLevel ceiling = SideEffectLevel.MUTATING;
        private final Map<OfferKey<?>, Object> attributes = new IdentityHashMap<>();

        private Builder() {
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
