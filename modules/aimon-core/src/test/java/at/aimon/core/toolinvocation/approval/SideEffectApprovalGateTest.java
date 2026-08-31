package at.aimon.core.toolinvocation.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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
}
