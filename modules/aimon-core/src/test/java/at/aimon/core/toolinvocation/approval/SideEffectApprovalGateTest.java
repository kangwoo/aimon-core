package at.aimon.core.toolinvocation.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.execution.AskPromptHandler;
import at.aimon.core.hook.execution.Decision;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.tools.ForkAccess;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Unit tests for {@link SideEffectApprovalGate}.
 *
 * <p>
 * The behaviours worth pinning here are the ones a regression would make silently unsafe or silently unusable: which
 * declarations are exempt, that no threshold switches off the question for a destructive tool, that a scope is asked at
 * most once (approval fatigue), that a decline sticks as firmly as an approval, and that a fork resolves against the
 * session that launched it rather than being asked through a channel it does not have.
 *
 * <p>
 * One more since the prompt started carrying the invocation: it must show the arguments — deterministically ordered,
 * flattened and cut — while the decision keys on the tool alone. Both halves are pinned, because a prompt that showed a
 * concrete call and silently bought every later one would be worse than the tool-name-only prompt it replaced.
 */
@DisplayName("SideEffectApprovalGate")
class SideEffectApprovalGateTest {

    private static final String TOOL_NAME = "Write";

    // --- helpers ------------------------------------------------------------------------------------------------

    /** Records every prompt it is shown and answers each with the next queued decision. */
    private static final class RecordingHandler implements AskPromptHandler {

        private final List<String> prompts = new ArrayList<>();
        private final Decision answer;
        private final AtomicInteger calls = new AtomicInteger();

        RecordingHandler(Decision answer) {
            this.answer = answer;
        }

        @Override
        public Decision resolve(String prompt) {
            prompts.add(prompt);
            calls.incrementAndGet();
            return answer;
        }

        int callCount() {
            return calls.get();
        }
    }

    /** A tool that declares only its level, and so is destructive by default — the unaudited case. */
    private static Tool tool(String name, SideEffectLevel level) {
        return tool(name, level, DestructiveBehavior.DESTRUCTIVE);
    }

    private static Tool tool(String name, SideEffectLevel level, DestructiveBehavior destructive) {
        return new AbstractTool(name, "test tool", Map.of("type", "object")) {

            @Override
            public SideEffectLevel getSideEffectLevel() {
                return level;
            }

            @Override
            public DestructiveBehavior getDestructiveBehavior() {
                return destructive;
            }

            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                return ToolResult.success("ok");
            }
        };
    }

    /**
     * Asks the gate about a no-argument call of {@code tool}. Most tests here are about the decision, which never reads
     * the arguments; the ones that are about the prompt build their own {@link ToolUse}.
     */
    private static Optional<String> ask(SideEffectApprovalGate gate, Tool tool, ToolContext context) {
        return gate.denialReason(tool, ToolUse.of("call-1", tool.getDefinition().getName(), Map.of()), context);
    }

    private static ToolContext turnContext(String sessionId) {
        return ToolContext.builder().put(ToolContextKeys.SESSION_ID, SessionId.of(sessionId)).build();
    }

    private static ToolContext forkContext(String invokingSessionId) {
        return ToolContext.builder().put(ToolContextKeys.INVOKING_SESSION_ID, SessionId.of(invokingSessionId)).build();
    }

    // --- exemption ----------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("exemption threshold")
    class Exemption {

        @Test
        @DisplayName("a READ_ONLY tool runs without asking anyone")
        void readOnlyTool_isNotAsked() {
            final RecordingHandler handler = new RecordingHandler(Decision.DENY);
            final SideEffectApprovalGate gate = new SideEffectApprovalGate(handler);

            final Optional<String> refusal = ask(gate, tool("Read", SideEffectLevel.READ_ONLY), turnContext("s-1"));

            assertThat(refusal).isEmpty();
            assertThat(handler.callCount()).isZero();
        }

        @Test
        @DisplayName("by default every tool that writes is asked about, whatever the write is")
        void defaultGate_asksAboutEveryWrite() {
            final RecordingHandler handler = new RecordingHandler(Decision.ALLOW);
            final SideEffectApprovalGate gate = new SideEffectApprovalGate(handler);

            ask(gate, tool("Touch", SideEffectLevel.MUTATING), turnContext("s-1"));
            ask(gate, tool(TOOL_NAME, SideEffectLevel.MUTATING), turnContext("s-1"));

            assertThat(handler.callCount()).isEqualTo(2);
            assertThat(gate.getExemptAtOrBelow()).isEqualTo(SideEffectLevel.READ_ONLY);
        }

        @Test
        @DisplayName("a MUTATING threshold exempts additive writes")
        void mutatingThreshold_exemptsAdditiveWrites() {
            final RecordingHandler handler = new RecordingHandler(Decision.ALLOW);
            final SideEffectApprovalGate gate = new SideEffectApprovalGate(SideEffectLevel.MUTATING, handler,
                    new InMemoryToolApprovalStore());

            assertThat(ask(gate, tool("Touch", SideEffectLevel.MUTATING, DestructiveBehavior.NON_DESTRUCTIVE),
                    turnContext("s-1"))).isEmpty();

            assertThat(handler.callCount()).isZero();
        }
    }

    // --- destructiveness ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("destructiveness overrides the threshold")
    class Destructiveness {

        @Test
        @DisplayName("a DESTRUCTIVE tool is asked about even at the highest threshold")
        void destructiveTool_isAskedAtAnyThreshold() {
            final RecordingHandler handler = new RecordingHandler(Decision.ALLOW);
            final SideEffectApprovalGate gate = new SideEffectApprovalGate(SideEffectLevel.MUTATING, handler,
                    new InMemoryToolApprovalStore());

            assertThat(ask(gate, tool("Delete", SideEffectLevel.MUTATING, DestructiveBehavior.DESTRUCTIVE),
                    turnContext("s-1"))).isEmpty();

            assertThat(handler.callCount()).as("no threshold can switch off the destructive branch").isEqualTo(1);
        }

        @Test
        @DisplayName("and can still be declined, at the threshold that would otherwise have exempted it")
        void destructiveTool_canBeDeclinedAtTheHighestThreshold() {
            final SideEffectApprovalGate gate = new SideEffectApprovalGate(SideEffectLevel.MUTATING,
                    AskPromptHandler.denyAll(), new InMemoryToolApprovalStore());

            assertThat(ask(gate, tool("Delete", SideEffectLevel.MUTATING, DestructiveBehavior.DESTRUCTIVE),
                    turnContext("s-1"))).isPresent();
        }

        @Test
        @DisplayName("a READ_ONLY tool is exempt even when it also declares DESTRUCTIVE — the second axis is not read")
        void readOnlyTool_isExemptDespiteDestructiveDeclaration() {
            final RecordingHandler handler = new RecordingHandler(Decision.DENY);
            final SideEffectApprovalGate gate = new SideEffectApprovalGate(handler);

            assertThat(ask(gate, tool("Read", SideEffectLevel.READ_ONLY, DestructiveBehavior.DESTRUCTIVE),
                    turnContext("s-1"))).isEmpty();

            assertThat(handler.callCount()).isZero();
        }

        @Test
        @DisplayName("the prompt says which of the two put the tool here")
        void prompt_distinguishesDestructiveFromAdditive() {
            final RecordingHandler destructive = new RecordingHandler(Decision.ALLOW);
            ask(new SideEffectApprovalGate(destructive),
                    tool("Delete", SideEffectLevel.MUTATING, DestructiveBehavior.DESTRUCTIVE), turnContext("s-1"));

            final RecordingHandler additive = new RecordingHandler(Decision.ALLOW);
            ask(new SideEffectApprovalGate(additive),
                    tool("Touch", SideEffectLevel.MUTATING, DestructiveBehavior.NON_DESTRUCTIVE), turnContext("s-1"));

            assertThat(destructive.prompts).singleElement().asString().contains("DESTRUCTIVE")
                    .contains("overwrite or remove");
            assertThat(additive.prompts).singleElement().asString().contains("NON_DESTRUCTIVE")
                    .doesNotContain("overwrite or remove");
        }
    }

    // --- asking and remembering ---------------------------------------------------------------------------------

    @Nested
    @DisplayName("asked once per scope")
    class AskedOnce {

        @Test
        @DisplayName("an approval is remembered — the same tool is not asked about again in that session")
        void approval_isRememberedForTheSession() {
            final RecordingHandler handler = new RecordingHandler(Decision.ALLOW);
            final SideEffectApprovalGate gate = new SideEffectApprovalGate(handler);
            final Tool write = tool(TOOL_NAME, SideEffectLevel.MUTATING);

            for (int i = 0; i < 5; i++) {
                assertThat(ask(gate, write, turnContext("s-1"))).isEmpty();
            }

            assertThat(handler.callCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a decline is remembered just as firmly — the user is not re-prompted for a tool they refused")
        void decline_isRememberedForTheSession() {
            final RecordingHandler handler = new RecordingHandler(Decision.DENY);
            final SideEffectApprovalGate gate = new SideEffectApprovalGate(handler);
            final Tool write = tool(TOOL_NAME, SideEffectLevel.MUTATING);

            final Optional<String> first = ask(gate, write, turnContext("s-1"));
            final Optional<String> second = ask(gate, write, turnContext("s-1"));

            assertThat(first).isPresent();
            assertThat(second).isPresent();
            assertThat(handler.callCount()).isEqualTo(1);
            // Both refusals tell the model not to retry; the second says the answer is an existing one.
            assertThat(first.get()).contains("was not approved by the user").contains("Do not retry");
            assertThat(second.get()).contains("earlier in this session");
        }

        @Test
        @DisplayName("the answer is per tool, not per session — a second tool is asked about separately")
        void answerIsPerTool() {
            final RecordingHandler handler = new RecordingHandler(Decision.ALLOW);
            final SideEffectApprovalGate gate = new SideEffectApprovalGate(handler);

            ask(gate, tool(TOOL_NAME, SideEffectLevel.MUTATING), turnContext("s-1"));
            ask(gate, tool("Edit", SideEffectLevel.MUTATING), turnContext("s-1"));

            assertThat(handler.callCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("another session does not inherit the answer")
        void answerDoesNotLeakAcrossSessions() {
            final RecordingHandler handler = new RecordingHandler(Decision.ALLOW);
            final SideEffectApprovalGate gate = new SideEffectApprovalGate(handler);
            final Tool write = tool(TOOL_NAME, SideEffectLevel.MUTATING);

            ask(gate, write, turnContext("s-1"));
            ask(gate, write, turnContext("s-2"));

            assertThat(handler.callCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("the prompt names the tool and its declaration so the user can judge what they are approving")
        void prompt_namesToolAndDeclaration() {
            final RecordingHandler handler = new RecordingHandler(Decision.ALLOW);
            ask(new SideEffectApprovalGate(handler), tool(TOOL_NAME, SideEffectLevel.MUTATING), turnContext("s-1"));

            assertThat(handler.prompts).singleElement().asString().contains(TOOL_NAME).contains("MUTATING");
        }
    }

    // --- what the prompt shows ----------------------------------------------------------------------------------

    @Nested
    @DisplayName("the prompt shows the call")
    class PromptShowsTheCall {

        private String promptFor(Tool tool, Map<String, Object> input) {
            final RecordingHandler handler = new RecordingHandler(Decision.ALLOW);
            new SideEffectApprovalGate(handler).denialReason(tool,
                    ToolUse.of("call-1", tool.getDefinition().getName(), input), turnContext("s-1"));
            return handler.prompts.get(0);
        }

        @Test
        @DisplayName("arguments are rendered, so the user is judging a call rather than a tool name")
        void prompt_rendersArguments() {
            final String prompt = promptFor(tool("Bash", SideEffectLevel.MUTATING), Map.of("command", "rm -rf build"));

            assertThat(prompt).contains("'Bash(command=rm -rf build)'");
        }

        @Test
        @DisplayName("arguments are ordered by key, because the invocation map is not ordered")
        void prompt_ordersArgumentsByKey() {
            final Map<String, Object> unordered = new LinkedHashMap<>();
            unordered.put("zone", "eu");
            unordered.put("action", "drop");
            unordered.put("name", "orders");

            assertThat(promptFor(tool("Table", SideEffectLevel.MUTATING), unordered))
                    .contains("'Table(action=drop, name=orders, zone=eu)'");
        }

        @Test
        @DisplayName("a call with no arguments still reads as a call")
        void prompt_rendersEmptyInputAsABareCall() {
            assertThat(promptFor(tool(TOOL_NAME, SideEffectLevel.MUTATING), Map.of())).contains("'Write()'");
        }

        @Test
        @DisplayName("a long argument is cut and flattened — a prompt is one line the user has to read")
        void prompt_shortensLongArguments() {
            final String script = "line one\nline two\n" + "x".repeat(500);

            final String prompt = promptFor(tool("Bash", SideEffectLevel.MUTATING), Map.of("command", script));

            assertThat(prompt).contains("line one line two").doesNotContain("\n").contains("...");
            assertThat(prompt.length()).isLessThan(400);
        }

        @Test
        @DisplayName("the reach is stated, because the answer buys more than the call being shown")
        void prompt_statesThatTheAnswerCoversLaterCalls() {
            assertThat(promptFor(tool("Bash", SideEffectLevel.MUTATING), Map.of("command", "ls")))
                    .contains("covers every 'Bash' call").contains("whatever its arguments");
        }

        @Test
        @DisplayName("and the decision keys on the tool only — different arguments are not asked about again")
        void decision_ignoresArguments() {
            final RecordingHandler handler = new RecordingHandler(Decision.ALLOW);
            final SideEffectApprovalGate gate = new SideEffectApprovalGate(handler);
            final Tool bash = tool("Bash", SideEffectLevel.MUTATING);

            gate.denialReason(bash, ToolUse.of("call-1", "Bash", Map.of("command", "ls")), turnContext("s-1"));
            gate.denialReason(bash, ToolUse.of("call-2", "Bash", Map.of("command", "rm -rf /")), turnContext("s-1"));

            assertThat(handler.callCount()).as("the prompt showed one call but the answer bought the tool")
                    .isEqualTo(1);
        }
    }

    // --- scope resolution ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("scope resolution")
    class Scope {

        @Test
        @DisplayName("a fork inherits what the launching session answered, and is not prompted itself")
        void fork_inheritsTheInvokingSessionsAnswer() {
            final RecordingHandler handler = new RecordingHandler(Decision.ALLOW);
            final SideEffectApprovalGate gate = new SideEffectApprovalGate(handler);
            final Tool write = tool(TOOL_NAME, SideEffectLevel.MUTATING);

            // The user approves in their own turn...
            assertThat(ask(gate, write, turnContext("s-1"))).isEmpty();
            // ...and the fork that turn spawned carries only INVOKING_SESSION_ID.
            assertThat(ask(gate, write, forkContext("s-1"))).isEmpty();

            assertThat(handler.callCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a fork inherits a decline too — the reach is bidirectional")
        void fork_inheritsTheInvokingSessionsDecline() {
            final RecordingHandler handler = new RecordingHandler(Decision.DENY);
            final SideEffectApprovalGate gate = new SideEffectApprovalGate(handler);
            final Tool write = tool(TOOL_NAME, SideEffectLevel.MUTATING);

            assertThat(ask(gate, write, turnContext("s-1"))).isPresent();
            assertThat(ask(gate, write, forkContext("s-1"))).isPresent();

            assertThat(handler.callCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a run with no session of any kind is asked every time and cannot cache an answer")
        void sessionlessRun_isAskedEveryTime() {
            final RecordingHandler handler = new RecordingHandler(Decision.ALLOW);
            final SideEffectApprovalGate gate = new SideEffectApprovalGate(handler);
            final Tool write = tool(TOOL_NAME, SideEffectLevel.MUTATING);

            ask(gate, write, ToolContext.empty());
            ask(gate, write, ToolContext.empty());

            assertThat(handler.callCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("a session-less run falls to the handler's answer, which for the deny-all default is refusal")
        void sessionlessRun_withDenyingHandler_isRefused() {
            final SideEffectApprovalGate gate = new SideEffectApprovalGate(AskPromptHandler.denyAll());

            final Optional<String> refusal = ask(gate, tool(TOOL_NAME, SideEffectLevel.MUTATING), ToolContext.empty());

            assertThat(refusal).isPresent();
        }
    }

    // --- store integration --------------------------------------------------------------------------------------

    @Test
    @DisplayName("revoking the scope makes the next call ask again")
    void revoke_causesReprompt() {
        final RecordingHandler handler = new RecordingHandler(Decision.ALLOW);
        final SideEffectApprovalGate gate = new SideEffectApprovalGate(handler);
        final Tool write = tool(TOOL_NAME, SideEffectLevel.MUTATING);

        ask(gate, write, turnContext("s-1"));
        gate.getStore().revoke("session:s-1");
        ask(gate, write, turnContext("s-1"));

        assertThat(handler.callCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("null arguments are rejected")
    void nullArguments_throw() {
        final SideEffectApprovalGate gate = new SideEffectApprovalGate(AskPromptHandler.allowAll());

        assertThatNullPointerException().isThrownBy(() -> new SideEffectApprovalGate(null));
        assertThatNullPointerException().isThrownBy(
                () -> new SideEffectApprovalGate(null, AskPromptHandler.allowAll(), new InMemoryToolApprovalStore()));
        assertThatNullPointerException().isThrownBy(
                () -> new SideEffectApprovalGate(SideEffectLevel.READ_ONLY, null, new InMemoryToolApprovalStore()));
        assertThatNullPointerException().isThrownBy(
                () -> new SideEffectApprovalGate(SideEffectLevel.READ_ONLY, AskPromptHandler.allowAll(), null));
        final Tool write = tool(TOOL_NAME, SideEffectLevel.MUTATING);
        final ToolUse call = ToolUse.of("call-1", TOOL_NAME, Map.of());
        assertThatNullPointerException().isThrownBy(() -> gate.denialReason(null, call, ToolContext.empty()));
        assertThatNullPointerException().isThrownBy(() -> gate.denialReason(write, null, ToolContext.empty()));
        assertThatNullPointerException().isThrownBy(() -> gate.denialReason(write, call, null));
    }

    // --- concurrent questions ------------------------------------------------------------------------------------

    /**
     * Parallel foreground forks of one session share its scope, so the same question can reach the gate several times
     * at once. It must be asked once and its answer shared, while unrelated questions never wait on each other.
     */
    @Nested
    @DisplayName("concurrent questions")
    class ConcurrentQuestions {

        /** Blocks every prompt until released, counting how many prompts it was shown. */
        private final class BlockingHandler implements AskPromptHandler {
            private final CountDownLatch release = new CountDownLatch(1);
            private final CountDownLatch entered;
            private final AtomicInteger calls = new AtomicInteger();
            private final List<String> prompts = new CopyOnWriteArrayList<>();
            private final Decision answer;

            BlockingHandler(Decision answer, int expectedEntries) {
                this.answer = answer;
                this.entered = new CountDownLatch(expectedEntries);
            }

            @Override
            public Decision resolve(String prompt) {
                calls.incrementAndGet();
                prompts.add(prompt);
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return answer;
            }
        }

        private final ExecutorService callers = Executors.newCachedThreadPool();

        @AfterEach
        void shutdown() {
            callers.shutdownNow();
        }

        @Test
        @DisplayName("the same question asked eight times at once prompts once and every caller gets the answer")
        void sameQuestionPromptsOnce() throws Exception {
            final BlockingHandler handler = new BlockingHandler(Decision.ALLOW, 1);
            final InMemoryToolApprovalStore store = new InMemoryToolApprovalStore();
            final SideEffectApprovalGate gate = new SideEffectApprovalGate(SideEffectLevel.READ_ONLY, handler, store);
            final Tool write = tool(TOOL_NAME, SideEffectLevel.MUTATING);
            final List<Future<Optional<String>>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(callers.submit(() -> ask(gate, write, forkContext("s1"))));
            }
            assertThat(handler.entered.await(5, TimeUnit.SECONDS)).isTrue();
            // Give the other seven time to reach the gate and queue on the open question.
            Thread.sleep(200);
            handler.release.countDown();

            for (Future<Optional<String>> result : results) {
                assertThat(result.get(5, TimeUnit.SECONDS)).isEmpty();
            }
            assertThat(handler.calls.get()).isEqualTo(1);
            assertThat(store.lookup("session:s1", TOOL_NAME)).contains(true);
        }

        @Test
        @DisplayName("a question in another scope, or for another tool, is not held up by an open prompt")
        void otherQuestionsDoNotWait() throws Exception {
            final CountDownLatch blockedEntered = new CountDownLatch(1);
            final CountDownLatch releaseBlocked = new CountDownLatch(1);
            final AskPromptHandler handler = prompt -> {
                if (prompt.contains("'Write(")) {
                    // Only the scope-A Write question blocks; everything else answers at once.
                    blockedEntered.countDown();
                    try {
                        releaseBlocked.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return Decision.ALLOW;
            };
            final SideEffectApprovalGate gate = new SideEffectApprovalGate(handler);
            final Tool write = tool(TOOL_NAME, SideEffectLevel.MUTATING);
            final Tool edit = tool("Edit", SideEffectLevel.MUTATING);
            final ToolUse writeCall = ToolUse.of("w", TOOL_NAME, Map.of("path", "a"));
            try {
                final Future<Optional<String>> blocked = callers
                        .submit(() -> gate.denialReason(write, writeCall, turnContext("A")));
                assertThat(blockedEntered.await(5, TimeUnit.SECONDS)).isTrue();

                // Another tool in the same scope.
                assertThat(callers.submit(() -> ask(gate, edit, turnContext("A"))).get(5, TimeUnit.SECONDS)).isEmpty();
                // Another scope — through a prompt that does not block (no arguments, so no "'Write(" match).
                assertThat(callers.submit(() -> ask(gate, edit, turnContext("B"))).get(5, TimeUnit.SECONDS)).isEmpty();
                assertThat(blocked.isDone()).isFalse();
            } finally {
                releaseBlocked.countDown();
            }
        }

        @Test
        @DisplayName("a caller interrupted while waiting for another's prompt is refused and remembers nothing")
        void interruptedWaiterIsRefused() throws Exception {
            final BlockingHandler handler = new BlockingHandler(Decision.ALLOW, 1);
            final InMemoryToolApprovalStore store = new InMemoryToolApprovalStore();
            final SideEffectApprovalGate gate = new SideEffectApprovalGate(SideEffectLevel.READ_ONLY, handler, store);
            final Tool write = tool(TOOL_NAME, SideEffectLevel.MUTATING);
            final Future<Optional<String>> asker = callers.submit(() -> ask(gate, write, forkContext("s1")));
            assertThat(handler.entered.await(5, TimeUnit.SECONDS)).isTrue();

            final AtomicReference<Optional<String>> waiterResult = new AtomicReference<>();
            final AtomicBoolean waiterFlag = new AtomicBoolean();
            final Thread waiter = new Thread(() -> {
                waiterResult.set(ask(gate, write, forkContext("s1")));
                waiterFlag.set(Thread.currentThread().isInterrupted());
            });
            waiter.start();
            Thread.sleep(200);
            waiter.interrupt();
            waiter.join(5_000);

            assertThat(waiterResult.get()).hasValueSatisfying(reason -> assertThat(reason).contains("cancelled"));
            assertThat(waiterFlag.get()).isTrue();
            assertThat(store.lookup("session:s1", TOOL_NAME)).isEmpty();

            handler.release.countDown();
            assertThat(asker.get(5, TimeUnit.SECONDS)).isEmpty();
            assertThat(handler.calls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("an asker that throws (RuntimeException or Error) does not strand waiters; the next call asks again")
        void throwingAskerReleasesWaiters() throws Exception {
            for (Throwable failure : List.of(new IllegalStateException("console gone"), new AssertionError("fatal"))) {
                final CountDownLatch entered = new CountDownLatch(1);
                final CountDownLatch fail = new CountDownLatch(1);
                final AtomicInteger calls = new AtomicInteger();
                final AskPromptHandler handler = prompt -> {
                    if (calls.incrementAndGet() == 1) {
                        entered.countDown();
                        try {
                            fail.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        if (failure instanceof Error error) {
                            throw error;
                        }
                        throw (RuntimeException) failure;
                    }
                    return Decision.ALLOW;
                };
                final SideEffectApprovalGate gate = new SideEffectApprovalGate(handler);
                final Tool write = tool(TOOL_NAME, SideEffectLevel.MUTATING);
                final Future<Optional<String>> asker = callers.submit(() -> ask(gate, write, forkContext("s1")));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                final Future<Optional<String>> waiter = callers.submit(() -> ask(gate, write, forkContext("s1")));
                Thread.sleep(200);
                fail.countDown();

                assertThat(waiter.get(5, TimeUnit.SECONDS)).isPresent();
                assertThatThrownBy(() -> asker.get(5, TimeUnit.SECONDS)).hasCause(failure);
                // Nothing was remembered and nothing is left in flight: the next call asks again.
                assertThat(ask(gate, write, forkContext("s1"))).isEmpty();
                assertThat(calls.get()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("a run with no scope is asked every time, concurrently, without sharing")
        void noScopeAlwaysAsks() throws Exception {
            final BlockingHandler handler = new BlockingHandler(Decision.ALLOW, 2);
            final SideEffectApprovalGate gate = new SideEffectApprovalGate(handler);
            final Tool write = tool(TOOL_NAME, SideEffectLevel.MUTATING);
            final Future<Optional<String>> a = callers.submit(() -> ask(gate, write, ToolContext.empty()));
            final Future<Optional<String>> b = callers.submit(() -> ask(gate, write, ToolContext.empty()));
            assertThat(handler.entered.await(5, TimeUnit.SECONDS)).isTrue();
            handler.release.countDown();

            assertThat(a.get(5, TimeUnit.SECONDS)).isEmpty();
            assertThat(b.get(5, TimeUnit.SECONDS)).isEmpty();
            assertThat(handler.calls.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("a caller that wins the slot after the answer was stored does not ask again")
        void winnerRechecksTheStore() throws Exception {
            final CountDownLatch secondLookupStarted = new CountDownLatch(1);
            final CountDownLatch firstDone = new CountDownLatch(1);
            final InMemoryToolApprovalStore backing = new InMemoryToolApprovalStore();
            final AtomicInteger lookups = new AtomicInteger();
            final ToolApprovalStore store = new ToolApprovalStore() {
                @Override
                public Optional<Boolean> lookup(String scopeKey, String toolName) {
                    // The second caller's first lookup misses, then stalls until the first caller has asked,
                    // remembered and left — the window the re-lookup after winning the slot exists for.
                    if ("second".equals(Thread.currentThread().getName()) && lookups.incrementAndGet() == 1) {
                        secondLookupStarted.countDown();
                        try {
                            firstDone.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return Optional.empty();
                    }
                    return backing.lookup(scopeKey, toolName);
                }

                @Override
                public void remember(String scopeKey, String toolName, boolean allowed) {
                    backing.remember(scopeKey, toolName, allowed);
                }

                @Override
                public void revoke(String scopeKey) {
                    backing.revoke(scopeKey);
                }
            };
            final RecordingHandler handler = new RecordingHandler(Decision.ALLOW);
            final SideEffectApprovalGate gate = new SideEffectApprovalGate(SideEffectLevel.READ_ONLY, handler, store);
            final Tool write = tool(TOOL_NAME, SideEffectLevel.MUTATING);
            final AtomicReference<Optional<String>> secondResult = new AtomicReference<>();
            final Thread second = new Thread(() -> secondResult.set(ask(gate, write, forkContext("s1"))), "second");
            second.start();
            assertThat(secondLookupStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(ask(gate, write, forkContext("s1"))).isEmpty();
            firstDone.countDown();
            second.join(5_000);

            assertThat(secondResult.get()).isEmpty();
            assertThat(handler.callCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a fork labelled with its task id is named in the prompt; an unlabelled run's prompt is unchanged")
        void taskIdLabel() {
            final RecordingHandler labelled = new RecordingHandler(Decision.ALLOW);
            final Tool write = tool(TOOL_NAME, SideEffectLevel.MUTATING);
            final ToolContext fork = ToolContext.builder().put(ToolContextKeys.INVOKING_SESSION_ID, SessionId.of("s1"))
                    .put(ToolContextKeys.EXECUTION_ATTRIBUTES_KEY,
                            Map.of(ForkAccess.TASK_ID_ATTRIBUTE, "3fa2c1d0-1111-2222-3333-444455556666"))
                    .build();
            ask(new SideEffectApprovalGate(labelled), write, fork);

            final RecordingHandler plain = new RecordingHandler(Decision.ALLOW);
            ask(new SideEffectApprovalGate(plain), write, forkContext("s1"));

            assertThat(labelled.prompts).singleElement().asString().startsWith("[task 3fa2c1d0] Allow 'Write()'?");
            assertThat(plain.prompts).singleElement().asString().startsWith("Allow 'Write()'?");
            assertThat(labelled.prompts.get(0)).isEqualTo("[task 3fa2c1d0] " + plain.prompts.get(0));
        }
    }
}
