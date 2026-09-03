package at.aimon.core.memory;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Computes what a {@link PeerMemory} backend can actually do, by looking at its five tier accessors.
 *
 * <p>
 * <b>This is a static utility rather than a method on {@link PeerMemory}, and that placement is the whole point.</b> A
 * capability set that a backend declares is a second source of truth: it can disagree with the tiers, and the
 * disagreement surfaces not at assembly but at the first call, after a tool has already been registered and offered to
 * the model. Deriving the set here leaves nothing to disagree with — and because there is no method to override, the
 * derivation cannot be replaced by a claim.
 *
 * <p>
 * An {@code abstract class} with a {@code final} method would enforce the same thing, but it would force every adapter
 * into a superclass; a {@code default} method on the interface would enforce nothing at all.
 */
public final class MemoryCapabilities {

    private MemoryCapabilities() {
    }

    /**
     * Returns the capabilities {@code backend} serves, derived from which of its tier accessors are present.
     *
     * @param backend
     *            the backend to inspect (must not be null)
     * @return an immutable set, possibly empty; never null
     * @throws NullPointerException
     *             if {@code backend} is null
     */
    public static Set<MemoryCapability> of(PeerMemory backend) {
        Objects.requireNonNull(backend, "backend must not be null");
        EnumSet<MemoryCapability> capabilities = EnumSet.noneOf(MemoryCapability.class);
        if (backend.snapshotReader().isPresent()) {
            capabilities.add(MemoryCapability.SNAPSHOT);
        }
        if (backend.searcher().isPresent()) {
            capabilities.add(MemoryCapability.SEARCH);
        }
        if (backend.dialecticEngine().isPresent()) {
            capabilities.add(MemoryCapability.CHAT);
        }
        if (backend.observationRecorder().isPresent()) {
            capabilities.add(MemoryCapability.OBSERVE);
        }
        if (backend.ingestor().isPresent()) {
            capabilities.add(MemoryCapability.INGEST);
        }
        return Collections.unmodifiableSet(capabilities);
    }

    /**
     * Returns the capabilities {@code backend} does <em>not</em> serve — the complement of {@link #of(PeerMemory)}.
     *
     * <p>
     * Assemblies use this to say what a deployment loses, one consequence per missing capability, instead of leaving
     * an operator to infer it from a memory that never answers.
     *
     * @param backend
     *            the backend to inspect (must not be null)
     * @return an immutable set, possibly empty; never null
     * @throws NullPointerException
     *             if {@code backend} is null
     */
    public static Set<MemoryCapability> missingFrom(PeerMemory backend) {
        EnumSet<MemoryCapability> present = EnumSet.noneOf(MemoryCapability.class);
        present.addAll(of(backend));
        return Collections.unmodifiableSet(EnumSet.complementOf(present));
    }
}
