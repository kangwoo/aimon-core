package at.aimon.spring.boot.autoconfigure;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import at.aimon.bootstrap.spec.SessionSpec;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
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
 * they do have to be closed — by Spring, as the beans they are. Resolving them inside this {@code @Bean} method
 * is what orders that: {@code getIfAvailable()} registers a dependent-bean edge, so Spring destroys each bus
 * <em>after</em> the {@code SessionSpec}, and therefore after the stack built from it has drained. Adding them to
 * the stack's own teardown plan as well would give one resource two destruction edges, which is the thing
 * {@code scope-model.md} exists to prevent.
 */
@AutoConfiguration
@ConditionalOnProperty(name = AimonProperties.ENABLED, havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AimonProperties.class)
public class AimonSessionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SessionSpec.class)
    SessionSpec aimonSessionSpec(AimonProperties properties, ObjectProvider<SessionRecordStore> recordStores,
            ObjectProvider<SessionLeaseStore> leaseStores, ObjectProvider<SessionSignalBus> signalBuses,
            ObjectProvider<SessionInbox> inboxes, ObjectProvider<IdempotencyStore> idempotencyStores) {
        final AimonProperties.SessionProperties session = properties.getSession();
        final SessionSpec.Builder builder = SessionSpec.builder().drainTimeout(session.getShutdownDrainTimeout())
                .idleTtl(session.getCache().getIdleTtl()).maxCachedSessions(session.getCache().getMaxEntries())
                .mode(session.getMode()).nodeId(session.getNodeId());

        final SessionRecordStore supplied = recordStores.getIfAvailable();
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
        final SessionLeaseStore leaseStore = leaseStores.getIfAvailable();
        final SessionSignalBus signalBus = signalBuses.getIfAvailable();
        final SessionInbox inbox = inboxes.getIfAvailable();
        final IdempotencyStore idempotencyStore = idempotencyStores.getIfAvailable();
        builder.leaseStore(leaseStore).signalBus(signalBus).inbox(inbox).idempotencyStore(idempotencyStore);

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
