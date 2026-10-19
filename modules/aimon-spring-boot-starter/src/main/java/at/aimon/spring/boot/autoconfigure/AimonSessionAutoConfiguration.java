package at.aimon.spring.boot.autoconfigure;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import at.aimon.bootstrap.spec.SessionSpec;
import at.aimon.core.agent.session.SessionLifecycleListener;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.InMemorySessionLogSegmentStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.session.store.SessionLogSegmentStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.session.routing.DeploymentMode;

/**
 * Selects where session state lives, how the node-local cache is bounded, and — in distributed mode — which
 * beans make a session reachable from a second node.
 *
 * <p>
 * <b>The in-memory branch publishes no store at all.</b> It would be one line to hand the spec an explicit
 * {@code InMemorySessionRecordStore}, and it would be wrong: the stack registers a {@code session-durability}
 * degradation precisely when no store was supplied, and supplying one silently satisfies that check. The
 * result would be a server that loses every transcript on restart without ever having said so. Leaving the
 * spec's store unset makes the stack fall back to the same in-memory implementation <em>and</em> log why —
 * which is the entire content of the "in-memory default, but noisily" rule.
 *
 * <p>
 * Any other value of {@code aimon.session.store} names infrastructure this starter deliberately does not
 * construct. Postgres, MongoDB and Redis stores each need a client, a schema or a connection factory that the
 * application already owns and configures through its own Boot starter; guessing at that wiring here would
 * produce a second, differently-configured connection pool. So the property selects, the application supplies
 * the bean, and a mismatch between the two fails at startup rather than degrading to memory in silence.
 *
 * <p>
 * <b>{@code aimon.session.mode=distributed} follows the same rule, four beans wider.</b> The router needs a
 * {@link SessionLeaseStore}, a {@link SessionSignalBus}, a {@link SessionInbox} and an {@link IdempotencyStore},
 * and each of the three backing modules ships all four over the same connection the application already opened.
 * What this slice adds over letting the router refuse them one at a time is the message:
 * {@link #describeMissing} names every absent bean at once, with the module that provides it, because a
 * deployment turning distributed mode on for the first time is usually missing all four rather than one.
 *
 * <p>
 * <b>Nothing here is closed by AIMON.</b> The four SPIs and the record store are resolved from the application's
 * context and handed to the spec as borrowed references. Two of the three shipped signal buses own a thread, so
 * they do have to be closed — by Spring, as the beans they are, <em>after</em> the {@code SessionSpec} and
 * therefore after the stack built from it has drained. Resolving them inside this {@code @Bean} method is not
 * what orders that, which is what this paragraph used to say: {@code getIfAvailable()} hands over an instance
 * without telling the factory who asked. {@link ApplicationBeans#resolve} registers the edge that does order it —
 * see that class for why the reverse creation order it would otherwise fall back to is right only by accident.
 * Adding them to the stack's own teardown plan as well would give one resource two destruction edges, which is
 * the thing {@code scope-model.md} exists to prevent.
 *
 * <p>
 * <b>A version-2 log over an application record store needs the segment store too.</b> Version 2 never rewrites the
 * log in place, so without somewhere to seal the ranges a compaction hides, a durable record carries every entry of
 * the session for good and each save rewrites all of it. The stack would only record that as a degradation; this
 * slice fails instead, for the same reason it fails on a missing record store — a deployment that grows without bound
 * is found in production rather than at startup. The fix is a bean from the module that already supplies the record
 * store, or pinning {@code v1}. An application {@code InMemorySessionRecordStore} is the exception: it gets an
 * in-memory segment store, the same pairing the stack makes for its own default. An application that publishes its
 * own {@code SessionSpec} bypasses this slice, and the stack records the {@code session-log-sealing} degradation
 * instead.
 */
@AutoConfiguration
@ConditionalOnProperty(name = AimonProperties.ENABLED, havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AimonProperties.class)
public class AimonSessionAutoConfiguration {

    /**
     * Name of the bean this slice publishes, which the six SPIs below are made dependencies of.
     *
     * <p>
     * A constant so the {@code @Bean} and the edges registered against it cannot drift apart.
     */
    static final String SESSION_SPEC_BEAN = "aimonSessionSpec";

    /**
     * Gathers the six session SPIs, each of which the application publishes or does not.
     *
     * <p>
     * The sixth, {@link SessionLogSegmentStore}, is where a version-2 log seals the ranges its view hides
     * (session-log §5). It is required beside an application record store when the write format is {@code v2}, and
     * optional otherwise: an in-memory record store, supplied or defaulted, is paired with an in-memory one, and a
     * {@code v1} log compacts in place.
     *
     * <p>
     * All six go through {@link ApplicationBeans#resolve}, which registers the destruction edge an
     * {@code ObjectProvider} does not. This slice is where that matters most concretely: a
     * {@link SessionRecordStore} under {@code store=postgres} is a connection, and the stack writes transcripts
     * into it throughout its own ordered teardown.
     *
     * @param properties
     *            the bound configuration
     * @param recordStores
     *            the record store, when the application published one
     * @param leaseStores
     *            the lease store, when the application published one
     * @param signalBuses
     *            the signal bus, when the application published one
     * @param inboxes
     *            the inbox, when the application published one
     * @param idempotencyStores
     *            the idempotency store, when the application published one
     * @param segmentStores
     *            the log segment store, when the application published one
     * @param lifecycleListeners
     *            every {@link SessionLifecycleListener} bean, in {@code @Order} order — each told when the router
     *            deletes a session; resolved through {@link ApplicationBeans#resolveAll} so a listener holding a
     *            connection outlives the stack that calls it
     * @param beanFactory
     *            the context's bean factory, used to register the destruction edges
     * @return the spec
     */
    @Bean(SESSION_SPEC_BEAN)
    @ConditionalOnMissingBean(SessionSpec.class)
    @SuppressWarnings("checkstyle:ParameterNumber")
    SessionSpec aimonSessionSpec(AimonProperties properties, ObjectProvider<SessionRecordStore> recordStores,
            ObjectProvider<SessionLeaseStore> leaseStores, ObjectProvider<SessionSignalBus> signalBuses,
            ObjectProvider<SessionInbox> inboxes, ObjectProvider<IdempotencyStore> idempotencyStores,
            ObjectProvider<SessionLogSegmentStore> segmentStores,
            ObjectProvider<SessionLifecycleListener> lifecycleListeners, ConfigurableListableBeanFactory beanFactory) {
        final AimonProperties.SessionProperties session = properties.getSession();
        final SessionSpec.Builder builder = SessionSpec.builder().drainTimeout(session.getShutdownDrainTimeout())
                .idleTtl(session.getCache().getIdleTtl()).maxCachedSessions(session.getCache().getMaxEntries())
                .mode(session.getMode()).nodeId(session.getNodeId()).logWriteFormat(session.getLogWriteFormat())
                .segmentSweepInterval(session.getSegmentSweepInterval())
                .segmentSweepGrace(session.getSegmentSweepGrace());

        final SessionRecordStore supplied = ApplicationBeans.resolve(recordStores, SessionRecordStore.class,
                beanFactory, SESSION_SPEC_BEAN);
        if (supplied != null) {
            builder.recordStore(supplied);
        } else if (session.getStore() != SessionStoreType.IN_MEMORY) {
            throw new IllegalStateException(
                    AimonProperties.SESSION_STORE + "=" + AimonProperties.asPropertyValue(session.getStore())
                            + " but no SessionRecordStore bean is defined. Add the matching module"
                            + " (aimon-session-postgres / aimon-session-mongodb / aimon-session-redis) and expose its"
                            + " store as a bean — this starter selects a store, it does not build one. To run without"
                            + " durable sessions, set " + AimonProperties.SESSION_STORE + "=in-memory.");
        }

        // Resolved in every mode, not only DISTRIBUTED: an application that publishes a durable lease store has
        // asked for its leases to be visible outside this process, and dropping the bean because the mode happens
        // to be single-node would answer a question it did not ask. The mode decides what may be defaulted.
        final SessionLeaseStore leaseStore = ApplicationBeans.resolve(leaseStores, SessionLeaseStore.class, beanFactory,
                SESSION_SPEC_BEAN);
        final SessionSignalBus signalBus = ApplicationBeans.resolve(signalBuses, SessionSignalBus.class, beanFactory,
                SESSION_SPEC_BEAN);
        final SessionInbox inbox = ApplicationBeans.resolve(inboxes, SessionInbox.class, beanFactory,
                SESSION_SPEC_BEAN);
        final IdempotencyStore idempotencyStore = ApplicationBeans.resolve(idempotencyStores, IdempotencyStore.class,
                beanFactory, SESSION_SPEC_BEAN);
        builder.leaseStore(leaseStore).signalBus(signalBus).inbox(inbox).idempotencyStore(idempotencyStore);
        SessionLogSegmentStore segmentStore = ApplicationBeans.resolve(segmentStores, SessionLogSegmentStore.class,
                beanFactory, SESSION_SPEC_BEAN);
        if (segmentStore == null && supplied instanceof InMemorySessionRecordStore) {
            // The pairing the stack makes for its own in-memory default: both die with the process, so no manifest
            // can outlive the segments it names, and there is no connection here to build.
            segmentStore = new InMemorySessionLogSegmentStore();
        }
        builder.segmentStore(segmentStore);
        builder.lifecycleListeners(ApplicationBeans.resolveAll(lifecycleListeners, SessionLifecycleListener.class,
                beanFactory, SESSION_SPEC_BEAN));

        if (session.getMode() == DeploymentMode.DISTRIBUTED) {
            final List<String> missing = describeMissing(leaseStore, signalBus, inbox, idempotencyStore);
            if (!missing.isEmpty()) {
                throw new IllegalStateException(AimonProperties.SESSION_MODE + "=distributed but " + missing.size()
                        + " of the 4 beans it needs " + (missing.size() == 1 ? "is" : "are") + " not defined:\n  - "
                        + String.join("\n  - ", missing)
                        + "\nEach backing module ships all four over the connection the application already opened"
                        + " — expose them as beans, or set " + AimonProperties.SESSION_MODE + "=single-node.");
            }
        }
        // After the distributed check, which names more of what is missing in one message, and before build(), whose
        // segment-sweep check would otherwise answer the same missing bean with "or drop the sweep". An in-memory
        // record store never reaches it — it was paired above — so build()'s refusal of one in distributed mode
        // still comes first.
        if (supplied != null && segmentStore == null && session.getLogWriteFormat() == SessionLogFormat.V2) {
            throw new IllegalStateException("A SessionRecordStore bean is defined but no SessionLogSegmentStore bean,"
                    + " and " + AimonProperties.SESSION_LOG_WRITE_FORMAT + "=v2 never rewrites the log in place: every"
                    + " record would keep its whole history and each save would rewrite it. Expose the segment store"
                    + " of the module that supplies the record store (PostgresSessionLogSegmentStore /"
                    + " MongoSessionLogSegmentStore / RedisSessionLogSegmentStore) as a bean, or set "
                    + AimonProperties.SESSION_LOG_WRITE_FORMAT + "=v1.");
        }
        return builder.build();
    }

    /**
     * Names the absent session SPIs, with the type to declare and where an implementation comes from.
     *
     * <p>
     * The module hint is repeated per entry on purpose. A reader missing one bean should not have to work out
     * which of three modules to look in, and a reader missing all four reads the same hint four times — a cheap
     * price for the first reader.
     *
     * @param leaseStore
     *            the resolved lease store, or null
     * @param signalBus
     *            the resolved signal bus, or null
     * @param inbox
     *            the resolved inbox, or null
     * @param idempotencyStore
     *            the resolved idempotency store, or null
     * @return one line per missing bean, in the order the router needs them
     */
    private static List<String> describeMissing(SessionLeaseStore leaseStore, SessionSignalBus signalBus,
            SessionInbox inbox, IdempotencyStore idempotencyStore) {
        final List<String> missing = new ArrayList<>();
        if (leaseStore == null) {
            missing.add(SessionLeaseStore.class.getName() + " — elects the one node that holds a session"
                    + " (Mongo/Postgres/RedisSessionLeaseStore)");
        }
        if (signalBus == null) {
            missing.add(SessionSignalBus.class.getName() + " — wakes and evicts sessions held on another node"
                    + " (Mongo/PostgresSessionSignalBus, RedisPubSubSignalBus)");
        }
        if (inbox == null) {
            missing.add(SessionInbox.class.getName() + " — queues a turn for the node that holds the session"
                    + " (Mongo/Postgres/RedisSessionInbox)");
        }
        if (idempotencyStore == null) {
            missing.add(IdempotencyStore.class.getName() + " — collapses a retry that lands on another node"
                    + " (Mongo/Postgres/RedisIdempotencyStore)");
        }
        return missing;
    }
}
