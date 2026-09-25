package at.aimon.session.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.store.SegmentScanPage;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;

/**
 * The per-node walk behind a cluster scan, driven by fake nodes: each "node" hands out its keys two at a time, with its
 * own cursor, the way {@code SCAN} does.
 */
class KeyspaceScannerTest {

    private final Map<String, List<String>> nodes = new LinkedHashMap<>();
    private final List<String> calls = new ArrayList<>();

    private KeyspaceScanner.PerNode scanner(AtomicReference<List<String>> masters) {
        return new KeyspaceScanner.PerNode(masters::get, (nodeId, cursor, args) -> {
            calls.add(nodeId + "@" + cursor.getCursor());
            final List<String> keys = nodes.get(nodeId);
            final int from = Integer.parseInt(cursor.getCursor());
            final int to = Math.min(keys.size(), from + 2);
            final KeyScanCursor<String> page = new KeyScanCursor<>();
            page.getKeys().addAll(keys.subList(from, to));
            page.setCursor(to >= keys.size() ? "0" : Integer.toString(to));
            page.setFinished(to >= keys.size());
            return page;
        });
    }

    private static Set<String> drain(KeyspaceScanner scanner) {
        final Set<String> seen = new HashSet<>();
        String cursor = null;
        int steps = 0;
        do {
            final KeyspaceScanner.Step step = scanner.next(cursor, ScanArgs.Builder.limit(2));
            seen.addAll(step.getKeys());
            cursor = step.getNextCursor();
            assertThat(++steps).as("the walk terminates").isLessThan(100);
        } while (cursor != null);
        return seen;
    }

    @Test
    @DisplayName("every master is walked, in node id order, each from its own cursor 0")
    void walksEveryMaster() {
        nodes.put("node-b", List.of("b1", "b2", "b3"));
        nodes.put("node-a", List.of("a1"));
        nodes.put("node-c", List.of("c1", "c2"));

        final Set<String> seen = drain(scanner(new AtomicReference<>(List.of("node-c", "node-a", "node-b"))));

        assertThat(seen).containsExactlyInAnyOrder("a1", "b1", "b2", "b3", "c1", "c2");
        assertThat(calls).containsExactly("node-a@0", "node-b@0", "node-b@2", "node-c@0");
    }

    @Test
    @DisplayName("the cursor names the node and its position, and ends only after the last node")
    void cursorCarriesTheNode() {
        nodes.put("node-a", List.of("a1", "a2", "a3"));
        nodes.put("node-b", List.of("b1"));
        final KeyspaceScanner.PerNode scanner = scanner(new AtomicReference<>(List.of("node-a", "node-b")));

        final KeyspaceScanner.Step first = scanner.next(null, ScanArgs.Builder.limit(2));
        assertThat(first.getNextCursor()).isEqualTo("node-a:2");
        final KeyspaceScanner.Step second = scanner.next(first.getNextCursor(), ScanArgs.Builder.limit(2));
        assertThat(second.getKeys()).containsExactly("a3");
        assertThat(second.getNextCursor()).as("node a done, node b next").isEqualTo("node-b:0");
        final KeyspaceScanner.Step third = scanner.next(second.getNextCursor(), ScanArgs.Builder.limit(2));
        assertThat(third.getKeys()).containsExactly("b1");
        assertThat(third.getNextCursor()).isNull();
    }

    @Test
    @DisplayName("an empty master does not end the walk early")
    void emptyMasterInTheMiddle() {
        nodes.put("node-a", List.of("a1"));
        nodes.put("node-b", List.of());
        nodes.put("node-c", List.of("c1"));

        assertThat(drain(scanner(new AtomicReference<>(List.of("node-a", "node-b", "node-c")))))
                .containsExactlyInAnyOrder("a1", "c1");
    }

    @Test
    @DisplayName("a node that left between two steps is skipped, and the walk goes on with the next one")
    void nodeLeftMidWalk() {
        nodes.put("node-a", List.of("a1", "a2", "a3"));
        nodes.put("node-b", List.of("b1"));
        nodes.put("node-c", List.of("c1"));
        final AtomicReference<List<String>> masters = new AtomicReference<>(List.of("node-a", "node-b", "node-c"));
        final KeyspaceScanner.PerNode scanner = scanner(masters);

        final KeyspaceScanner.Step first = scanner.next(null, ScanArgs.Builder.limit(2));
        assertThat(first.getNextCursor()).isEqualTo("node-a:2");
        masters.set(List.of("node-b", "node-c"));

        final KeyspaceScanner.Step next = scanner.next(first.getNextCursor(), ScanArgs.Builder.limit(2));
        assertThat(next.getKeys()).containsExactly("b1");
        assertThat(calls).last().isEqualTo("node-b@0");
    }

    @Test
    @DisplayName("no masters means an empty, finished walk; a malformed cursor is refused")
    void edges() {
        assertThat(drain(scanner(new AtomicReference<>(List.of())))).isEmpty();
        nodes.put("node-a", List.of("a1"));
        final KeyspaceScanner.PerNode scanner = scanner(new AtomicReference<>(List.of("node-a")));
        assertThatThrownBy(() -> scanner.next("123", ScanArgs.Builder.limit(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the store reports sessions from every node of the walk")
    void storeScansThroughTheScanner() {
        final String prefix = RedisSessionLogSegmentStore.DEFAULT_KEY_PREFIX;
        nodes.put("node-a", List.of(prefix + ":{s:one}:created", prefix + ":{s:one}:data"));
        nodes.put("node-b", List.of(prefix + ":{s:two}:created"));
        final KeyspaceScanner scanner = new KeyspaceScanner.PerNode(() -> List.of("node-a", "node-b"),
                (nodeId, cursor, args) -> {
                    final KeyScanCursor<String> page = new KeyScanCursor<>();
                    // The fake ignores MATCH; the store keeps only the :created keys itself.
                    page.getKeys().addAll(nodes.get(nodeId));
                    page.setCursor("0");
                    page.setFinished(true);
                    return page;
                });
        @SuppressWarnings("unchecked")
        final RedisClusterCommands<String, String> commands = mock(RedisClusterCommands.class);
        final RedisSessionLogSegmentStore store = new RedisSessionLogSegmentStore(commands, scanner, prefix);

        final Set<String> sessions = new HashSet<>();
        String cursor = null;
        do {
            final SegmentScanPage page = store.scanSessions(Instant.now(), cursor, 10);
            page.getSessionIds().forEach(id -> sessions.add(id.value()));
            cursor = page.getNextCursor().orElse(null);
        } while (cursor != null);

        assertThat(sessions).containsExactlyInAnyOrder("one", "two");
    }
}
