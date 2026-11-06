package at.aimon.core.tools.code;

import java.util.Objects;

import at.aimon.core.agent.tool.OfferKey;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolOfferContext;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.programmatic.OfferFacts;
import at.aimon.core.tools.ExecutionEnvironmentAccess;

/**
 * The offer-context keys for the environment facts {@code ExecuteCode} reads at offer time
 * ({@code docs/design/tool/programmatic-tool-calling.md} §4 (a), §6.2).
 *
 * <p>
 * {@code at.aimon.core.agent.tool} may not depend on the environment, so the keys live here, with their one reader. A
 * loop that builds a {@link ToolOfferContext} calls {@link #putEnvironmentFacts} with its tool context; reading the
 * descriptor never provisions anything.
 */
public final class CodeOfferKeys {

    /** The descriptor of the iteration's execution environment. */
    public static final OfferKey<EnvironmentDescriptor> ENVIRONMENT_DESCRIPTOR = OfferKey.of("environmentDescriptor",
            EnvironmentDescriptor.class);

    private CodeOfferKeys() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Copies the static environment facts of {@code toolContext} into {@code builder}: the environment's descriptor,
     * when the context carries an environment.
     *
     * @param builder
     *            the offer context being built (must not be null)
     * @param toolContext
     *            the iteration's tool context (must not be null)
     * @return {@code builder}
     */
    public static ToolOfferContext.Builder putEnvironmentFacts(ToolOfferContext.Builder builder,
            ToolContext toolContext) {
        Objects.requireNonNull(builder, "builder cannot be null");
        Objects.requireNonNull(toolContext, "toolContext cannot be null");
        ExecutionEnvironmentAccess.of(toolContext)
                .ifPresent(environment -> builder.put(ENVIRONMENT_DESCRIPTOR, environment.descriptor()));
        return builder;
    }

    /**
     * Returns the static facts an engine judges its availability on, read from an offer context.
     *
     * @param offer
     *            the offer context (must not be null)
     * @return the facts (never null)
     */
    public static OfferFacts factsOf(ToolOfferContext offer) {
        Objects.requireNonNull(offer, "offer cannot be null");
        return OfferFacts.builder().descriptor(offer.get(ENVIRONMENT_DESCRIPTOR).orElse(null)).build();
    }
}
