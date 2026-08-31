package at.aimon.core.skill.hook.declarative;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.hook.rewake.RewakeSpec;

/**
 * Optional, config-derived settings shared by every declarative hook.
 *
 * <p>
 * These are the knobs that come from the hook's <i>declaration</i> rather than from its action, and that every
 * declarative hook class would otherwise have to thread through its own constructor overload. Bundling them keeps a
 * single extra constructor parameter per hook class as the surface grows.
 *
 * <p>
 * Carries:
 * <ul>
 * <li><b>hookIdDiscriminator</b> — makes {@link at.aimon.core.hook.execution.ExecutionHook#getHookId()} unique among
 * same-class siblings. See {@link DeclarativeHookId} for why this matters.
 * <li><b>rewakeSpec</b> — the parsed {@code asyncRewake} block. Hooks that honour it attach the spec to every
 * {@code HookResult} they emit on a firing path, which schedules a re-fire through the async-rewake machinery. The
 * chain is bounded by {@link RewakeSpec#getMaxAttempts()}, so re-attaching on each fire terminates rather than looping.
 * </ul>
 *
 * <p>
 * Not every hook event can be re-fired: {@code DefaultRewakeFireListener} can only rebuild contexts for
 * {@code PRE_TOOL}, {@code ON_CONFIG_RELOAD}, {@code PRE_COMPACT}, {@code ON_SESSION_START} and
 * {@code ON_SESSION_END}. Callers are responsible for rejecting {@code asyncRewake} on other events — see
 * {@code HookRegistryApplier}, which warns and drops the spec rather than registering a hook whose rewake could only
 * ever be discarded at fire time. Hook classes for non-rewakeable events therefore ignore {@link #getRewakeSpec()}.
 *
 * <p>
 * Immutable; thread-safe.
 */
public final class DeclarativeHookOptions {

    private static final DeclarativeHookOptions NONE = builder().build();

    private final String hookIdDiscriminator;
    private final RewakeSpec rewakeSpec;

    private DeclarativeHookOptions(Builder builder) {
        this.hookIdDiscriminator = builder.hookIdDiscriminator;
        this.rewakeSpec = builder.rewakeSpec;
    }

    /**
     * Returns the shared empty instance — no discriminator, no rewake.
     *
     * @return the empty options (never null)
     */
    public static DeclarativeHookOptions none() {
        return NONE;
    }

    /**
     * Returns options carrying only a hook-id discriminator.
     *
     * @param hookIdDiscriminator
     *            reload-stable discriminator (may be null)
     * @return new options (never null)
     */
    public static DeclarativeHookOptions ofDiscriminator(String hookIdDiscriminator) {
        return builder().hookIdDiscriminator(hookIdDiscriminator).build();
    }

    /**
     * Creates a new builder.
     *
     * @return a fresh builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return the reload-stable discriminator distinguishing this hook from same-class siblings, or {@code null}
     */
    public String getHookIdDiscriminator() {
        return hookIdDiscriminator;
    }

    /**
     * @return the parsed {@code asyncRewake} spec, or empty when the hook does not opt into async rewake
     */
    public Optional<RewakeSpec> getRewakeSpec() {
        return Optional.ofNullable(rewakeSpec);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DeclarativeHookOptions that)) {
            return false;
        }
        return Objects.equals(hookIdDiscriminator, that.hookIdDiscriminator)
                && Objects.equals(rewakeSpec, that.rewakeSpec);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hookIdDiscriminator, rewakeSpec);
    }

    @Override
    public String toString() {
        return "DeclarativeHookOptions{hookIdDiscriminator='" + hookIdDiscriminator + "', rewakeSpec=" + rewakeSpec
                + '}';
    }

    /** Builder for {@link DeclarativeHookOptions}. */
    public static final class Builder {

        private String hookIdDiscriminator;
        private RewakeSpec rewakeSpec;

        private Builder() {
        }

        /**
         * @param hookIdDiscriminator
         *            reload-stable discriminator distinguishing this hook from same-class siblings (may be null)
         * @return this builder
         */
        public Builder hookIdDiscriminator(String hookIdDiscriminator) {
            this.hookIdDiscriminator = hookIdDiscriminator;
            return this;
        }

        /**
         * @param rewakeSpec
         *            parsed {@code asyncRewake} spec (may be null)
         * @return this builder
         */
        public Builder rewakeSpec(RewakeSpec rewakeSpec) {
            this.rewakeSpec = rewakeSpec;
            return this;
        }

        /**
         * @return the built options (never null)
         */
        public DeclarativeHookOptions build() {
            return new DeclarativeHookOptions(this);
        }
    }
}
