package at.aimon.core.command.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.CommandType;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.session.InMemorySessionApprovalStore;
import at.aimon.core.skill.policy.session.SessionApprovalStore;

/** Unit tests for {@link ClearCommand}. */
class ClearCommandTest {

    private static final SessionId SESSION = SessionId.of("conv-1");

    private SessionApprovalStore sessionApprovals;

    @BeforeEach
    void setUp() {
        sessionApprovals = new InMemorySessionApprovalStore();
    }

    @Test
    void hasCorrectNameDescriptionAndType() {
        ClearCommand command = new ClearCommand();

        assertThat(command.getName()).isEqualTo("clear");
        assertThat(command.getMetadata().getDescription()).hasValue("Clear conversation history");
        assertThat(command.getType()).isEqualTo(CommandType.SYSTEM);
    }

    @Test
    void executeRejectsNullContext() {
        ClearCommand command = new ClearCommand();

        assertThatThrownBy(() -> command.execute(null, DirectCommandExecutionRequest.of("")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void executeRejectsNullRequest() {
        ClearCommand command = new ClearCommand();

        assertThatThrownBy(() -> command.execute(createContext(SESSION), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void reportsNothingToClearWhenNoPreviousConversation() {
        ClearCommand command = new ClearCommand(sessionApprovals);
        sessionApprovals.put(SESSION, "commit", SkillInvocationDecision.ALLOW);

        CommandExecutionResult result = command.execute(createContext(SESSION), DirectCommandExecutionRequest.of(""));

        assertThat(result.getResponse()).contains("No active conversation to clear.");
        // Nothing was cleared, so the approval must survive.
        assertThat(sessionApprovals.get(SESSION, "commit")).contains(SkillInvocationDecision.ALLOW);
    }

    @Test
    void clearsHistoryAndReportsMessageCount() {
        ClearCommand command = new ClearCommand();
        TranscriptBuffer memory = new TranscriptBuffer(SESSION);
        memory.addMessage(Message.user("hello"));

        CommandExecutionResult result = command.execute(createContext(memory), requestWith(
                SessionSnapshot.of(SESSION, null, List.of(Message.user("hello"), Message.assistant("hi")))));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("Conversation cleared. Removed 2 message(s).");
        assertThat(memory.getMessages()).isEmpty();
    }

    @Test
    void dropsTheConversationsApprovalsAlongsideTheHistory() {
        sessionApprovals.put(SESSION, "commit", SkillInvocationDecision.ALLOW);
        sessionApprovals.put(SESSION, "deploy", SkillInvocationDecision.DENY);

        ClearCommand command = new ClearCommand(sessionApprovals);
        CommandExecutionResult result = command.execute(createContext(SESSION),
                requestWith(SessionSnapshot.of(SESSION)));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("Skill approvals granted in this session were dropped.");
        assertThat(sessionApprovals.get(SESSION, "commit")).isEmpty();
        assertThat(sessionApprovals.get(SESSION, "deploy")).isEmpty();
    }

    @Test
    void leavesOtherConversationsApprovalsUntouched() {
        SessionId bystander = SessionId.of("conv-2");
        sessionApprovals.put(SESSION, "commit", SkillInvocationDecision.ALLOW);
        sessionApprovals.put(bystander, "commit", SkillInvocationDecision.ALLOW);

        new ClearCommand(sessionApprovals).execute(createContext(SESSION), requestWith(SessionSnapshot.of(SESSION)));

        assertThat(sessionApprovals.get(SESSION, "commit")).isEmpty();
        assertThat(sessionApprovals.get(bystander, "commit")).contains(SkillInvocationDecision.ALLOW);
    }

    @Test
    void withoutAnApprovalStoreTheMessageDoesNotClaimApprovalsWereDropped() {
        CommandExecutionResult result = new ClearCommand().execute(createContext(SESSION),
                requestWith(SessionSnapshot.of(SESSION)));

        assertThat(result.getResponse()).contains("Conversation cleared.").doesNotContain("Skill approvals");
    }

    private static DirectCommandExecutionRequest requestWith(SessionSnapshot snapshot) {
        return DirectCommandExecutionRequest.builder().arguments("").previousSnapshot(snapshot).build();
    }

    private CommandExecutionContext createContext(SessionId sessionId) {
        return createContext(new TranscriptBuffer(sessionId));
    }

    private CommandExecutionContext createContext(TranscriptBuffer memory) {
        return CommandExecutionContext.builder().command(new ClearCommand())
                .defaultModel(LlmModel.builder().name("test").build()).toolRegistry(new DefaultToolRegistry())
                .transcriptBuffer(memory).build();
    }
}
