package at.aimon.core.agent;

import java.util.Objects;
import java.util.Optional;

/**
 * Value object representing an agent runtime identifier.
 *
 * <p>
 * The id is <b>agent-scoped</b> (see the scope model): the same {@code AgentRuntime} instance — and therefore
 * the same id — is shared by every session against a given {@link Agent}. Ids are derived deterministically from
 * the agent (and an optional {@code discriminator}) rather than generated as random UUIDs:
 *
 * <ul>
 * <li>{@code agent:<name>} — basic form. Use {@link #from(Agent)} or {@link #fromName(String)}.</li>
 * <li>{@code agent:<name>:<discriminator>} — when a single agent definition needs to be split per tenant / environment
 * / user. Use {@link #from(Agent, String)} or {@link #fromName(String, String)}.</li>
 * </ul>
 *
 * <p>
 * Stable, agent-derived ids let scheduled tasks (whose {@code boundRuntimeId} can survive far beyond any originating
 * session) reliably resolve their owning context from {@link AgentRuntimeRegistry} on cron re-fires —
 * including across multi-instance deployments where ids must agree between nodes.
 *
 * <p>
 * The class stores {@code agentName} and an optional {@code discriminator} as separate fields; the wire-format string
 * returned by {@link #value()} is derived from those fields, and {@link #of(String)} is the only path that parses an
 * external string into an instance. As a consequence {@link #agentName()} and {@link #discriminator()} are simple field
 * accessors and never throw — every reachable instance is guaranteed to satisfy the {@code agent:<name>[:disc]} layout.
 *
 * <p>
 * Instances are immutable and safe to use as map keys.
 *
 * <h2>Example</h2>
 *
 * <pre>
 * {@code
 * AgentRuntimeId id = AgentRuntimeId.from(agent);              // "agent:<name>"
 * AgentRuntimeId tenantId = AgentRuntimeId.from(agent, "acme"); // "agent:<name>:acme"
 * AgentRuntimeId restored = AgentRuntimeId.of("agent:foo");    // deserialization
 * }
 * </pre>
 */
public final class AgentRuntimeId {

    private static final String PREFIX = "agent:";
    private static final String SEPARATOR = ":";

    private final String agentName;
    private final String discriminator;
    private final String value;

    private AgentRuntimeId(String agentName, String discriminator) {
        this.agentName = agentName;
        this.discriminator = discriminator;
        this.value = discriminator == null ? PREFIX + agentName : PREFIX + agentName + SEPARATOR + discriminator;
    }

    /**
     * Reconstructs an {@link AgentRuntimeId} from its serialized string value of the form
     * {@code agent:<name>[:<discriminator>]}.
     *
     * <p>
     * Intended for deserialization / persistence-layer restore paths. Production code that derives an id from a live
     * {@link Agent} should prefer {@link #from(Agent)} or {@link #from(Agent, String)} so the agent-scoped contract is
     * enforced at the call site.
     *
     * @param value
     *            the wire-format value (must start with {@code agent:}, name segment must be non-blank, both segments
     *            must follow the {@code :}-separator convention)
     * @return a new id wrapping {@code value}
     * @throws NullPointerException
     *             when {@code value} is null
     * @throws IllegalArgumentException
     *             when {@code value} does not match {@code agent:<name>[:<discriminator>]}
     */
    public static AgentRuntimeId of(String value) {
        Objects.requireNonNull(value, "Agent runtime ID cannot be null");
        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Agent runtime ID must start with '" + PREFIX + "': " + value);
        }
        final String tail = value.substring(PREFIX.length());
        if (tail.isBlank()) {
            throw new IllegalArgumentException("Agent runtime ID has empty agent-name segment: " + value);
        }
        final int colon = tail.indexOf(SEPARATOR);
        if (colon < 0) {
            return new AgentRuntimeId(tail, null);
        }
        final String name = tail.substring(0, colon);
        final String disc = tail.substring(colon + 1);
        if (name.isBlank()) {
            throw new IllegalArgumentException("Agent runtime ID has empty agent-name segment: " + value);
        }
        if (disc.isBlank()) {
            throw new IllegalArgumentException("Agent runtime ID has empty discriminator segment: " + value);
        }
        if (disc.contains(SEPARATOR)) {
            throw new IllegalArgumentException(
                    "Agent runtime ID discriminator must not contain ':' (reserved separator): " + value);
        }
        return new AgentRuntimeId(name, disc);
    }

    /**
     * Derives an agent-scoped id from {@code agent} of the form {@code agent:<agent.getName()>}.
     *
     * @param agent
     *            the source agent (must not be null)
     * @return a stable id keyed on the agent name
     * @throws NullPointerException
     *             if {@code agent} is null
     */
    public static AgentRuntimeId from(Agent agent) {
        Objects.requireNonNull(agent, "Agent cannot be null");
        return fromName(agent.getName());
    }

    /**
     * Derives an agent-scoped id from {@code agent} and a {@code discriminator} of the form
     * {@code agent:<agent.getName()>:<discriminator>}. Use this overload to split a single agent definition into
     * separate agent runtimes (e.g., per tenant, per environment, per user).
     *
     * <p>
     * The {@code discriminator} must be non-null, non-blank, and must not contain a colon ({@code :}) — colons are
     * reserved as the segment separator and would make the id ambiguous on round-trip through {@link #of(String)}.
     *
     * @param agent
     *            the source agent (must not be null)
     * @param discriminator
     *            the discriminator suffix (must be non-blank and must not contain {@code ':'})
     * @return a stable id keyed on the agent name and discriminator
     * @throws NullPointerException
     *             if {@code agent} is null
     * @throws IllegalArgumentException
     *             if {@code discriminator} is null, blank, or contains {@code ':'}
     */
    public static AgentRuntimeId from(Agent agent, String discriminator) {
        Objects.requireNonNull(agent, "Agent cannot be null");
        return fromName(agent.getName(), discriminator);
    }

    /**
     * Derives an agent-scoped id from a raw agent name string of the form {@code agent:<agentName>}.
     *
     * <p>
     * Intended for callers that hold the agent name but not the live {@link Agent} instance — typically session
     * dispatch layers (e.g., {@code SessionRouter}) routing requests to a pre-registered context. Production
     * paths that resolve a live {@link Agent} should prefer {@link #from(Agent)} so the agent-name source is
     * unambiguous
     * at the call site.
     *
     * @param agentName
     *            the agent name (must be non-blank and must not contain {@code ':'})
     * @return a stable id keyed on the agent name
     * @throws IllegalArgumentException
     *             if {@code agentName} is null, blank, or contains {@code ':'}
     */
    public static AgentRuntimeId fromName(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("Agent name cannot be null or blank");
        }
        if (agentName.contains(SEPARATOR)) {
            throw new IllegalArgumentException(
                    "Agent name must not contain ':' (reserved separator); got: " + agentName);
        }
        return new AgentRuntimeId(agentName, null);
    }

    /**
     * Derives an agent-scoped id from a raw agent name and a {@code discriminator} of the form
     * {@code agent:<agentName>:<discriminator>}.
     *
     * <p>
     * See {@link #fromName(String)} for the calling convention. Both {@code agentName} and {@code discriminator} must
     * be non-blank and must not contain colons.
     *
     * @param agentName
     *            the agent name (must be non-blank and must not contain {@code ':'})
     * @param discriminator
     *            the discriminator suffix (must be non-blank and must not contain {@code ':'})
     * @return a stable id keyed on the agent name and discriminator
     * @throws IllegalArgumentException
     *             if any argument is null, blank, or contains {@code ':'}
     */
    public static AgentRuntimeId fromName(String agentName, String discriminator) {
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("Agent name cannot be null or blank");
        }
        if (agentName.contains(SEPARATOR)) {
            throw new IllegalArgumentException(
                    "Agent name must not contain ':' (reserved separator); got: " + agentName);
        }
        if (discriminator == null || discriminator.isBlank()) {
            throw new IllegalArgumentException("Discriminator cannot be null or blank");
        }
        if (discriminator.contains(SEPARATOR)) {
            throw new IllegalArgumentException(
                    "Discriminator must not contain ':' (reserved separator); got: " + discriminator);
        }
        return new AgentRuntimeId(agentName, discriminator);
    }

    /**
     * Returns the wire-format identifier value of the form {@code agent:<name>[:<discriminator>]}.
     *
     * @return the identifier value (never null or blank)
     */
    public String value() {
        return value;
    }

    /**
     * Returns the agent-name segment, i.e., the part between the {@code agent:} prefix and the optional discriminator.
     *
     * @return the agent name (never null or blank)
     */
    public String agentName() {
        return agentName;
    }

    /**
     * Returns the discriminator segment, if any.
     *
     * @return the discriminator, or {@link Optional#empty()} when the id is in bare {@code agent:<name>} form
     */
    public Optional<String> discriminator() {
        return Optional.ofNullable(discriminator);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AgentRuntimeId that = (AgentRuntimeId) o;
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
