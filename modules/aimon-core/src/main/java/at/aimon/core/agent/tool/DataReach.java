package at.aimon.core.agent.tool;

import java.util.Objects;

/**
 * How far the data and network a {@link Tool} touches reach: the execution environment only, the host's own closed
 * systems, or the open outside world.
 *
 * <p>
 * This is a fourth declaration beside {@link SideEffectLevel}, {@link DestructiveBehavior} and
 * {@link IdempotencyBehavior}, and like them it is independent of the others — a read-only tool can reach the open
 * internet ({@code WebFetch}), and a destructive one can stay inside the environment ({@code Bash}). It answers the
 * question programmatic tool calling has to ask before it hands a tool to model-written code: if that code loops over
 * this tool, where can what it reads go and where can what it sends land (programmatic-tool-calling design §7.1).
 *
 * <h2>Ordered</h2>
 *
 * <p>
 * Unlike the two traits, this axis has a real order — {@link #ENVIRONMENT} reaches less than {@link #HOST}, which
 * reaches less than {@link #EXTERNAL} — so a grade composed of several tools takes the {@link #max(DataReach) widest}
 * of them.
 *
 * <h2>The default is the conservative end</h2>
 *
 * <p>
 * {@link #EXTERNAL} is the default of {@link Tool#dataReach()}, the same direction as the conservative defaults of the
 * other axes ({@code MUTATING}, {@code DESTRUCTIVE}): a tool nobody has audited is assumed to reach anything. Every
 * built-in tool declares its reach explicitly.
 *
 * @see Tool#dataReach()
 * @see ToolDescriptor#getDataReach()
 */
public enum DataReach {

    /**
     * Runs inside the execution environment and reaches nothing the environment does not already reach — {@code Read},
     * {@code Grep}, {@code Write}, {@code Edit}, {@code Bash}.
     */
    ENVIRONMENT(0),

    /**
     * Reaches a closed system on the host side: a host data store, or a system reached with the host's credentials —
     * memory, knowledge, session history, a trusted in-house MCP server that declares it is not open-world.
     */
    HOST(1),

    /**
     * Reaches the open outside world — {@code WebFetch}, {@code WebSearch}, an MCP tool whose claims are not trusted.
     * The default.
     */
    EXTERNAL(2);

    private final int rank;

    DataReach(int rank) {
        this.rank = rank;
    }

    /**
     * Returns the wider of this reach and {@code other}.
     *
     * @param other
     *            the reach to compare with (must not be null)
     * @return whichever of the two reaches further (never null)
     * @throws NullPointerException
     *             if {@code other} is null
     */
    public DataReach max(DataReach other) {
        Objects.requireNonNull(other, "other cannot be null");
        return other.rank > rank ? other : this;
    }
}
