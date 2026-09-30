package at.aimon.session.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.store.SegmentScanPage;
import at.aimon.core.agent.session.store.SessionLogSegment;
import at.aimon.core.agent.session.store.SessionLogSegmentStore;
import at.aimon.session.testkit.AbstractSessionLogSegmentStoreContractTest;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import io.lettuce.core.internal.HostAndPort;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DnsResolvers;
import io.lettuce.core.resource.MappingSocketAddressResolver;

/**
 * The segment store over a real two-master Redis Cluster: the whole contract suite, plus a scan that must report
 * sessions whose keys live on either master.
 *
 * <p>
 * The cluster is two cluster-enabled {@code redis:7-alpine} containers on one Docker network, introduced with
 * {@code CLUSTER MEET} and given half the slots each. They announce their network addresses, which the host cannot
 * reach, so the client maps each announced address to the container's published port.
 */
@DisplayName("RedisSessionLogSegmentStore over a Redis Cluster")
@Tag("docker")
class RedisClusterSessionLogSegmentStoreIntegrationTest extends AbstractSessionLogSegmentStoreContractTest {

    private static final int PORT = 6379;

    private static Network network;
    private static GenericContainer<?> first;
    private static GenericContainer<?> second;
    private static ClientResources resources;
    private static RedisClusterClient client;

    private StatefulRedisClusterConnection<String, String> connection;
    private SessionLogSegmentStore store;

    @BeforeAll
    static void startCluster() throws Exception {
        network = Network.newNetwork();
        first = node();
        second = node();
        first.start();
        second.start();

        final Map<String, Integer> published = new HashMap<>();
        published.put(ip(first), first.getMappedPort(PORT));
        published.put(ip(second), second.getMappedPort(PORT));

        cli(first, "cluster", "meet", ip(second), Integer.toString(PORT));
        cli(first, "cluster", "addslotsrange", "0", "8191");
        cli(second, "cluster", "addslotsrange", "8192", "16383");
        awaitClusterOk(first);
        awaitClusterOk(second);

        final String host = first.getHost();
        resources = ClientResources.builder()
                .socketAddressResolver(MappingSocketAddressResolver.create(DnsResolvers.UNRESOLVED, address -> {
                    final Integer port = published.get(address.getHostText());
                    return port == null ? address : HostAndPort.of(host, port);
                })).build();
        client = RedisClusterClient.create(resources, RedisURI.create(host, first.getMappedPort(PORT)));
    }

    private static GenericContainer<?> node() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withNetwork(network)
                .withExposedPorts(PORT).withCommand("redis-server", "--port", Integer.toString(PORT),
                        "--cluster-enabled", "yes", "--cluster-node-timeout", "5000", "--appendonly", "no");
    }

    private static String ip(GenericContainer<?> container) {
        return container.getContainerInfo().getNetworkSettings().getNetworks().values().iterator().next()
                .getIpAddress();
    }

    private static String cli(GenericContainer<?> container, String... args) throws Exception {
        final String[] command = new String[args.length + 1];
        command[0] = "redis-cli";
        System.arraycopy(args, 0, command, 1, args.length);
        final Container.ExecResult result = container.execInContainer(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("redis-cli " + String.join(" ", args) + " failed: " + result);
        }
        return result.getStdout();
    }

    private static void awaitClusterOk(GenericContainer<?> container) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            final String info = cli(container, "cluster", "info");
            if (info.contains("cluster_state:ok") && info.contains("cluster_known_nodes:2")) {
                return;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException(
                "The cluster did not reach cluster_state:ok: " + cli(container, "cluster", "info"));
    }

    @AfterAll
    static void stopCluster() {
        if (client != null) {
            client.shutdown();
        }
        if (resources != null) {
            resources.shutdown();
        }
        if (first != null) {
            first.stop();
        }
        if (second != null) {
            second.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        cli(first, "flushall");
        cli(second, "flushall");
        connection = client.connect();
        store = new RedisSessionLogSegmentStore(connection);
    }

    @AfterEach
    void tearDown() {
        connection.close();
    }

    @Override
    protected SessionLogSegmentStore store() {
        return store;
    }

    @Test
    @DisplayName("the scan reports sessions from both masters, paging a node at a time")
    void scanCoversEveryMaster() throws Exception {
        final Instant old = Instant.now().minus(Duration.ofHours(2));
        final Set<String> written = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            final SessionId session = SessionId.of("cluster-" + i);
            store.put(SessionLogSegment.builder().sessionId(session).id(SegmentId.generate()).fromSeq(0).toSeq(1)
                    .entryCount(1).payload("[]").createdAt(old).build());
            written.add(session.value());
        }
        assertThat(Long.parseLong(cli(first, "dbsize").trim())).as("keys on the first master").isPositive();
        assertThat(Long.parseLong(cli(second, "dbsize").trim())).as("keys on the second master").isPositive();
        assertThat(connection.getPartitions()).filteredOn(n -> n.is(RedisClusterNode.NodeFlag.UPSTREAM)).hasSize(2);

        final Set<String> reported = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            final SegmentScanPage page = store.scanSessions(Instant.now(), cursor, 3);
            page.getSessionIds().forEach(id -> reported.add(id.value()));
            cursor = page.getNextCursor().orElse(null);
            assertThat(++pages).isLessThan(1000);
        } while (cursor != null);

        assertThat(reported).isEqualTo(written);
    }
}
