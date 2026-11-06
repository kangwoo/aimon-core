package at.aimon.core.programmatic;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentIsolation;

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

    private OfferFacts(Builder builder) {
        this.descriptor = builder.descriptor;
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

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof OfferFacts other && Objects.equals(descriptor, other.descriptor));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(descriptor);
    }

    @Override
    public String toString() {
        return "OfferFacts{isolation=" + getIsolation().map(Enum::name).orElse("undeclared") + '}';
    }

    /** Builder for {@link OfferFacts}. */
    public static final class Builder {

        private EnvironmentDescriptor descriptor;

        private Builder() {
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
