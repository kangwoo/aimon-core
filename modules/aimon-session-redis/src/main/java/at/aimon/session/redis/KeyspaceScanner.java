package at.aimon.session.redis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;

/**
 * One step of a {@code SCAN} over a whole keyspace, behind an opaque string cursor that survives between calls.
 *
 * <p>
 * Standalone Redis has one keyspace and its own cursor is enough. A Redis Cluster splits the keyspace over its masters
 * and {@code SCAN} only walks the node it is sent to, so {@link #cluster} walks the masters one after another, in node
 * id order, and folds "which node, and where on it" into the cursor as {@code <nodeId>:<nodeCursor>}. Lettuce's own
 * cluster-wide scan keeps that position in a cursor <em>object</em>, which cannot be handed back as a string between
 * two calls of {@code scanSessions}.
 */
interface KeyspaceScanner {

    /**
     * @param cursor
     *            the cursor a previous step returned, or null to start
     * @param args
     *            the {@code MATCH} / {@code COUNT} arguments (never null)
     * @return the keys of this step and where to continue (never null)
     */
    Step next(String cursor, ScanArgs args);

    /**
     * A scanner over the single keyspace {@code commands} is connected to.
     *
     * @param commands
     *            the connection's commands
     * @return a scanner (never null)
     */
    static KeyspaceScanner standalone(RedisClusterCommands<String, String> commands) {
        return (cursor, args) -> {
            final KeyScanCursor<String> page = commands.scan(ScanCursor.of(cursor == null ? "0" : cursor), args);
            return new Step(page.getKeys(), page.isFinished() ? null : page.getCursor());
        };
    }

    /**
     * A scanner over every master of the cluster {@code connection} is connected to.
     *
     * @param connection
     *            the cluster connection (must not be null)
     * @return a scanner (never null)
     */
    static KeyspaceScanner cluster(StatefulRedisClusterConnection<String, String> connection) {
        Objects.requireNonNull(connection, "connection must not be null");
        return new PerNode(() -> {
            final List<String> masters = new ArrayList<>();
            for (RedisClusterNode node : connection.getPartitions()) {
                if (node.is(RedisClusterNode.NodeFlag.UPSTREAM)) {
                    masters.add(node.getNodeId());
                }
            }
            return masters;
        }, (nodeId, cursor, args) -> connection.getConnection(nodeId).sync().scan(cursor, args));
    }

    /**
     * The keys of one step and the cursor to continue from.
     */
    final class Step {

        private final List<String> keys;
        private final String nextCursor;

        Step(List<String> keys, String nextCursor) {
            this.keys = List.copyOf(Objects.requireNonNull(keys, "keys must not be null"));
            this.nextCursor = nextCursor;
        }

        List<String> getKeys() {
            return keys;
        }

        /** @return the cursor to continue from, or null when the whole keyspace has been walked */
        String getNextCursor() {
            return nextCursor;
        }
    }

    /** {@code SCAN} against one named node. */
    @FunctionalInterface
    interface NodeScan {
        KeyScanCursor<String> scan(String nodeId, ScanCursor cursor, ScanArgs args);
    }

    /**
     * Walks the masters in node id order. Package-private so the per-node iteration is testable without a cluster.
     */
    final class PerNode implements KeyspaceScanner {

        private static final Logger log = LoggerFactory.getLogger(PerNode.class);
        private static final char SEPARATOR = ':';

        private final Supplier<List<String>> masters;
        private final NodeScan nodeScan;

        PerNode(Supplier<List<String>> masters, NodeScan nodeScan) {
            this.masters = Objects.requireNonNull(masters, "masters must not be null");
            this.nodeScan = Objects.requireNonNull(nodeScan, "nodeScan must not be null");
        }

        @Override
        public Step next(String cursor, ScanArgs args) {
            final List<String> nodes = new ArrayList<>(masters.get());
            nodes.sort(Comparator.naturalOrder());
            if (nodes.isEmpty()) {
                return new Step(List.of(), null);
            }
            String node;
            String nodeCursor;
            if (cursor == null) {
                node = nodes.get(0);
                nodeCursor = "0";
            } else {
                final int split = cursor.indexOf(SEPARATOR);
                if (split <= 0) {
                    throw new IllegalArgumentException("Not a cluster scan cursor: " + cursor);
                }
                node = cursor.substring(0, split);
                nodeCursor = cursor.substring(split + 1);
                if (!nodes.contains(node)) {
                    // The node left the cluster between two steps. Its slots went to other masters, some of which may
                    // already be behind us, so this pass can miss keys — the same caveat SCAN has for any topology
                    // change. Move on to the next node in order; the next pass sees the new layout from the start.
                    log.warn("Cluster node {} left during a keyspace scan; continuing with the next master", node);
                    node = firstAfter(nodes, node);
                    if (node == null) {
                        return new Step(List.of(), null);
                    }
                    nodeCursor = "0";
                }
            }
            final KeyScanCursor<String> page = nodeScan.scan(node, ScanCursor.of(nodeCursor), args);
            if (!page.isFinished()) {
                return new Step(page.getKeys(), node + SEPARATOR + page.getCursor());
            }
            final String following = firstAfter(nodes, node);
            return new Step(page.getKeys(), following == null ? null : following + SEPARATOR + "0");
        }

        private static String firstAfter(List<String> sortedNodes, String node) {
            for (String candidate : sortedNodes) {
                if (candidate.compareTo(node) > 0) {
                    return candidate;
                }
            }
            return null;
        }
    }
}
