package at.aimon.core.mcp;

import java.util.Objects;

import at.aimon.core.agent.tool.DataReach;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.IdempotencyBehavior;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.mcp.McpServerConfig.AnnotationTrust;

/**
 * The AIMON declarations an {@link McpTool} will make, resolved once from what a server claimed
 * ({@link McpToolAnnotations}) and how far that server is believed ({@link AnnotationTrust}).
 *
 * <p>
 * This is where the protocol's hints stop being hints. Everything downstream — the side-effect ceiling, the approval
 * gate — reads {@link at.aimon.core.agent.tool.Tool#getSideEffectLevel()} and
 * {@link at.aimon.core.agent.tool.Tool#getDestructiveBehavior()} (and the routine executor
 * {@link at.aimon.core.agent.tool.Tool#getIdempotencyBehavior()}) and cannot tell an MCP tool from a local one, which
 * is the point: an MCP tool that is trusted to be read-only is read-only in exactly the sense a local tool is.
 * Confining the trust decision to this one resolution is what keeps that true.
 *
 * <h2>Resolution</h2>
 *
 * <ul>
 * <li>{@link AnnotationTrust#IGNORE} — the annotations are not read at all. Every tool is
 * {@link SideEffectLevel#MUTATING} + {@link DestructiveBehavior#DESTRUCTIVE} +
 * {@link IdempotencyBehavior#NON_IDEMPOTENT}, which is where MCP tools sat before this existed, so the default changes
 * nothing about an existing deployment.
 * <li>{@link AnnotationTrust#TRUST} — {@code readOnlyHint} becomes the level, {@code destructiveHint} the
 * destructiveness and {@code idempotentHint} the idempotency, each with its MCP default applied when absent.
 * </ul>
 *
 * <p>
 * <b>Data reach.</b> {@code openWorldHint} feeds {@link DataReach}, read by programmatic tool calling. Under
 * {@link AnnotationTrust#IGNORE} the reach is {@link DataReach#EXTERNAL}. Under {@link AnnotationTrust#TRUST} it is
 * {@link DataReach#HOST} when the server says {@code openWorldHint: false}, and {@link DataReach#EXTERNAL} when the
 * hint is absent or {@code true} (the MCP default). It is never {@link DataReach#ENVIRONMENT}: an MCP tool runs on its
 * server, not inside the agent's execution environment, so the most a trusted closed-world claim can earn is
 * {@code HOST} ({@code docs/design/integration/mcp-tool.md} D13).
 *
 * <p>
 * One normalisation: a tool resolved to {@link SideEffectLevel#READ_ONLY} is always
 * {@link DestructiveBehavior#NON_DESTRUCTIVE} and {@link IdempotencyBehavior#IDEMPOTENT}, even if the server sent
 * {@code destructiveHint: true} alongside
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
            DestructiveBehavior.DESTRUCTIVE, IdempotencyBehavior.NON_IDEMPOTENT, DataReach.EXTERNAL);

    private final SideEffectLevel sideEffectLevel;
    private final DestructiveBehavior destructiveBehavior;
    private final IdempotencyBehavior idempotencyBehavior;
    private final DataReach dataReach;

    private McpToolTraits(SideEffectLevel sideEffectLevel, DestructiveBehavior destructiveBehavior,
            IdempotencyBehavior idempotencyBehavior, DataReach dataReach) {
        this.sideEffectLevel = sideEffectLevel;
        this.destructiveBehavior = destructiveBehavior;
        this.idempotencyBehavior = idempotencyBehavior;
        this.dataReach = dataReach;
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
     * Folds a server's annotations and the trust placed in that server into the three declarations the tool will make.
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
        final DataReach reach = annotations.isOpenWorld() ? DataReach.EXTERNAL : DataReach.HOST;
        if (annotations.isReadOnly()) {
            return new McpToolTraits(SideEffectLevel.READ_ONLY, DestructiveBehavior.NON_DESTRUCTIVE,
                    IdempotencyBehavior.IDEMPOTENT, reach);
        }
        return new McpToolTraits(SideEffectLevel.MUTATING,
                annotations.isDestructive() ? DestructiveBehavior.DESTRUCTIVE : DestructiveBehavior.NON_DESTRUCTIVE,
                annotations.isIdempotent() ? IdempotencyBehavior.IDEMPOTENT : IdempotencyBehavior.NON_IDEMPOTENT,
                reach);
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

    /**
     * Returns the idempotency the tool will declare.
     *
     * @return the idempotency (never null)
     */
    public IdempotencyBehavior getIdempotencyBehavior() {
        return idempotencyBehavior;
    }

    /**
     * Returns how far the tool will declare it reaches: {@link DataReach#HOST} only for a trusted server that says
     * {@code openWorldHint: false}, otherwise {@link DataReach#EXTERNAL}.
     *
     * @return the reach (never null)
     */
    public DataReach getDataReach() {
        return dataReach;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof McpToolTraits other)) {
            return false;
        }
        return sideEffectLevel == other.sideEffectLevel && destructiveBehavior == other.destructiveBehavior
                && idempotencyBehavior == other.idempotencyBehavior && dataReach == other.dataReach;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sideEffectLevel, destructiveBehavior, idempotencyBehavior, dataReach);
    }

    @Override
    public String toString() {
        return "McpToolTraits{" + sideEffectLevel + "/" + destructiveBehavior + "/" + idempotencyBehavior + "/"
                + dataReach + '}';
    }
}
