package at.aimon.core.programmatic;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentIsolation;
import at.aimon.core.shell.ShellFeature;

/**
 * The static facts an engine may judge its availability on at offer time
 * ({@code docs/design/tool/programmatic-tool-calling.md} §6.2, §9): nothing here was obtained by running a command or
 * writing a file, so reading it never provisions a sandbox.
 *
 * <p>
 * Immutable. Use {@link #builder()}; {@link #none()} is the facts of an offer that knows no environment.
 */
public final class OfferFacts {

    private static final OfferFacts NONE = builder().build();

    private final EnvironmentDescriptor descriptor;
    private final Set<ShellFeature> shellFeatures;
    private final boolean runtimeFileSystem;
    private final boolean bashUnconstrained;

    private OfferFacts(Builder builder) {
        this.descriptor = builder.descriptor;
        this.shellFeatures = Collections.unmodifiableSet(builder.shellFeatures.isEmpty()
                ? EnumSet.noneOf(ShellFeature.class)
                : EnumSet.copyOf(builder.shellFeatures));
        this.runtimeFileSystem = builder.runtimeFileSystem;
        this.bashUnconstrained = builder.bashUnconstrained;
    }

    /**
     * Returns the facts of an offer that knows no environment.
     *
     * @return the shared empty facts (never null)
     */
    public static OfferFacts none() {
        return NONE;
    }

    /**
     * Returns a new builder.
     *
     * @return a new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the descriptor of the execution environment the offer's tools run in.
     *
     * @return the descriptor, empty when the offer knows no environment
     */
    public Optional<EnvironmentDescriptor> getDescriptor() {
        return Optional.ofNullable(descriptor);
    }

    /**
     * Returns the environment's declared isolation.
     *
     * @return the isolation, empty when there is no environment or it does not declare one
     */
    public Optional<EnvironmentIsolation> getIsolation() {
        return getDescriptor().flatMap(EnvironmentDescriptor::isolation);
    }

    /**
     * Returns the runtimes the environment's descriptor declares ({@link EnvironmentDescriptor#runtimes()}).
     *
     * @return the runtimes (never null; empty without an environment)
     */
    public Set<String> getRuntimes() {
        return getDescriptor().map(EnvironmentDescriptor::runtimes).orElse(Set.of());
    }

    /**
     * Returns the features the environment's shell declares ({@code VirtualShell#supports}).
     *
     * @return the features (never null; empty without an environment)
     */
    public Set<ShellFeature> getShellFeatures() {
        return shellFeatures;
    }

    /**
     * Returns whether the environment offers a runtime file system ({@code ExecutionEnvironment#runtimeFileSystem()}).
     *
     * @return {@code true} when it does
     */
    public boolean hasRuntimeFileSystem() {
        return runtimeFileSystem;
    }

    /**
     * Returns whether the offer's agent can use {@code Bash} without constraint
     * ({@code docs/design/tool/programmatic-tool-calling-shell.md} §4): {@code Bash} is eligible for this offer, the
     * allow-list names it with no argument pattern (an empty, "unrestricted" allow-list does not count), and no
     * {@code PermissionRequest} or {@code PreTool} hook can apply to it. Code the shell engine runs can do what
     * {@code Bash} does without passing through it, so it is offered only to an agent whose {@code Bash} is not
     * narrowed.
     *
     * @return {@code true} when {@code Bash} is unconstrained here
     */
    public boolean isBashUnconstrained() {
        return bashUnconstrained;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof OfferFacts other && Objects.equals(descriptor, other.descriptor)
                && shellFeatures.equals(other.shellFeatures) && runtimeFileSystem == other.runtimeFileSystem
                && bashUnconstrained == other.bashUnconstrained);
    }

    @Override
    public int hashCode() {
        return Objects.hash(descriptor, shellFeatures, runtimeFileSystem, bashUnconstrained);
    }

    @Override
    public String toString() {
        return "OfferFacts{isolation=" + getIsolation().map(Enum::name).orElse("undeclared") + ", runtimes="
                + getRuntimes() + ", shellFeatures=" + shellFeatures + ", runtimeFileSystem=" + runtimeFileSystem
                + ", bashUnconstrained=" + bashUnconstrained + '}';
    }

    /** Builder for {@link OfferFacts}. */
    public static final class Builder {

        private EnvironmentDescriptor descriptor;
        private final Set<ShellFeature> shellFeatures = EnumSet.noneOf(ShellFeature.class);
        private boolean runtimeFileSystem;
        private boolean bashUnconstrained;

        private Builder() {
        }

        /**
         * @param shellFeatures
         *            the features the environment's shell declares (must not be null)
         * @return this builder
         */
        public Builder shellFeatures(Collection<ShellFeature> shellFeatures) {
            Objects.requireNonNull(shellFeatures, "shellFeatures cannot be null");
            this.shellFeatures.clear();
            this.shellFeatures.addAll(shellFeatures);
            return this;
        }

        /**
         * @param runtimeFileSystem
         *            whether the environment offers a runtime file system
         * @return this builder
         */
        public Builder runtimeFileSystem(boolean runtimeFileSystem) {
            this.runtimeFileSystem = runtimeFileSystem;
            return this;
        }

        /**
         * @param bashUnconstrained
         *            whether the agent can use {@code Bash} without constraint
         *            ({@link OfferFacts#isBashUnconstrained()})
         * @return this builder
         */
        public Builder bashUnconstrained(boolean bashUnconstrained) {
            this.bashUnconstrained = bashUnconstrained;
            return this;
        }

        /**
         * @param descriptor
         *            the environment's descriptor, or {@code null} when the offer knows no environment
         * @return this builder
         */
        public Builder descriptor(EnvironmentDescriptor descriptor) {
            this.descriptor = descriptor;
            return this;
        }

        /**
         * @return the facts (never null)
         */
        public OfferFacts build() {
            return new OfferFacts(this);
        }
    }
}
