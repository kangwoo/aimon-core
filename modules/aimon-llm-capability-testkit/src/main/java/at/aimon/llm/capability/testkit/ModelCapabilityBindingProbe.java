package at.aimon.llm.capability.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.beans.PropertyDescriptor;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import at.aimon.core.llm.capability.ModelCapabilityDeclaration;

/**
 * One configuration surface, probed one key at a time: whether the surface has the key, and whether a value written
 * to it reaches the {@link ModelCapabilityDeclaration} the surface's hand-written forwarding produces.
 *
 * <p>
 * Plain Java rather than a JUnit class, so this module's own tests can show the probe <em>failing</em> — against a fake
 * surface whose forwarding drops a key — and not only passing against surfaces that happen to be right.
 *
 * <h2>The round trip</h2>
 *
 * <p>
 * For one key and one value: a fresh surface, that one property written ({@link SurfaceWriter}), the surface's own
 * forwarding run, and the result compared with {@code isEqualTo} against the builder with exactly that setter called
 * ({@link DeclarableKeys#expectedDeclaration}). Whole-declaration equality says three things in one assertion — the key
 * arrived, it arrived with this value, and no other key arrived. The third is the half of a copy-paste slip a per-key
 * check would miss: in a chain of near-identical lines, one getter feeding two setters.
 *
 * <p>
 * It follows that a forwarding which defaults or derives a key rather than copying it fails. That is intended: both
 * forwardings are field copies, and the starter's says so in its javadoc. If a surface ever needs to depart from that,
 * the departure belongs in this class, visibly, once — not in a per-surface hook.
 *
 * <p>
 * The comparison rests on {@code equals} seeing the key, so before any round trip the two values of a key are checked
 * to give <em>unequal</em> expected declarations. A field added to the builder and forgotten in {@code equals} would
 * otherwise leave "arrived" intact and quietly remove "carried".
 *
 * <h2>One key at a time, and the refusal it buys</h2>
 *
 * <p>
 * The two ladder keys cannot be written together — the declaration refuses that on purpose — and with exactly one key
 * written, a dropped forwarding hands the builder nothing, which {@code build()} already refuses by name. That refusal
 * is worded for an operator who wrote an empty entry, so the probe rewrites it for the developer who forgot a line.
 *
 * <p>
 * It recognises <em>that one refusal</em> by its message, captured from {@code ModelCapabilityDeclaration.builder()}
 * {@code .build()} at run time so it follows any edit to the sentence, and looks for it anywhere in the cause chain,
 * because a surface may wrap it: the CLI's {@code declarationOf} rethrows it inside a {@code ConfigurationException}
 * that names the yaml key. It never recognises it by type. The ladder-pair refusal is an
 * {@code IllegalArgumentException} too, and so is a surface refusing a synthetic value; calling either of those "the
 * forwarding does not read this key" would send the reader to the wrong line. Anything else a forwarding throws is
 * reported with the key and value it was probed with, and left to speak for itself.
 *
 * <h2>Every pair is checked the same way</h2>
 *
 * <p>
 * {@link #assertValuesReachTheDeclaration(String, List)} holds a pair to the same rules whether {@link ProbeValues}
 * synthesised it or a subclass chose it: exactly two values, neither {@code null}, both of the setter's type, distinct,
 * accepted by the declaration, and told apart by its {@code equals}. Without that, an override returning an empty list
 * would be a skip with extra steps.
 *
 * @param <S>
 *            the configuration surface's type
 */
public final class ModelCapabilityBindingProbe<S> {

    private final Supplier<? extends S> newSurface;
    private final Function<? super S, ModelCapabilityDeclaration> forwarding;
    private final UnaryOperator<String> operatorKeyPath;
    private final String forwardingLocation;

    private ModelCapabilityBindingProbe(Builder<S> builder) {
        this.newSurface = builder.newSurface;
        this.forwarding = Objects.requireNonNull(builder.forwarding, "forwarding");
        this.operatorKeyPath = Objects.requireNonNull(builder.operatorKeyPath, "operatorKeyPath");
        this.forwardingLocation = Objects.requireNonNull(builder.forwardingLocation, "forwardingLocation");
    }

    /**
     * @param newSurface
     *            supplies a fresh, empty instance of the surface on every call
     * @param <S>
     *            the surface's type
     * @return a builder for a probe over that surface
     */
    public static <S> Builder<S> forSurface(Supplier<? extends S> newSurface) {
        return new Builder<>(newSurface);
    }

    /**
     * Check 1, #69's: the surface has a property named after the key, with both a getter and a setter.
     *
     * @param key
     *            a declarable key
     * @throws AssertionError
     *             if it does not
     */
    public void assertSurfaceCarries(String key) {
        Objects.requireNonNull(key, "key");
        final Class<?> surfaceType = surface().getClass();
        final PropertyDescriptor property = SurfaceWriter.property(surfaceType, key)
                .orElseThrow(() -> new AssertionError(surfaceType.getName() + " has no property `" + key
                        + "`, so an operator cannot write `" + operatorKeyPath.apply(key)
                        + "`. ModelCapabilityDeclaration accepts the key and its refusal message advertises it; add a"
                        + " field with a getter and a setter named after it."));
        if (property.getReadMethod() == null || property.getWriteMethod() == null) {
            throw new AssertionError(surfaceType.getName() + "'s property `" + key + "` has "
                    + (property.getReadMethod() == null ? "no getter" : "no setter") + ". It needs both: a binder"
                    + " writes it, and " + forwardingLocation + " has to read it.");
        }
    }

    /**
     * Check 2, #82's: each value, written as the only key on a fresh surface, reaches the declaration the surface's
     * forwarding produces — and nothing else does.
     *
     * @param key
     *            a declarable key
     * @param values
     *            the pair to probe with, synthesised or hand-picked
     * @throws AssertionError
     *             if the pair is unusable, or a value does not arrive, arrives changed, or arrives with company
     */
    public void assertValuesReachTheDeclaration(String key, List<?> values) {
        Objects.requireNonNull(key, "key");
        requireUsablePair(key, values);
        for (final Object value : values) {
            assertValueReachesTheDeclaration(key, value);
        }
    }

    static void requireDistinguishable(String key, List<?> values, ModelCapabilityDeclaration first,
            ModelCapabilityDeclaration second) {
        if (first.equals(second)) {
            throw new AssertionError("ModelCapabilityDeclaration.equals does not compare `" + key
                    + "`: declaring it as " + values.get(0) + " and as " + values.get(1)
                    + " gives two declarations that compare equal. This"
                    + " contract compares whole declarations, so it cannot see a wrong value for that key — a"
                    + " forwarding reading the wrong property would pass. Add the field to equals and hashCode.");
        }
    }

    static boolean isEmptyDeclarationRefusal(Throwable thrown) {
        final String refusal = emptyDeclarationRefusal();
        if (refusal == null) {
            return false;
        }
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = thrown; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException && refusal.equals(cause.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private void requireUsablePair(String key, List<?> values) {
        if (values == null || values.size() != 2) {
            throw new AssertionError("`" + key + "` was given "
                    + (values == null ? "no values" : values.size() + " value(s) " + values)
                    + " to probe with, and the probe needs exactly two, distinct. Zero checks nothing, and one cannot"
                    + " tell a value that is carried from one that is hard-coded.");
        }
        final Class<?> parameterType = SurfaceWriter.boxed(DeclarableKeys.setterFor(key).getParameterTypes()[0]);
        for (final Object value : values) {
            if (value == null) {
                throw new AssertionError("`" + key + "` was given a null probe value. Null is how a declaration says"
                        + " \"not declared\", so writing it tests nothing.");
            }
            if (!parameterType.isInstance(value)) {
                throw new AssertionError("`" + key + "` was given " + value + ", a " + value.getClass().getName()
                        + ", and its setter on ModelCapabilityDeclaration.Builder takes " + parameterType.getName()
                        + ".");
            }
        }
        if (values.get(0).equals(values.get(1))) {
            throw new AssertionError("`" + key + "` was given the same value twice (" + values.get(0) + "). The two"
                    + " values have to differ, or a forwarding that hard-codes that value passes.");
        }
        requireDistinguishable(key, values, acceptedDeclaration(key, values.get(0)),
                acceptedDeclaration(key, values.get(1)));
    }

    private static ModelCapabilityDeclaration acceptedDeclaration(String key, Object value) {
        try {
            return DeclarableKeys.expectedDeclaration(key, value);
        } catch (IllegalArgumentException e) {
            throw new AssertionError("ModelCapabilityDeclaration refuses `" + key + "` = " + value + " on its own,"
                    + " before any surface is involved, so it cannot be a probe value: pick one the declaration"
                    + " accepts.", e);
        }
    }

    private void assertValueReachesTheDeclaration(String key, Object value) {
        final S surface = surface();
        SurfaceWriter.write(surface, key, value, DeclarableKeys.parameterTypeOf(key));
        final ModelCapabilityDeclaration forwarded = forward(surface, key, value);
        assertThat(forwarded)
                .as("`%s` = %s was written on %s (`%s`) and passed through %s, so the declaration has to state that"
                        + " key, with that value, and nothing else. A missing field means the forwarding reads the"
                        + " wrong property; an extra one means one property feeds two setters", key, value,
                        surface.getClass().getName(), operatorKeyPath.apply(key), forwardingLocation)
                .isEqualTo(DeclarableKeys.expectedDeclaration(key, value));
    }

    private ModelCapabilityDeclaration forward(S surface, String key, Object value) {
        try {
            return forwarding.apply(surface);
        } catch (RuntimeException e) {
            if (isEmptyDeclarationRefusal(e)) {
                throw new AssertionError("`" + key + "` was written on " + surface.getClass().getName()
                        + " (an operator writes it as `" + operatorKeyPath.apply(key) + "`) and " + forwardingLocation
                        + " produced a declaration that states nothing at all — so that method does not read this key."
                        + " Add a `." + key + "(...)` call to it that reads this property. (What it threw is"
                        + " ModelCapabilityDeclaration's refusal of an empty declaration, worded for an operator who"
                        + " wrote an empty entry; it is attached as the cause.)", e);
            }
            throw new AssertionError(forwardingLocation + " threw while `" + key + "` = " + value
                    + " was the only key written on " + surface.getClass().getName() + ". That is not the refusal of"
                    + " an empty declaration, so it is not reported as a dropped forwarding; the cause says what it"
                    + " is.", e);
        }
    }

    private S surface() {
        return Objects.requireNonNull(newSurface.get(), "newSurface returned null");
    }

    private static String emptyDeclarationRefusal() {
        try {
            ModelCapabilityDeclaration.builder().build();
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        // The declaration stopped refusing an empty entry. A dropped forwarding then produces an empty declaration,
        // which the equality check still fails — it only loses the sentence that names the forwarding.
        return null;
    }

    /**
     * Builder for {@link ModelCapabilityBindingProbe}.
     *
     * @param <S>
     *            the surface's type
     */
    public static final class Builder<S> {
        private final Supplier<? extends S> newSurface;
        private Function<? super S, ModelCapabilityDeclaration> forwarding;
        private UnaryOperator<String> operatorKeyPath;
        private String forwardingLocation;

        private Builder(Supplier<? extends S> newSurface) {
            this.newSurface = Objects.requireNonNull(newSurface, "newSurface");
        }

        /**
         * @param forwarding
         *            the surface's own hand-written translation into a declaration — the step #82 is about
         * @return this builder
         */
        public Builder<S> forwarding(Function<? super S, ModelCapabilityDeclaration> forwarding) {
            this.forwarding = forwarding;
            return this;
        }

        /**
         * @param operatorKeyPath
         *            maps a key to how an operator writes it on this surface, for failure messages
         * @return this builder
         */
        public Builder<S> operatorKeyPath(UnaryOperator<String> operatorKeyPath) {
            this.operatorKeyPath = operatorKeyPath;
            return this;
        }

        /**
         * @param forwardingLocation
         *            where the forwarding lives, for failure messages — {@code "LlmClientFactory.declarationOf"}
         * @return this builder
         */
        public Builder<S> forwardingLocation(String forwardingLocation) {
            this.forwardingLocation = forwardingLocation;
            return this;
        }

        /**
         * @return a new {@link ModelCapabilityBindingProbe}
         * @throws NullPointerException
         *             if the forwarding, the operator key path or the forwarding location was not set
         */
        public ModelCapabilityBindingProbe<S> build() {
            return new ModelCapabilityBindingProbe<>(this);
        }
    }
}
