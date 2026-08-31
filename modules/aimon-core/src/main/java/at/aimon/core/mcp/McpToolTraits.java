package at.aimon.core.mcp;

import java.util.Objects;

import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.mcp.McpServerConfig.AnnotationTrust;

/**
 * The AIMON declarations an {@link McpTool} will make, resolved once from what a server claimed
 * ({@link McpToolAnnotations}) and how far that server is believed ({@link AnnotationTrust}).
 *
 * <p>
 * This is where the protocol's hints stop being hints. Everything downstream — the side-effect ceiling, the approval
 * gate — reads {@link at.aimon.core.agent.tool.Tool#getSideEffectLevel()} and
 * {@link at.aimon.core.agent.tool.Tool#getDestructiveBehavior()} and cannot tell an MCP tool from a local one, which is
 * the point: an MCP tool that is trusted to be read-only is read-only in exactly the sense a local tool is. Confining
 * the trust decision to this one resolution is what keeps that true.
 *
 * <h2>Resolution</h2>
 *
 * <ul>
 * <li>{@link AnnotationTrust#IGNORE} — the annotations are not read at all. Every tool is
 * {@link SideEffectLevel#MUTATING} + {@link DestructiveBehavior#DESTRUCTIVE}, which is where MCP tools sat before this
 * existed, so the default changes nothing about an existing deployment.
 * <li>{@link AnnotationTrust#TRUST} — {@code readOnlyHint} becomes the level and {@code destructiveHint} the
 * destructiveness, each with its MCP default applied when absent.
 * </ul>
 *
 * <p>
 * One normalisation: a tool resolved to {@link SideEffectLevel#READ_ONLY} is always
 * {@link DestructiveBehavior#NON_DESTRUCTIVE}, even if the server sent {@code destructiveHint: true} alongside
 * {@code readOnlyHint: true}. Consumers never read the second axis below {@code MUTATING}, so the combination could
 * only ever be stored, never acted on; storing it anyway would make {@code toString()} and any future reader disagree
 * with the behaviour. Note that this resolves the contradiction in the <em>permissive</em> direction — that is
 * deliberate, because a server contradicting itself under {@code TRUST} has already been believed about the more
 * consequential of the two claims.
 *
 * @see McpToolAnnotations
 * @see AnnotationTrust
 */
public final class McpToolTraits {

    private static final McpToolTraits UNTRUSTED = new McpToolTraits(SideEffectLevel.MUTATING,
            DestructiveBehavior.DESTRUCTIVE);

    private final SideEffectLevel sideEffectLevel;
    private final DestructiveBehavior destructiveBehavior;

    private McpToolTraits(SideEffectLevel sideEffectLevel, DestructiveBehavior destructiveBehavior) {
        this.sideEffectLevel = sideEffectLevel;
        this.destructiveBehavior = destructiveBehavior;
    }

    /**
     * Returns the traits of a tool whose own claims are not being read: the conservative end of both axes, identical to
     * what an in-tree tool that declares nothing gets.
     *
     * @return the untrusted traits (never null)
     */
    public static McpToolTraits untrusted() {
        return UNTRUSTED;
    }

    /**
     * Folds a server's annotations and the trust placed in that server into the two declarations the tool will make.
     *
     * @param annotations
     *            what the server claimed (must not be null; use {@link McpToolAnnotations#empty()} when it claimed
     *            nothing)
     * @param trust
     *            how far the server is believed (must not be null)
     * @return the resolved traits (never null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public static McpToolTraits resolve(McpToolAnnotations annotations, AnnotationTrust trust) {
        Objects.requireNonNull(annotations, "annotations cannot be null");
        Objects.requireNonNull(trust, "trust cannot be null");

        if (trust != AnnotationTrust.TRUST) {
            return UNTRUSTED;
        }
        if (annotations.isReadOnly()) {
            return new McpToolTraits(SideEffectLevel.READ_ONLY, DestructiveBehavior.NON_DESTRUCTIVE);
        }
        return new McpToolTraits(SideEffectLevel.MUTATING,
                annotations.isDestructive() ? DestructiveBehavior.DESTRUCTIVE : DestructiveBehavior.NON_DESTRUCTIVE);
    }

    /**
     * @return the level the tool will declare (never null)
     */
    public SideEffectLevel getSideEffectLevel() {
        return sideEffectLevel;
    }

    /**
     * @return the destructiveness the tool will declare (never null)
     */
    public DestructiveBehavior getDestructiveBehavior() {
        return destructiveBehavior;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof McpToolTraits other)) {
            return false;
        }
        return sideEffectLevel == other.sideEffectLevel && destructiveBehavior == other.destructiveBehavior;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sideEffectLevel, destructiveBehavior);
    }

    @Override
    public String toString() {
        return "McpToolTraits{" + sideEffectLevel + "/" + destructiveBehavior + '}';
    }
}
