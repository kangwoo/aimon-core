package at.aimon.bootstrap.spec;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import at.aimon.core.base.ExternallyManaged;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.approval.SkillApprovalChannel;
import at.aimon.core.skill.policy.pending.PendingTurn;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;
import at.aimon.core.skill.policy.session.SessionApprovalStore;

/**
 * Declares how the stack answers "may this skill run?" when no prior approval covers it.
 *
 * <p>
 * Two things combine to produce an answer, and both live here:
 *
 * <ol>
 * <li>the <b>default decision</b> of the rule-based policy at the end of the approval chain — consulted only
 * after the session-scoped and agent-scoped approval stores both miss;
 * <li>the <b>approval channel</b>, which is what an {@link SkillInvocationDecision#ASK} is actually asked
 * <i>through</i>.
 * </ol>
 *
 * <p>
 * They are easy to get wrong independently. {@code ASK} with no usable channel is not "prompt the user", it is
 * a denial with extra steps — which is exactly right for an interactive CLI that can fall back to a suspend
 * flow, and exactly wrong for a headless service, where it produces a skill that silently never runs. So this
 * type ships two named presets rather than leaving the pair to be assembled by hand:
 *
 * <ul>
 * <li>{@link #denyAll()} — {@code ASK} + a deny-all channel. Fail-closed. The right default for a service.
 * <li>{@link #allowList(Collection)} — {@code ASK} + a channel that auto-approves a fixed set of skill names.
 * The right shape for a service that runs a known, audited set of skills.
 * <li>{@link #suspend()} — {@code ASK} + <i>no channel</i>, so the turn suspends and waits to be approved from
 * outside. The right shape for a service that has somewhere to route the question to.
 * </ul>
 *
 * <p>
 * An interactive front end supplies its own channel with {@link #channel(SkillApprovalChannel)}, or — when the
 * channel needs the stack's own approval stores to write answers into —
 * {@link #channelFactory(SkillApprovalChannelFactory)}. That pair is the seam the CLI's terminal prompt
 * occupies.
 *
 * <h2>Pending turns</h2>
 *
 * <p>
 * The third thing here follows from the second. When an {@code ASK} cannot be answered inline — no terminal is
 * bound, the caller is a scheduled task — the turn is <i>suspended</i> rather than denied, and waits in the
 * pending-turn registry for an out-of-band {@code /approve}. Waits, but not forever: entries expire after
 * {@link #withPendingTurnTtl(Duration) a TTL}, a reaper sweeps them on
 * {@link #withPendingTurnSweepInterval(Duration) an interval}, and
 * {@link #withPendingTurnExpirationListener(Consumer) a listener} is how a front end tells the user that a
 * turn they were asked about has quietly gone away. Without it the turn simply vanishes from {@code /pending}
 * with no explanation, which reads as a bug.
 *
 * <h2>Where the answers are kept</h2>
 *
 * <p>
 * Three stores hold what the two sections above produce: an {@link AgentApprovalStore} for "always allow in this
 * agent", a {@link SessionApprovalStore} for "always allow in this session", and a {@link PendingTurnRegistry}
 * for the turns that suspended. All three default to the node-local in-memory implementations, which is right
 * for a single node and wrong the moment a session can be served by a second one: an approval given on node A
 * is invisible to node B, so the same skill is asked about again, and an {@code /approve} entered on B finds no
 * turn to release because the turn suspended on A.
 *
 * <p>
 * {@link #withAgentApprovalStore(AgentApprovalStore)}, {@link #withSessionApprovalStore(SessionApprovalStore)}
 * and {@link #withPendingTurnRegistry(PendingTurnRegistry)} are what a shared implementation arrives through.
 * None ships — this seam exists so that one an application wrote is reachable at all. Without it the only route
 * to a written implementation was hand-building the object graph, which means giving up the stack's ordered
 * teardown; the same gap that {@code SchedulingSpec.withTaskRepository(...)} closed on the scheduling axis.
 *
 * <p>
 * All three are <b>borrowed</b>: they are {@link ExternallyManaged} and the stack closes none of them. Whoever
 * built the connection underneath closes the thing on top of it. Supplying them also narrows what the stack
 * announces — the {@code distributed-approvals} degradation names only the stores still left node-local, and
 * says nothing when all three were supplied.
 */
public final class SkillApprovalSpec {

    /**
     * The default pending-turn sweep interval — a minute, which trades how promptly an expiry is noticed against
     * how often the reaper's daemon thread wakes up.
     */
    public static final Duration DEFAULT_PENDING_TURN_SWEEP_INTERVAL = Duration.ofMinutes(1);

    /**
     * How the stack builds its {@link SkillApprovalChannel}.
     */
    public enum ChannelMode {
        /** Deny every request that reaches the channel. Fail-closed. */
        DENY_ALL,
        /** Approve requests whose skill names are all in the allow-list; deny the rest. */
        ALLOW_LIST,
        /**
         * No channel at all: an {@code ASK} suspends the turn into the pending-turn registry, where an
         * out-of-band approval can pick it up.
         */
        SUSPEND,
        /** Use the channel instance supplied by the caller. */
        SUPPLIED,
        /** Build the channel from a caller-supplied factory, over the stack's own approval stores. */
        SUPPLIED_FACTORY
    }

    private final SkillInvocationDecision defaultDecision;
    private final ChannelMode channelMode;
    private final Set<String> allowedSkills;
    private final SkillApprovalChannel suppliedChannel;
    private final SkillApprovalChannelFactory channelFactory;
    private final Duration pendingTurnTtl;
    private final Duration pendingTurnSweepInterval;
    private final Consumer<List<PendingTurn>> pendingTurnExpirationListener;
    private final AgentApprovalStore agentApprovalStore;
    private final SessionApprovalStore sessionApprovalStore;
    private final PendingTurnRegistry pendingTurnRegistry;

    private SkillApprovalSpec(Builder builder) {
        this.defaultDecision = Objects.requireNonNull(builder.defaultDecision, "defaultDecision must not be null");
        this.channelMode = Objects.requireNonNull(builder.channelMode, "channelMode must not be null");
        this.allowedSkills = (builder.allowedSkills == null) ? Set.of() : Set.copyOf(builder.allowedSkills);
        this.suppliedChannel = builder.suppliedChannel;
        this.channelFactory = builder.channelFactory;
        this.pendingTurnTtl = builder.pendingTurnTtl;
        this.pendingTurnSweepInterval = (builder.pendingTurnSweepInterval == null)
                ? DEFAULT_PENDING_TURN_SWEEP_INTERVAL
                : builder.pendingTurnSweepInterval;
        this.pendingTurnExpirationListener = builder.pendingTurnExpirationListener;
        this.agentApprovalStore = builder.agentApprovalStore;
        this.sessionApprovalStore = builder.sessionApprovalStore;
        this.pendingTurnRegistry = builder.pendingTurnRegistry;

        if (this.pendingTurnSweepInterval.isNegative() || this.pendingTurnSweepInterval.isZero()) {
            throw new IllegalArgumentException(
                    "pendingTurnSweepInterval must be positive: " + this.pendingTurnSweepInterval);
        }
        if (this.pendingTurnTtl != null && (this.pendingTurnTtl.isNegative() || this.pendingTurnTtl.isZero())) {
            throw new IllegalArgumentException("pendingTurnTtl must be positive: " + this.pendingTurnTtl);
        }

        if (this.channelMode == ChannelMode.SUPPLIED) {
            Objects.requireNonNull(this.suppliedChannel, "A SUPPLIED channel mode needs a channel instance");
        }
        if (this.channelMode == ChannelMode.SUPPLIED_FACTORY) {
            Objects.requireNonNull(this.channelFactory, "A SUPPLIED_FACTORY channel mode needs a factory");
        }
    }

    /**
     * Fail-closed preset: unknown skills are asked about, and every ask is denied.
     *
     * @return the spec
     */
    public static SkillApprovalSpec denyAll() {
        return new Builder().channelMode(ChannelMode.DENY_ALL).build();
    }

    /**
     * Suspends the turn instead of answering: an {@code ASK} parks in the pending-turn registry and waits.
     *
     * <p>
     * The other presets answer the question here and now. This one declines to, on the grounds that whoever can
     * answer it is not in this process — an operator with a console, a reviewer with a ticket. The turn stops
     * before the skill runs and reappears when the approval arrives, which is the only mode in which a headless
     * deployment can say <i>yes</i> to something it did not authorise in advance.
     *
     * <p>
     * Two consequences worth stating before choosing it. Nothing here delivers the question — the registry is a
     * place to look, not a notification; a deployment that never looks has replaced a denial with a hang.
     * And a suspended turn holds memory until it is answered or its
     * {@link #withPendingTurnTtl(Duration) TTL} elapses, so the TTL is what bounds a queue of unanswered
     * questions rather than merely tidying it.
     *
     * @return the spec
     */
    public static SkillApprovalSpec suspend() {
        return new Builder().channelMode(ChannelMode.SUSPEND).build();
    }

    /**
     * Auto-approves the named skills and denies everything else.
     *
     * <p>
     * Names are matched literally. There is no wildcard — an entry of {@code "*"} allows a skill actually
     * called {@code *}, nothing more.
     *
     * @param allowedSkills
     *            the skill names to auto-approve (must not be null; entries must be non-blank)
     * @return the spec
     */
    public static SkillApprovalSpec allowList(Collection<String> allowedSkills) {
        Objects.requireNonNull(allowedSkills, "allowedSkills must not be null");
        return new Builder().channelMode(ChannelMode.ALLOW_LIST).allowedSkills(allowedSkills).build();
    }

    /**
     * Auto-approves the named skills and denies everything else.
     *
     * @param allowedSkills
     *            the skill names to auto-approve
     * @return the spec
     */
    public static SkillApprovalSpec allowList(String... allowedSkills) {
        return allowList(List.of(allowedSkills));
    }

    /**
     * Routes asks through a caller-supplied channel — an interactive prompt, a work-queue, an approval API.
     *
     * <p>
     * The channel must <b>block until it has an answer</b>, and must record that answer in the approval stores
     * it was given. Returning early — having posted a WebSocket notification, say, and intending to record the
     * reply when it arrives — leaves no decision behind, so the policy chain re-evaluates the same skill to
     * {@code ASK} moments later and asks again. The failure mode is not a missed approval but an ask loop.
     * A channel that cannot block should not be a channel: use {@link #suspend()}, which is exactly the shape
     * for an answer that arrives later.
     *
     * @param channel
     *            the channel (must not be null)
     * @return the spec
     */
    public static SkillApprovalSpec channel(SkillApprovalChannel channel) {
        return new Builder().channelMode(ChannelMode.SUPPLIED).suppliedChannel(channel).build();
    }

    /**
     * Routes asks through a channel the stack builds from the caller's factory.
     *
     * <p>
     * Use this rather than {@link #channel(SkillApprovalChannel)} whenever the channel must record its answers
     * — an interactive prompt does, because "always allow in this session" has to be visible to the very next
     * check. See {@link SkillApprovalChannelFactory} for why the inversion is necessary.
     *
     * @param factory
     *            the factory (must not be null)
     * @return the spec
     */
    public static SkillApprovalSpec channelFactory(SkillApprovalChannelFactory factory) {
        return new Builder().channelMode(ChannelMode.SUPPLIED_FACTORY).channelFactory(factory).build();
    }

    /**
     * Returns a copy of this spec with a different rule-based default decision.
     *
     * <p>
     * Setting {@link SkillInvocationDecision#ALLOW} bypasses the channel entirely for unknown skills — every
     * skill on the class path becomes invokable without review. Only appropriate for a trusted, fully
     * controlled skill set.
     *
     * @param decision
     *            the default decision (must not be null)
     * @return a new spec
     */
    public SkillApprovalSpec withDefaultDecision(SkillInvocationDecision decision) {
        return Builder.from(this).defaultDecision(decision).build();
    }

    /**
     * Returns the rule-based policy's default decision.
     *
     * @return the decision, never null
     */
    public SkillInvocationDecision getDefaultDecision() {
        return defaultDecision;
    }

    /**
     * Returns how the channel is built.
     *
     * @return the channel mode, never null
     */
    public ChannelMode getChannelMode() {
        return channelMode;
    }

    /**
     * Returns the allow-list, empty unless {@link ChannelMode#ALLOW_LIST} is in effect.
     *
     * @return an immutable set of skill names
     */
    public Set<String> getAllowedSkills() {
        return allowedSkills;
    }

    /**
     * Returns the caller-supplied channel, when {@link ChannelMode#SUPPLIED} is in effect.
     *
     * @return the channel, or empty
     */
    public Optional<SkillApprovalChannel> getSuppliedChannel() {
        return Optional.ofNullable(suppliedChannel);
    }

    /**
     * Returns the caller-supplied channel factory, when {@link ChannelMode#SUPPLIED_FACTORY} is in effect.
     *
     * @return the factory, or empty
     */
    public Optional<SkillApprovalChannelFactory> getChannelFactory() {
        return Optional.ofNullable(channelFactory);
    }

    /**
     * Returns a copy of this spec with a different pending-turn TTL.
     *
     * <p>
     * How long a suspended turn waits before the reaper drops it. Left unset, the executor's own default of
     * thirty minutes applies — a figure chosen for a terminal, where the person who was asked is the person
     * sitting there. Neither extreme is safe by default elsewhere: too long and unanswered turns accumulate
     * with nothing to release them, too short and an approval that was on its way arrives to find nothing
     * waiting. Set it from who answers, not from how long the skill takes.
     *
     * @param ttl
     *            the TTL (must be positive), or {@code null} to keep the executor default
     * @return a new spec
     */
    public SkillApprovalSpec withPendingTurnTtl(Duration ttl) {
        return Builder.from(this).pendingTurnTtl(ttl).build();
    }

    /**
     * Returns a copy of this spec with a different pending-turn sweep interval.
     *
     * @param interval
     *            the interval (must be positive)
     * @return a new spec
     */
    public SkillApprovalSpec withPendingTurnSweepInterval(Duration interval) {
        return Builder.from(this).pendingTurnSweepInterval(interval).build();
    }

    /**
     * Returns a copy of this spec with a listener notified of each batch of expired pending turns.
     *
     * @param listener
     *            the listener, or {@code null} for none
     * @return a new spec
     */
    public SkillApprovalSpec withPendingTurnExpirationListener(Consumer<List<PendingTurn>> listener) {
        return Builder.from(this).pendingTurnExpirationListener(listener).build();
    }

    /**
     * Returns how long a suspended turn waits before the reaper drops it.
     *
     * <p>
     * Empty means unset, which is not the same as zero: the executor's own default applies. The default is not
     * repeated here so that the two cannot drift apart.
     *
     * @return the TTL, or empty to keep the executor default
     */
    public Optional<Duration> getPendingTurnTtl() {
        return Optional.ofNullable(pendingTurnTtl);
    }

    /**
     * Returns how often the reaper sweeps expired pending turns.
     *
     * @return the interval, never null
     */
    public Duration getPendingTurnSweepInterval() {
        return pendingTurnSweepInterval;
    }

    /**
     * Returns the expired-pending-turn listener.
     *
     * @return the listener, or empty when expiries are silent
     */
    public Optional<Consumer<List<PendingTurn>>> getPendingTurnExpirationListener() {
        return Optional.ofNullable(pendingTurnExpirationListener);
    }

    /**
     * Returns a copy of this spec with the store that answers "always allow in this agent".
     *
     * <p>
     * Reads and writes go through the same instance: the policy chain consults it and the approval channel
     * records into it, so a store supplied here is also the one an {@code a} answer lands in. Decisions kept in
     * it have no TTL and survive {@code /clear} — only {@code /revoke --agent} removes them — which is what makes
     * sharing it across nodes worth doing and also what makes a mistake here durable.
     *
     * @param store
     *            the agent-scoped approval store (must not be null)
     * @return a new spec carrying the store
     */
    public SkillApprovalSpec withAgentApprovalStore(@ExternallyManaged AgentApprovalStore store) {
        Objects.requireNonNull(store, "agentApprovalStore must not be null");
        return Builder.from(this).agentApprovalStore(store).build();
    }

    /**
     * Returns a copy of this spec with the store that answers "always allow in this session".
     *
     * <p>
     * The narrow half of the pair, and the one a distributed deployment feels first: it is consulted before the
     * agent-scoped store, so a session that moves to another node re-asks every skill it had already been
     * allowed unless this store is shared. Its reach is the session <i>and the executions that session
     * delegated</i> — subagent forks carry the id along as {@code invokingSessionId} — so a shared
     * implementation changes the answer for forks too.
     *
     * @param store
     *            the session-scoped approval store (must not be null)
     * @return a new spec carrying the store
     */
    public SkillApprovalSpec withSessionApprovalStore(@ExternallyManaged SessionApprovalStore store) {
        Objects.requireNonNull(store, "sessionApprovalStore must not be null");
        return Builder.from(this).sessionApprovalStore(store).build();
    }

    /**
     * Returns a copy of this spec with the registry suspended turns wait in.
     *
     * <p>
     * A {@code PendingTurn} is a snapshot rather than a live continuation, which is why sharing the registry is
     * enough to make {@code /approve} work from a node other than the one that suspended: the approving node
     * finds the entry, writes the decision into the approval stores above, and removes it. Sharing this one
     * without sharing those is the half-configured shape — the turn is found and released into a node that
     * still has no record of the approval.
     *
     * @param registry
     *            the pending-turn registry (must not be null)
     * @return a new spec carrying the registry
     */
    public SkillApprovalSpec withPendingTurnRegistry(@ExternallyManaged PendingTurnRegistry registry) {
        Objects.requireNonNull(registry, "pendingTurnRegistry must not be null");
        return Builder.from(this).pendingTurnRegistry(registry).build();
    }

    /**
     * Returns the caller-supplied agent-scoped approval store, when there is one.
     *
     * @return the store, or empty to use the stack default (in-memory, this node only)
     */
    public Optional<AgentApprovalStore> getAgentApprovalStore() {
        return Optional.ofNullable(agentApprovalStore);
    }

    /**
     * Returns the caller-supplied session-scoped approval store, when there is one.
     *
     * @return the store, or empty to use the stack default (in-memory, this node only)
     */
    public Optional<SessionApprovalStore> getSessionApprovalStore() {
        return Optional.ofNullable(sessionApprovalStore);
    }

    /**
     * Returns the caller-supplied pending-turn registry, when there is one.
     *
     * @return the registry, or empty to use the stack default (in-memory, this node only)
     */
    public Optional<PendingTurnRegistry> getPendingTurnRegistry() {
        return Optional.ofNullable(pendingTurnRegistry);
    }

    @Override
    public String toString() {
        return "SkillApprovalSpec[default=" + defaultDecision + ", channel=" + channelMode
                + (channelMode == ChannelMode.ALLOW_LIST ? ", allowed=" + allowedSkills : "")
                + (agentApprovalStore != null ? ", custom agent approval store" : "")
                + (sessionApprovalStore != null ? ", custom session approval store" : "")
                + (pendingTurnRegistry != null ? ", custom pending turn registry" : "") + "]";
    }

    /**
     * Assembles the fields for the enclosing class, and is deliberately not part of its public surface.
     *
     * <p>
     * Callers choose a spec by naming a mode — {@link #denyAll()}, {@link #suspend()} — because the fields are
     * not independent: a {@link ChannelMode#SUPPLIED} without a channel, or an allow-list under
     * {@link ChannelMode#DENY_ALL}, are combinations the presets exist to prevent. Exposing a builder would
     * hand those back. What it is for internally is that the same eight fields are otherwise re-listed
     * positionally at every preset and every {@code with*} copy, where an omitted argument is five
     * indistinguishable nulls from its neighbour.
     */
    private static final class Builder {

        private SkillInvocationDecision defaultDecision = SkillInvocationDecision.ASK;
        private ChannelMode channelMode;
        private Collection<String> allowedSkills = Set.of();
        private SkillApprovalChannel suppliedChannel;
        private SkillApprovalChannelFactory channelFactory;
        private Duration pendingTurnTtl;
        private Duration pendingTurnSweepInterval;
        private Consumer<List<PendingTurn>> pendingTurnExpirationListener;
        private AgentApprovalStore agentApprovalStore;
        private SessionApprovalStore sessionApprovalStore;
        private PendingTurnRegistry pendingTurnRegistry;

        private static Builder from(SkillApprovalSpec spec) {
            final Builder builder = new Builder();
            builder.defaultDecision = spec.defaultDecision;
            builder.channelMode = spec.channelMode;
            builder.allowedSkills = spec.allowedSkills;
            builder.suppliedChannel = spec.suppliedChannel;
            builder.channelFactory = spec.channelFactory;
            builder.pendingTurnTtl = spec.pendingTurnTtl;
            builder.pendingTurnSweepInterval = spec.pendingTurnSweepInterval;
            builder.pendingTurnExpirationListener = spec.pendingTurnExpirationListener;
            builder.agentApprovalStore = spec.agentApprovalStore;
            builder.sessionApprovalStore = spec.sessionApprovalStore;
            builder.pendingTurnRegistry = spec.pendingTurnRegistry;
            return builder;
        }

        private Builder defaultDecision(SkillInvocationDecision decision) {
            this.defaultDecision = decision;
            return this;
        }

        private Builder channelMode(ChannelMode mode) {
            this.channelMode = mode;
            return this;
        }

        private Builder allowedSkills(Collection<String> skills) {
            this.allowedSkills = skills;
            return this;
        }

        private Builder suppliedChannel(SkillApprovalChannel channel) {
            this.suppliedChannel = channel;
            return this;
        }

        private Builder channelFactory(SkillApprovalChannelFactory factory) {
            this.channelFactory = factory;
            return this;
        }

        private Builder pendingTurnTtl(Duration ttl) {
            this.pendingTurnTtl = ttl;
            return this;
        }

        private Builder pendingTurnSweepInterval(Duration interval) {
            this.pendingTurnSweepInterval = interval;
            return this;
        }

        private Builder pendingTurnExpirationListener(Consumer<List<PendingTurn>> listener) {
            this.pendingTurnExpirationListener = listener;
            return this;
        }

        private Builder agentApprovalStore(AgentApprovalStore store) {
            this.agentApprovalStore = store;
            return this;
        }

        private Builder sessionApprovalStore(SessionApprovalStore store) {
            this.sessionApprovalStore = store;
            return this;
        }

        private Builder pendingTurnRegistry(PendingTurnRegistry registry) {
            this.pendingTurnRegistry = registry;
            return this;
        }

        private SkillApprovalSpec build() {
            return new SkillApprovalSpec(this);
        }
    }
}
