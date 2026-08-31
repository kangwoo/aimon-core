package at.aimon.core.agent.impl.orca;

import static at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.BarrierTool;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.ConcurrentScriptedLlmClient;
import at.aimon.core.agent.queue.DefaultMessageQueueManager;
import at.aimon.core.agent.queue.InMemoryMessageQueueRepository;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.queue.QueuedInput;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;

/**
 * The mid-turn queue drain is scoped by {@link AgentRuntimeId}, not by session. Two sessions
 * belonging
 * to two <b>different</b> agent runtimes share one {@link OrcaAgentExecutor} and one
 * {@link MessageQueueManager}; when both turns hit their iteration-tail drain at the same instant, each must pick up
 * only
 * the queued input addressed to <b>its own</b> context and never steal or duplicate the other context's entry.
 *
 * <p>
 * Both turns are pinned together inside their first tool on a shared {@link CyclicBarrier}, so their subsequent
 * {@code drainForInjection} calls genuinely race on the shared queue. The test then inspects the exact message list
 * that
 * went on the wire for each session's second iteration and asserts strict, mutually-exclusive routing: context A's
 * turn sees {@code inject-for-A} and never {@code inject-for-B}, and vice versa. A drain that ignored the context
 * filter,
 * or a repository that mis-handled concurrent removal, would surface as a stolen, missing, or duplicated injection.
 */
@DisplayName("OrcaAgentExecutor concurrent context-scoped queue drain routing")
class OrcaAgentExecutorConcurrentQueueRoutingTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("two contexts drain concurrently: each turn injects only its own context's queued input")
    void concurrentContextScopedDrainsStayIsolated() {
        final OrcaAgentExecutorTestSupport support = new OrcaAgentExecutorTestSupport(tempDir);

        final AgentRuntimeId ctxAId = AgentRuntimeId.of("agent:ctxA");
        final AgentRuntimeId ctxBId = AgentRuntimeId.of("agent:ctxB");

        // Both turns rendezvous inside their first (iteration-1) tool, so both reach the iteration-tail drain together.
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final BarrierTool gateA = new BarrierTool("gate", barrier);
        final BarrierTool gateB = new BarrierTool("gate", barrier);

        // Each session: iter1 calls the gate tool (reaching the drain point), iter2 returns its final answer.
        final ConcurrentScriptedLlmClient client = new ConcurrentScriptedLlmClient();
        client.script("conv-A", toolCall("a1", "gate")).fallback("conv-A", LlmResponse.text("done-A"));
        client.script("conv-B", toolCall("b1", "gate")).fallback("conv-B", LlmResponse.text("done-B"));

        final MessageQueueManager queue = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());
        final OrcaAgentExecutor executor = support.newExecutorWithQueue(client, queue);
        final OrcaAgentRuntime contextA = support.newContext("agent:ctxA", gateA);
        final OrcaAgentRuntime contextB = support.newContext("agent:ctxB", gateB);

        // One queued input per context, enqueued BEFORE the turns run so both are drained at the first iteration's
        // tail.
        queue.enqueue(QueuedInput.builder().agentRuntimeId(ctxAId).inputText("inject-for-A")
                .priority(QueuedInputPriority.NEXT).build());
        queue.enqueue(QueuedInput.builder().agentRuntimeId(ctxBId).inputText("inject-for-B")
                .priority(QueuedInputPriority.NEXT).build());

        final CompletableFuture<OrcaAgentExecutionResult> futureA = executor
                .executeAsync(contextA, OrcaAgentExecutorTestSupport.request("conv-A"), event -> {
                }).toCompletableFuture();
        final CompletableFuture<OrcaAgentExecutionResult> futureB = executor
                .executeAsync(contextB, OrcaAgentExecutorTestSupport.request("conv-B"), event -> {
                }).toCompletableFuture();

        final OrcaAgentExecutionResult resultA = futureA.join();
        final OrcaAgentExecutionResult resultB = futureB.join();

        assertThat(gateA.rendezvoused && gateB.rendezvoused).as("both turns overlapped at the drain boundary").isTrue();
        assertThat(resultA.getFinalAnswer()).isEqualTo("done-A");
        assertThat(resultB.getFinalAnswer()).isEqualTo("done-B");

        // Inspect the message list that went on the wire for each session's second iteration (post-drain).
        final List<Message> turn2A = client.lastMessagesFor("conv-A");
        final List<Message> turn2B = client.lastMessagesFor("conv-B");

        assertThat(containsText(turn2A, "inject-for-A")).as("context A injects its own queued input").isTrue();
        assertThat(containsText(turn2A, "inject-for-B")).as("context A must NOT steal context B's queued input")
                .isFalse();
        assertThat(containsText(turn2B, "inject-for-B")).as("context B injects its own queued input").isTrue();
        assertThat(containsText(turn2B, "inject-for-A")).as("context B must NOT steal context A's queued input")
                .isFalse();

        // The shared queue is fully drained — each entry consumed exactly once, none left behind.
        assertThat(queue.snapshot()).as("both queued inputs consumed exactly once").isEmpty();
    }

    /**
     * The mirror case, and the one that actually occurs in production: <b>one</b> agent-scoped context with
     * <b>two</b> concurrent sessions. Because {@link at.aimon.core.agent.queue.QueuedInput} carries only an
     * {@link AgentRuntimeId} and no session id, and the context is shared across every session
     * against the same {@code (agent, discriminator)} pair (see {@code .claude/rules/scheduling.md}), both turns'
     * drain filters match <b>both</b> entries.
     *
     * <p>
     * What the framework still guarantees is the safety half — each entry is consumed <b>exactly once</b> across the
     * two turns and the queue ends empty, because {@code drainForInjection} removes by uuid and drops entries a
     * concurrent consumer already took. What it does <b>not</b> guarantee is addressing: which turn receives a given
     * entry is decided by the race, so text a user typed while watching session B can be injected into
     * session A's transcript instead. This test pins the guarantee that holds and documents the one that does
     * not — if per-session addressing is ever added (a session id on {@code QueuedInput} plus a matching
     * predicate at {@code OrcaAgentExecutor#injectQueuedMessages}), this test is the one that must be tightened.
     */
    @Test
    @DisplayName("one shared context, two conversations: each queued input is consumed exactly once, but which "
            + "conversation receives it is not addressable")
    void sharedContextDrainIsExactlyOnceButNotConversationAddressed() {
        final OrcaAgentExecutorTestSupport support = new OrcaAgentExecutorTestSupport(tempDir);

        final AgentRuntimeId sharedId = AgentRuntimeId.of("agent:shared");

        final CyclicBarrier barrier = new CyclicBarrier(2);
        final BarrierTool gate = new BarrierTool("gate", barrier);

        final ConcurrentScriptedLlmClient client = new ConcurrentScriptedLlmClient();
        client.script("conv-1", toolCall("t1", "gate")).fallback("conv-1", LlmResponse.text("done-1"));
        client.script("conv-2", toolCall("t2", "gate")).fallback("conv-2", LlmResponse.text("done-2"));

        final MessageQueueManager queue = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());
        final OrcaAgentExecutor executor = support.newExecutorWithQueue(client, queue);

        // ONE agent-scoped context, shared by both sessions — the arrangement the scope model prescribes.
        final OrcaAgentRuntime shared = support.newContext(sharedId.value(), gate);

        queue.enqueue(QueuedInput.builder().agentRuntimeId(sharedId).inputText("queued-alpha")
                .priority(QueuedInputPriority.NEXT).build());
        queue.enqueue(QueuedInput.builder().agentRuntimeId(sharedId).inputText("queued-beta")
                .priority(QueuedInputPriority.NEXT).build());

        final CompletableFuture<OrcaAgentExecutionResult> future1 = executor
                .executeAsync(shared, OrcaAgentExecutorTestSupport.request("conv-1"), event -> {
                }).toCompletableFuture();
        final CompletableFuture<OrcaAgentExecutionResult> future2 = executor
                .executeAsync(shared, OrcaAgentExecutorTestSupport.request("conv-2"), event -> {
                }).toCompletableFuture();

        final OrcaAgentExecutionResult result1 = future1.join();
        final OrcaAgentExecutionResult result2 = future2.join();

        assertThat(gate.rendezvoused).as("both turns overlapped at the drain boundary").isTrue();
        assertThat(result1.getFinalAnswer()).isEqualTo("done-1");
        assertThat(result2.getFinalAnswer()).isEqualTo("done-2");

        // Exactly-once delivery: every entry surfaced in exactly one of the two sessions. A double-delivery
        // (both turns injecting the same text) or a lost entry (neither turn seeing it) is a genuine defect.
        assertThat(conversationsThatSaw(client, "queued-alpha"))
                .as("queued-alpha must reach exactly one of the two concurrent turns").isEqualTo(1);
        assertThat(conversationsThatSaw(client, "queued-beta"))
                .as("queued-beta must reach exactly one of the two concurrent turns").isEqualTo(1);

        assertThat(queue.snapshot()).as("the shared queue is fully drained, with nothing left behind").isEmpty();
    }

    /** Number of the two sessions whose wire traffic ever carried {@code needle} (0, 1, or 2). */
    private static int conversationsThatSaw(ConcurrentScriptedLlmClient client, String needle) {
        int seen = 0;
        for (String sessionId : List.of("conv-1", "conv-2")) {
            for (List<Message> messages : client.messagesFor(sessionId)) {
                if (containsText(messages, needle)) {
                    seen++;
                    break;
                }
            }
        }
        return seen;
    }

    private static boolean containsText(List<Message> messages, String needle) {
        for (Message message : messages) {
            final String content = message.getContent();
            if (content != null && content.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
