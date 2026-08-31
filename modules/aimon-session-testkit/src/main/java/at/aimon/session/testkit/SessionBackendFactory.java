package at.aimon.session.testkit;

import java.util.function.Consumer;

import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.store.SessionLeaseStore;

/**
 * Everything {@link AbstractMultiNodeSessionContractTest} needs to know about a session backend: how to wipe it, how
 * to build a node on it, and how to build a single SPI bound to a connection nothing else owns.
 *
 * <p>
 * Every method takes a {@code Consumer<AutoCloseable>} sink rather than returning something closeable. Connections,
 * pools and clients differ per backend and outnumber the SPIs built from them — one Postgres node needs two Hikari
 * pools, one Redis node three connections — so the caller registers what it opened and the harness or the test closes
 * the lot at teardown. Nothing in the suite has to know how many there were.
 *
 * <p>
 * The last three methods exist because three scenarios deliberately go around the router to reach the SPI directly:
 * lease fail-over is a statement about lock semantics, and the inbox and idempotency cases have to observe shared
 * state through a connection that is not one of the two nodes'. They are separate methods rather than one more
 * {@link #createNode} because a node also starts a signal bus — watchers, listener threads — and those cases want
 * none of it.
 */
public interface SessionBackendFactory {

    /**
     * Clears every trace of a previous test from the shared container: keys, rows, collections. Called once per
     * harness, before either node is built.
     */
    void reset();

    /**
     * Builds one node's four SPIs on connections of its own, so cross-node traffic genuinely crosses the wire instead
     * of short-circuiting in process.
     *
     * @param nodeId
     *            the node's id, which some backends put on the wire to route signals back to a sender
     * @param ownedResources
     *            sink for anything that must be closed at teardown
     * @return the four SPIs for this node
     */
    SessionBackend createNode(String nodeId, Consumer<AutoCloseable> ownedResources);

    /**
     * @param ownedResources
     *            sink for anything that must be closed at teardown
     * @return a lease store on a connection of its own, belonging to no node
     */
    SessionLeaseStore createLeaseStore(Consumer<AutoCloseable> ownedResources);

    /**
     * @param ownedResources
     *            sink for anything that must be closed at teardown
     * @return an inbox view on a connection of its own, belonging to no node
     */
    SessionInbox createInbox(Consumer<AutoCloseable> ownedResources);

    /**
     * @param ownedResources
     *            sink for anything that must be closed at teardown
     * @return an idempotency store on a connection of its own, belonging to no node
     */
    IdempotencyStore createIdempotencyStore(Consumer<AutoCloseable> ownedResources);
}
