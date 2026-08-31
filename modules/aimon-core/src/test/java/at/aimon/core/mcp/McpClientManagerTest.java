package at.aimon.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.mcp.McpServerConfig.AnnotationTrust;
import at.aimon.core.mcp.McpServerConfig.McpTransportType;
import at.aimon.core.mcp.exception.McpInitializeException;

class McpClientManagerTest {

    private McpClientFactory factory;
    private McpClientManager manager;

    @BeforeEach
    void setUp() {
        factory = mock(McpClientFactory.class);
        manager = new McpClientManager(factory);
    }

    private McpServerConfig stdioConfig(String name) {
        return McpServerConfig.builder().name(name).transportType(McpTransportType.STDIO).command("/bin/cat").build();
    }

    private McpServerConfig impatientConfig(String name) {
        return McpServerConfig.builder().name(name).transportType(McpTransportType.STDIO).command("/bin/cat")
                .requestTimeout(Duration.ofMillis(50)).build();
    }

    private McpServerConfig trustedConfig(String name) {
        return McpServerConfig.builder().name(name).transportType(McpTransportType.STDIO).command("/bin/cat")
                .annotationTrust(AnnotationTrust.TRUST).build();
    }

    private McpClient stubClient(String name) {
        McpClient client = mock(McpClient.class);
        lenient().when(client.getServerName()).thenReturn(name);
        return client;
    }

    /** A tool claiming to read nothing but state. */
    private McpToolSchema readOnlySchema(String name) {
        return McpToolSchema.of(name, "d", Map.of("type", "object"),
                McpToolAnnotations.builder().readOnlyHint(true).build());
    }

    /** A tool claiming to write, but only additively. */
    private McpToolSchema additiveSchema(String name) {
        return McpToolSchema.of(name, "d", Map.of("type", "object"),
                McpToolAnnotations.builder().readOnlyHint(false).destructiveHint(false).build());
    }

    @Test
    @DisplayName("Constructor rejects null factory")
    void nullFactoryRejected() {
        assertThatNullPointerException().isThrownBy(() -> new McpClientManager(null));
    }

    @Test
    @DisplayName("createClient registers client in map")
    void createClientRegisters() {
        McpServerConfig config = stdioConfig("github");
        McpClient client = stubClient("github");
        when(factory.create(config)).thenReturn(client);

        McpClient created = manager.createClient(config);

        assertThat(created).isSameAs(client);
        assertThat(manager.getClient("github")).contains(client);
        assertThat(manager.getServerNames()).containsExactly("github");
    }

    @Test
    @DisplayName("createClient rejects duplicate server name")
    void createClientDuplicateRejected() {
        McpServerConfig config = stdioConfig("github");
        McpClient client = stubClient("github");
        when(factory.create(any())).thenReturn(client);
        manager.createClient(config);

        assertThatIllegalArgumentException().isThrownBy(() -> manager.createClient(stdioConfig("github")))
                .withMessageContaining("already registered");
    }

    @Test
    @DisplayName("createClient rejects null config")
    void createClientNullRejected() {
        assertThatNullPointerException().isThrownBy(() -> manager.createClient(null));
    }

    @Test
    @DisplayName("createClient throws when manager is closed")
    void createClientAfterClose() {
        manager.closeAll();
        assertThatIllegalStateException().isThrownBy(() -> manager.createClient(stdioConfig("a")));
    }

    @Test
    @DisplayName("createClients returns empty list when configs empty")
    void createClientsEmptyConfigs() {
        assertThat(manager.createClients(List.of())).isEmpty();
        verify(factory, never()).create(any());
    }

    @Test
    @DisplayName("createClients with single config registers it")
    void createClientsSingle() {
        McpServerConfig config = stdioConfig("solo");
        McpClient client = stubClient("solo");
        when(factory.create(config)).thenReturn(client);

        List<String> failed = manager.createClients(List.of(config));

        assertThat(failed).isEmpty();
        assertThat(manager.getServerNames()).containsExactly("solo");
    }

    @Test
    @DisplayName("createClients with single failing config returns failed name")
    void createClientsSingleFails() {
        McpServerConfig config = stdioConfig("solo");
        when(factory.create(config)).thenThrow(new McpInitializeException("nope"));

        List<String> failed = manager.createClients(List.of(config));

        assertThat(failed).containsExactly("solo");
        assertThat(manager.getServerNames()).isEmpty();
    }

    @Test
    @DisplayName("createClients with multiple configs runs in parallel and returns failures")
    void createClientsParallel() {
        McpServerConfig ok1 = stdioConfig("ok1");
        McpServerConfig ok2 = stdioConfig("ok2");
        McpServerConfig bad = stdioConfig("bad");
        McpClient c1 = stubClient("ok1");
        McpClient c2 = stubClient("ok2");
        when(factory.create(ok1)).thenReturn(c1);
        when(factory.create(ok2)).thenReturn(c2);
        when(factory.create(bad)).thenThrow(new McpInitializeException("init failed"));

        List<String> failed = manager.createClients(List.of(ok1, ok2, bad));

        assertThat(failed).containsExactly("bad");
        assertThat(manager.getServerNames()).containsExactlyInAnyOrder("ok1", "ok2");
    }

    @Test
    @DisplayName("createClients gives up on a server that never finishes instead of waiting for it forever")
    @Timeout(30)
    void createClientsBoundsTheWait() throws Exception {
        // The wait is `longest requestTimeout + spawn allowance`; both are shrunk so the net fires in milliseconds.
        McpClientManager bounded = new McpClientManager(factory, Duration.ofMillis(50));
        McpServerConfig hangs = impatientConfig("hangs");
        McpServerConfig ok = impatientConfig("ok");
        McpClient okClient = stubClient("ok");
        CountDownLatch neverReleased = new CountDownLatch(1);
        when(factory.create(hangs)).thenAnswer(invocation -> {
            neverReleased.await();
            return stubClient("hangs");
        });
        when(factory.create(ok)).thenReturn(okClient);

        List<String> failed = bounded.createClients(List.of(hangs, ok));

        assertThat(failed).containsExactly("hangs");
        assertThat(bounded.getServerNames()).containsExactly("ok");
        neverReleased.countDown();
    }

    @Test
    @DisplayName("a server that finishes after being given up on is closed, not left registered")
    @Timeout(30)
    void createClientsDiscardsRacedClient() throws Exception {
        McpClientManager bounded = new McpClientManager(factory, Duration.ofMillis(50));
        McpServerConfig late = impatientConfig("late");
        McpClient lateClient = stubClient("late");
        // Ignores its cancellation and finishes well after the deadline, which is the only way to lose the race.
        when(factory.create(late)).thenAnswer(invocation -> {
            sleepIgnoringInterrupt(Duration.ofMillis(400));
            return lateClient;
        });

        List<String> failed = bounded.createClients(List.of(late));

        assertThat(failed).containsExactly("late");
        assertThat(bounded.getServerNames()).isEmpty();
        verify(lateClient).close();
    }

    private static void sleepIgnoringInterrupt(Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(remaining);
            } catch (InterruptedException e) {
                // Deliberately swallowed: this stub models a worker that does not honour cancellation.
            }
        }
    }

    @Test
    @DisplayName("createClients reports a duplicate server name rather than overwriting the client that holds it")
    void createClientsDuplicateName() {
        McpServerConfig first = stdioConfig("dup");
        McpServerConfig second = stdioConfig("dup");
        McpClient client = stubClient("dup");
        when(factory.create(first)).thenReturn(client);

        List<String> failed = manager.createClients(List.of(first, second));

        assertThat(failed).containsExactly("dup");
        assertThat(manager.getClient("dup")).contains(client);
        verify(factory, never()).create(second);
    }

    @Test
    @DisplayName("createClients reports a name an earlier call already registered")
    void createClientsNameAlreadyRegistered() {
        McpServerConfig config = stdioConfig("github");
        McpClient client = stubClient("github");
        when(factory.create(config)).thenReturn(client);
        manager.createClient(config);

        McpServerConfig again = stdioConfig("github");
        assertThat(manager.createClients(List.of(again))).containsExactly("github");
        assertThat(manager.getClient("github")).contains(client);
        verify(factory, never()).create(again);
    }

    @Test
    @DisplayName("createClients rejects null and post-close calls")
    void createClientsGuards() {
        assertThatNullPointerException().isThrownBy(() -> manager.createClients(null));

        manager.closeAll();
        assertThatIllegalStateException().isThrownBy(() -> manager.createClients(List.of(stdioConfig("a"))));
    }

    @Test
    @DisplayName("registerAllTools registers each tool from each client")
    void registerAllTools() {
        McpClient client = stubClient("github");
        when(factory.create(any())).thenReturn(client);
        manager.createClient(stdioConfig("github"));

        when(client.listTools()).thenReturn(List.of(McpToolSchema.of("create_issue", "d", Map.of("type", "object")),
                McpToolSchema.of("list_repos", "d", Map.of("type", "object"))));

        ToolRegistry registry = new DefaultToolRegistry();
        manager.registerAllTools(registry);

        assertThat(registry.findAll()).extracting(t -> t.getDefinition().getName())
                .containsExactlyInAnyOrder("mcp__github__create_issue", "mcp__github__list_repos");
    }

    @Test
    @DisplayName("registerAllTools ignores annotations by default, so a claim of readOnly buys nothing")
    void registerAllToolsIgnoresAnnotationsByDefault() {
        McpClient client = stubClient("github");
        when(factory.create(any())).thenReturn(client);
        manager.createClient(stdioConfig("github"));
        when(client.listTools()).thenReturn(List.of(readOnlySchema("list_repos")));

        ToolRegistry registry = new DefaultToolRegistry();
        manager.registerAllTools(registry);

        Tool tool = registry.findByName("mcp__github__list_repos").orElseThrow();
        assertThat(tool.getSideEffectLevel()).isEqualTo(SideEffectLevel.MUTATING);
        assertThat(tool.getDestructiveBehavior()).isEqualTo(DestructiveBehavior.DESTRUCTIVE);
    }

    @Test
    @DisplayName("registerAllTools turns a trusted server's claims into the tool's own declarations")
    void registerAllToolsAppliesTrust() {
        McpClient client = stubClient("github");
        when(factory.create(any())).thenReturn(client);
        manager.createClient(trustedConfig("github"));
        when(client.listTools()).thenReturn(List.of(readOnlySchema("list_repos"), additiveSchema("create_issue")));

        ToolRegistry registry = new DefaultToolRegistry();
        manager.registerAllTools(registry);

        Tool readOnly = registry.findByName("mcp__github__list_repos").orElseThrow();
        assertThat(readOnly.getSideEffectLevel()).isEqualTo(SideEffectLevel.READ_ONLY);
        assertThat(readOnly.isReadOnly()).isTrue();
        assertThat(readOnly.getDestructiveBehavior()).isEqualTo(DestructiveBehavior.NON_DESTRUCTIVE);

        Tool additive = registry.findByName("mcp__github__create_issue").orElseThrow();
        assertThat(additive.getSideEffectLevel()).isEqualTo(SideEffectLevel.MUTATING);
        assertThat(additive.getDestructiveBehavior()).isEqualTo(DestructiveBehavior.NON_DESTRUCTIVE);
    }

    @Test
    @DisplayName("Trust is per server — the same claim is believed from one and not the other")
    void trustIsNotTransitiveAcrossServers() {
        McpClient trusted = stubClient("mine");
        McpClient stranger = stubClient("theirs");
        when(factory.create(any())).thenReturn(trusted, stranger);
        manager.createClient(trustedConfig("mine"));
        manager.createClient(stdioConfig("theirs"));
        when(trusted.listTools()).thenReturn(List.of(readOnlySchema("search")));
        when(stranger.listTools()).thenReturn(List.of(readOnlySchema("search")));

        ToolRegistry registry = new DefaultToolRegistry();
        manager.registerAllTools(registry);

        assertThat(registry.findByName("mcp__mine__search").orElseThrow().getSideEffectLevel())
                .isEqualTo(SideEffectLevel.READ_ONLY);
        assertThat(registry.findByName("mcp__theirs__search").orElseThrow().getSideEffectLevel())
                .isEqualTo(SideEffectLevel.MUTATING);
    }

    @Test
    @DisplayName("registerAllTools rejects null registry")
    void registerAllToolsRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> manager.registerAllTools(null));
    }

    @Test
    @DisplayName("getClient returns empty after close")
    void getClientAfterCloseEmpty() {
        McpClient client = stubClient("a");
        when(factory.create(any())).thenReturn(client);
        manager.createClient(stdioConfig("a"));

        manager.closeAll();

        assertThat(manager.getClient("a")).isEmpty();
        assertThat(manager.isClosed()).isTrue();
    }

    @Test
    @DisplayName("getClient returns empty for unknown name")
    void getClientUnknown() {
        assertThat(manager.getClient("unknown")).isEmpty();
    }

    @Test
    @DisplayName("closeAll closes every registered client")
    void closeAllClosesEach() throws Exception {
        McpClient c1 = stubClient("a");
        McpClient c2 = stubClient("b");
        when(factory.create(any())).thenReturn(c1, c2);
        manager.createClient(stdioConfig("a"));
        manager.createClient(stdioConfig("b"));

        manager.closeAll();

        verify(c1, atLeastOnce()).close();
        verify(c2, atLeastOnce()).close();
        assertThat(manager.getServerNames()).isEmpty();
    }

    @Test
    @DisplayName("closeAll is idempotent")
    void closeAllIdempotent() throws Exception {
        McpClient c1 = stubClient("a");
        when(factory.create(any())).thenReturn(c1);
        manager.createClient(stdioConfig("a"));

        manager.closeAll();
        manager.closeAll();

        verify(c1, times(1)).close();
    }

    @Test
    @DisplayName("closeAll continues even when individual close throws")
    void closeAllToleratesFailures() throws Exception {
        McpClient c1 = stubClient("a");
        McpClient c2 = stubClient("b");
        doThrow(new RuntimeException("c1 boom")).when(c1).close();
        when(factory.create(any())).thenReturn(c1, c2);
        manager.createClient(stdioConfig("a"));
        manager.createClient(stdioConfig("b"));

        manager.closeAll();

        verify(c2, atLeastOnce()).close();
        assertThat(manager.isClosed()).isTrue();
    }

    @Test
    @DisplayName("close() (AgentScoped) delegates to closeAll")
    void closeDelegates() throws Exception {
        McpClient c1 = stubClient("a");
        when(factory.create(any())).thenReturn(c1);
        manager.createClient(stdioConfig("a"));

        manager.close();

        assertThat(manager.isClosed()).isTrue();
        verify(c1, atLeastOnce()).close();
    }
}
