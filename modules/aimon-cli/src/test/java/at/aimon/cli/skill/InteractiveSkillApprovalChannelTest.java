package at.aimon.cli.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.cli.config.CliSettings;
import at.aimon.cli.repl.OutputFormatter;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.agent.InMemoryAgentApprovalStore;
import at.aimon.core.skill.policy.pending.PendingSkillRequest;
import at.aimon.core.skill.policy.session.InMemorySessionApprovalStore;
import at.aimon.core.skill.policy.session.SessionApprovalStore;

/**
 * SK-11.6 unit coverage for {@link InteractiveSkillApprovalChannel}: verifies that the channel persists per-skill
 * decisions to the store matching the scope the user chose, via prompts driven on a JLine {@link Terminal}, and that
 * the documented fallback paths (no terminal bound, mid-prompt abort, no session bound) leave the stores in a safe
 * state.
 */
@DisplayName("InteractiveSkillApprovalChannel (SK-11.6)")
class InteractiveSkillApprovalChannelTest {

    private CliSettings settings;
    private OutputFormatter formatter;
    private SessionApprovalStore sessionStore;
    private AgentApprovalStore store;
    private AgentRuntimeId agentRuntimeId;
    private SessionId sessionId;

    @BeforeEach
    void setUp() {
        settings = new CliSettings();
        settings.setColorOutput(false);
        formatter = new OutputFormatter(settings);
        sessionStore = new InMemorySessionApprovalStore();
        store = new InMemoryAgentApprovalStore();
        agentRuntimeId = AgentRuntimeId.of("agent:test-1");
        sessionId = SessionId.of("conv-1");
    }

    @AfterEach
    void tearDown() {
        // Nothing to clean — terminals are scoped per test.
    }

    @Test
    @DisplayName("requestApproval throws IllegalStateException when no LineReader is bound (scanner falls back to suspend)")
    void noLineReaderBoundThrowsIllegalStateExceptionForScannerFallback() {
        final InteractiveSkillApprovalChannel channel = newChannel();

        assertThatThrownBy(() -> channel.requestApproval(List.of(pending("read-config")), agentRuntimeId, sessionId))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("No JLine LineReader");

        // Nothing must be persisted on the fallback path; the SK-11.4 suspend flow handles it instead.
        assertThat(sessionStore.get(sessionId, "read-config")).isEmpty();
        assertThat(store.get(agentRuntimeId, "read-config")).isEmpty();
    }

    @Test
    @DisplayName("y allows for this conversation only — the agent-wide store is untouched")
    void yesAnswerPersistsConversationScopedAllow() throws IOException {
        try (Terminal terminal = newDumbTerminal("y\n")) {
            final InteractiveSkillApprovalChannel channel = newChannel();
            channel.bindLineReader(newLineReader(terminal));

            channel.requestApproval(List.of(pending("read-config")), agentRuntimeId, sessionId);

            assertThat(sessionStore.get(sessionId, "read-config")).hasValue(SkillInvocationDecision.ALLOW);
            // The point of the change: a plain "yes" no longer reaches the agent's other sessions.
            assertThat(store.get(agentRuntimeId, "read-config")).isEmpty();
        }
    }

    @Test
    @DisplayName("a allows for the whole agent — the broad answer must be asked for explicitly")
    void alwaysAnswerPersistsAgentScopedAllow() throws IOException {
        try (Terminal terminal = newDumbTerminal("a\n")) {
            final InteractiveSkillApprovalChannel channel = newChannel();
            channel.bindLineReader(newLineReader(terminal));

            channel.requestApproval(List.of(pending("read-config")), agentRuntimeId, sessionId);

            assertThat(store.get(agentRuntimeId, "read-config")).hasValue(SkillInvocationDecision.ALLOW);
            assertThat(sessionStore.get(sessionId, "read-config")).isEmpty();
        }
    }

    @Test
    @DisplayName("blank or 'n' answer fails closed to DENY, scoped to this conversation")
    void blankAndNoAnswersPersistConversationScopedDeny() throws IOException {
        try (Terminal terminal = newDumbTerminal("\nn\n")) {
            final InteractiveSkillApprovalChannel channel = newChannel();
            channel.bindLineReader(newLineReader(terminal));

            channel.requestApproval(List.of(pending("alpha"), pending("beta")), agentRuntimeId, sessionId);

            assertThat(sessionStore.get(sessionId, "alpha")).hasValue(SkillInvocationDecision.DENY);
            assertThat(sessionStore.get(sessionId, "beta")).hasValue(SkillInvocationDecision.DENY);
            // A refusal here must not harden into a standing agent-wide block.
            assertThat(store.get(agentRuntimeId, "alpha")).isEmpty();
            assertThat(store.get(agentRuntimeId, "beta")).isEmpty();
        }
    }

    @Test
    @DisplayName("duplicate skill names share a single prompt and a single persisted decision")
    void duplicateSkillsAreDeduplicated() throws IOException {
        // Only one "y" is provided — if dedup is broken, the second prompt would block waiting for input and the
        // test would hang. The successful return without timeout is itself an assertion of dedup.
        try (Terminal terminal = newDumbTerminal("y\n")) {
            final InteractiveSkillApprovalChannel channel = newChannel();
            channel.bindLineReader(newLineReader(terminal));

            channel.requestApproval(List.of(pending("dup", "args-1"), pending("dup", "args-2")), agentRuntimeId,
                    sessionId);

            assertThat(sessionStore.get(sessionId, "dup")).hasValue(SkillInvocationDecision.ALLOW);
        }
    }

    @Test
    @DisplayName("EOF mid-prompt denies remaining skills for this conversation only (fail-closed, not fail-forever)")
    void eofMidPromptDeniesRemainingSkillsInConversationScope() throws IOException {
        // Provide only the first answer (allow alpha) then EOF — the second skill must end up DENY.
        try (Terminal terminal = newDumbTerminal("y\n")) {
            final InteractiveSkillApprovalChannel channel = newChannel();
            channel.bindLineReader(newLineReader(terminal));

            channel.requestApproval(List.of(pending("alpha"), pending("beta")), agentRuntimeId, sessionId);

            assertThat(sessionStore.get(sessionId, "alpha")).hasValue(SkillInvocationDecision.ALLOW);
            assertThat(sessionStore.get(sessionId, "beta")).hasValue(SkillInvocationDecision.DENY);
            // One Ctrl+C must not deny the skill in every future session of this agent.
            assertThat(store.get(agentRuntimeId, "beta")).isEmpty();
        }
    }

    @Test
    @DisplayName("an agent-scoped answer given before an abort survives; only the unanswered ones are denied")
    void agentScopedAnswerBeforeAbortIsNotOverwritten() throws IOException {
        try (Terminal terminal = newDumbTerminal("a\n")) {
            final InteractiveSkillApprovalChannel channel = newChannel();
            channel.bindLineReader(newLineReader(terminal));

            channel.requestApproval(List.of(pending("alpha"), pending("beta")), agentRuntimeId, sessionId);

            assertThat(store.get(agentRuntimeId, "alpha")).hasValue(SkillInvocationDecision.ALLOW);
            assertThat(sessionStore.get(sessionId, "beta")).hasValue(SkillInvocationDecision.DENY);
        }
    }

    @Test
    @DisplayName("with no session bound, a session-scoped answer falls back to the agent store")
    void withoutConversationIdAnswersFallBackToAgentScope() throws IOException {
        // Dropping the write instead would re-ask the same question next turn: the scanner does not re-run the policy
        // after the channel returns, so an unwritten answer is an invisible failure rather than a safe one.
        try (Terminal terminal = newDumbTerminal("y\n")) {
            final InteractiveSkillApprovalChannel channel = newChannel();
            channel.bindLineReader(newLineReader(terminal));

            channel.requestApproval(List.of(pending("read-config")), agentRuntimeId, null);

            assertThat(store.get(agentRuntimeId, "read-config")).hasValue(SkillInvocationDecision.ALLOW);
        }
    }

    @Test
    @DisplayName("the deprecated two-arg entry point behaves as the no-conversation case")
    void twoArgEntryPointFallsBackToAgentScope() throws IOException {
        try (Terminal terminal = newDumbTerminal("y\n")) {
            final InteractiveSkillApprovalChannel channel = newChannel();
            channel.bindLineReader(newLineReader(terminal));

            channel.requestApproval(List.of(pending("read-config")), agentRuntimeId);

            assertThat(store.get(agentRuntimeId, "read-config")).hasValue(SkillInvocationDecision.ALLOW);
        }
    }

    @Test
    @DisplayName("unbindLineReader restores the no-reader fallback path")
    void unbindRestoresNoLineReaderFallback() throws IOException {
        try (Terminal terminal = newDumbTerminal("y\n")) {
            final InteractiveSkillApprovalChannel channel = newChannel();
            channel.bindLineReader(newLineReader(terminal));
            channel.unbindLineReader();

            assertThatThrownBy(
                    () -> channel.requestApproval(List.of(pending("after-unbind")), agentRuntimeId, sessionId))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("empty pendingRequests list is rejected up front")
    void emptyPendingRequestsRejected() throws IOException {
        try (Terminal terminal = newDumbTerminal("")) {
            final InteractiveSkillApprovalChannel channel = newChannel();
            channel.bindLineReader(newLineReader(terminal));

            assertThatThrownBy(() -> channel.requestApproval(List.of(), agentRuntimeId, sessionId))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void constructorRejectsNullStores() {
        assertThatThrownBy(() -> new InteractiveSkillApprovalChannel(null, store, formatter))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new InteractiveSkillApprovalChannel(sessionStore, null, formatter))
                .isInstanceOf(NullPointerException.class);
    }

    private InteractiveSkillApprovalChannel newChannel() {
        return new InteractiveSkillApprovalChannel(sessionStore, store, formatter);
    }

    private static PendingSkillRequest pending(String skillName) {
        return PendingSkillRequest.builder().toolUseId("tool-" + skillName).skillName(skillName).args("").build();
    }

    private static PendingSkillRequest pending(String skillName, String args) {
        return PendingSkillRequest.builder().toolUseId("tool-" + skillName + "-" + args).skillName(skillName).args(args)
                .build();
    }

    private static Terminal newDumbTerminal(String input) throws IOException {
        // A dumb terminal driven from a fixed input stream — LineReader.readLine() consumes one line per call.
        final InputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        return TerminalBuilder.builder().system(false).streams(in, new ByteArrayOutputStream()).dumb(true).build();
    }

    private static LineReader newLineReader(Terminal terminal) {
        // Stand-in for the REPL's bound reader. The channel reads from whatever reader callers bind, mirroring
        // how ReplSession hands over its main-prompt reader.
        return LineReaderBuilder.builder().terminal(terminal).build();
    }
}
