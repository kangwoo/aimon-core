package at.aimon.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.session.redis.RedisIdempotencyStore;
import at.aimon.session.redis.RedisPubSubSignalBus;
import at.aimon.session.redis.RedisSessionInbox;
import at.aimon.session.redis.RedisSessionLeaseStore;
import at.aimon.session.redis.RedisSessionRecordStore;
import at.aimon.session.routing.SubmitDisposition;
import at.aimon.spring.boot.AimonSessions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

/**
 * Two starter-assembled nodes over one Redis, doing the two things a second node exists to do.
 *
 * <p>
 * <b>The nodes share a container and nothing else.</b> Each is a separate application context that builds its
 * own stack from its own beans — its own record store, its own lease store, its own connections — exactly as
 * two pods would. Sharing a {@code SessionRecordStore} <em>instance</em> between them would make this file
 * pass without proving anything: the path where node A writes a transcript and node B reads it would be a heap
 * reference rather than a round trip, and the test would go on passing if the store were in-memory. That is why
 * {@link #transcriptCrossesToTheOtherNode} asserts the two instances are different objects before it asserts
 * anything else — the harness has to be honest about being two nodes before its results mean anything.
 *
 * <p>
 * The per-backend tests already cover the store round trip on its own ({@code nodeHandoffCarriesTheTranscript}
 * in each of the three modules). What is new here is everything above it: real autoconfiguration, real agent
 * runtimes, a real router with a real lease, and a turn that runs end to end on whichever node the cluster says
 * owns the session.
 */
@DisplayName("Two starter nodes over one Redis")
@Tag("docker")
class AimonClusterIntegrationTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379).withCommand("redis-server", "--appendonly", "no");

    private static RedisClient client;

    @AfterAll
    static void stopContainer() {
        if (client != null) {
            client.shutdown();
        }
        if (REDIS.isRunning()) {
            REDIS.stop();
        }
    }

    @BeforeEach
    void startContainerAndClearKeyspace() {
        if (!REDIS.isRunning()) {
            REDIS.start();
            client = RedisClient.create(RedisURI.Builder.redis(REDIS.getHost(), REDIS.getMappedPort(6379)).build());
        }
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            connection.sync().flushall();
        }
    }

    @Test
    @DisplayName("the transcript crosses to the other node once the first releases the session")
    void transcriptCrossesToTheOtherNode(@TempDir Path workspace) {
        node("node-a", workspace.resolve("a")).run(nodeA -> node("node-b", workspace.resolve("b")).run(nodeB -> {
            assertThat(nodeA.getBean(SessionRecordStore.class))
                    .as("two nodes must not share a store instance — a heap reference would fake the round trip")
                    .isNotSameAs(nodeB.getBean(SessionRecordStore.class));

            final SessionId sessionId = SessionId.of("cluster-handoff");
            final AgentExecutionResult first = nodeA.getBean(AimonSessions.class).submit(sessionId,
                    "what is the capacity of the west cluster");
            assertThat(first.isSuccess()).isTrue();

            // Without this the lease stays with node A and the next submit is forwarded rather than taken over —
            // which is the other test. Release is what a graceful pod shutdown does.
            nodeA.getBean(AimonSessions.class).release(sessionId);

            final AgentExecutionResult second = nodeB.getBean(AimonSessions.class).submit(sessionId,
                    "and the east one");
            assertThat(second.isSuccess()).isTrue();

            // The claim, stated where it can fail: node B's model saw a conversation it never took part in. Both
            // halves of node A's turn have to be there — a store that persisted only the user side would leave
            // the agent answering a question it has no record of having answered.
            final List<String> promptOnNodeB = nodeB.getBean(ScriptedLlmClient.class).lastConversation();
            assertThat(promptOnNodeB).anyMatch(text -> text.contains("west cluster"))
                    .anyMatch(text -> text.contains(ScriptedLlmClient.ANSWER)).anyMatch(text -> text.contains("east"));
        }));
    }

    @Test
    @DisplayName("a submit to the node that does not hold the session is forwarded to the one that does")
    void submitToTheNonHolderIsForwarded(@TempDir Path workspace) throws Exception {
        node("node-a", workspace.resolve("a")).run(nodeA -> node("node-b", workspace.resolve("b")).run(nodeB -> {
            final SessionId sessionId = SessionId.of("cluster-forward");
            // Node A takes the lease by running a turn, and keeps it: the live handle stays cached, so the lease
            // it protects stays held.
            assertThat(nodeA.getBean(AimonSessions.class).submit(sessionId, "first").isSuccess()).isTrue();

            final SubmitDisposition disposition = nodeB.getBean(AimonSessions.class).submitAsync(sessionId, "second");
            assertThat(disposition.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);
            assertThat(disposition.getInboxId()).isPresent();

            // Forwarding is only half a promise — the caller still gets an answer, from the other node.
            final AgentExecutionResult forwarded = disposition.getFuture().toCompletableFuture().get(30,
                    TimeUnit.SECONDS);
            assertThat(forwarded.isSuccess()).isTrue();

            // And it ran where the lease was. Node B's model was never asked anything.
            assertThat(nodeA.getBean(ScriptedLlmClient.class).callCount()).isEqualTo(2);
            assertThat(nodeB.getBean(ScriptedLlmClient.class).callCount()).isZero();
        }));
    }

    /**
     * One node: the four autoconfigurations, distributed mode, and a Redis-backed everything.
     *
     * @param nodeId
     *            the lease holder identity this node claims sessions under
     * @param workspace
     *            this node's workspace root — separate per node, as two pods would have
     * @return a runner that builds the node when run
     */
    private static ApplicationContextRunner node(String nodeId, Path workspace) {
        return new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(AimonLlmAutoConfiguration.class, AimonFileSystemAutoConfiguration.class,
                                AimonSessionAutoConfiguration.class, AimonSchedulingAutoConfiguration.class,
                                AimonKnowledgeAutoConfiguration.class, AimonMemoryAutoConfiguration.class,
                                AimonObservabilityAutoConfiguration.class, AimonAutoConfiguration.class))
                .withUserConfiguration(RedisNodeConfiguration.class, ScriptedLlmConfiguration.class)
                .withPropertyValues("aimon.workspace.root=" + workspace,
                        "aimon.agent-defaults.default-agent=test-agent", "aimon.session.store=redis",
                        "aimon.session.mode=distributed", "aimon.session.node-id=" + nodeId);
    }

    /**
     * The five Redis-backed collaborators distributed mode needs, over this node's own connections.
     *
     * <p>
     * Spring owns every one of them, which is the ownership rule this configuration also demonstrates: the
     * signal bus holds a subscriber thread and is closed by the container, after the {@code SessionSpec} that
     * borrowed it and therefore after the stack built from that spec has drained. Nothing here is added to the
     * stack's teardown plan — one resource, one destruction edge.
     */
    @Configuration(proxyBeanMethods = false)
    static class RedisNodeConfiguration {

        @Bean
        StatefulRedisConnection<String, String> dataConnection() {
            return client.connect();
        }

        @Bean
        StatefulRedisConnection<String, String> publishConnection() {
            return client.connect();
        }

        @Bean
        StatefulRedisPubSubConnection<String, String> subscribeConnection() {
            return client.connectPubSub();
        }

        @Bean
        SessionRecordStore recordStore(@Qualifier("dataConnection") StatefulRedisConnection<String, String> data) {
            return new RedisSessionRecordStore(data);
        }

        @Bean
        SessionLeaseStore leaseStore(@Qualifier("dataConnection") StatefulRedisConnection<String, String> data) {
            return new RedisSessionLeaseStore(data);
        }

        @Bean
        SessionSignalBus signalBus(@Qualifier("publishConnection") StatefulRedisConnection<String, String> publish,
                StatefulRedisPubSubConnection<String, String> subscribe) {
            return new RedisPubSubSignalBus(publish, subscribe);
        }

        @Bean
        SessionInbox inbox(@Qualifier("dataConnection") StatefulRedisConnection<String, String> data) {
            return new RedisSessionInbox(data);
        }

        @Bean
        IdempotencyStore idempotencyStore(@Qualifier("dataConnection") StatefulRedisConnection<String, String> data) {
            return new RedisIdempotencyStore(data);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ScriptedLlmConfiguration {

        @Bean
        LlmClient scriptedLlmClient() {
            return new ScriptedLlmClient();
        }
    }

    /**
     * Answers in one turn and remembers what it was shown.
     *
     * <p>
     * Recording the conversation is the whole point: it is the only place a test can observe what the framework
     * decided this node knows about the session. Everything else — the store, the lease, the inbox — is
     * infrastructure whose correctness only shows up here.
     */
    static class ScriptedLlmClient implements LlmClient {

        static final String ANSWER = "the cluster has four nodes free";

        private final List<List<String>> conversations = new CopyOnWriteArrayList<>();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            final List<String> texts = new ArrayList<>();
            for (Message message : messages) {
                if (message.getContent() != null) {
                    texts.add(message.getRole() + ": " + message.getContent());
                }
            }
            conversations.add(List.copyOf(texts));
            return LlmResponse.text(ANSWER);
        }

        @Override
        public String getProviderName() {
            return "scripted";
        }

        int callCount() {
            return conversations.size();
        }

        /** The messages handed to the model on the most recent call, as {@code ROLE: text}. */
        List<String> lastConversation() {
            assertThat(conversations).as("the model was never called on this node").isNotEmpty();
            return conversations.get(conversations.size() - 1);
        }
    }
}
