package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.impl.InvokedSkillsRestoreHook;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.token.HeuristicTokenEstimator;

/**
 * Integration test for SK-12 (compaction preserves invoked skill records E2E).
 *
 * <p>
 * Wires the real {@link DefaultCompactionEngine}, {@link DefaultHookExecutionManager}, {@link DefaultHookRegistry}, and
 * {@link InvokedSkillsRestoreHook}. A session containing several {@code Skill} tool_use/tool_result pairs is
 * compacted; the test verifies that after compaction the memory contains the boundary marker pair followed by a single
 * restored {@code USER} message that lists the invoked skills (in occurrence order, dedup-with-recency).
 *
 * <p>
 * The summary LLM call is stubbed (the engine wiring around it is what we exercise — not the LLM provider).
 */
class PostCompactSkillRestoreScenarioTest {

    private DefaultCompactionEngine engine;
    private DefaultHookRegistry hookRegistry;
    private DefaultHookExecutionManager hookExecutionManager;
    private Environment environment;

    @BeforeEach
    void setUp() {
        hookRegistry = new DefaultHookRegistry();
        hookExecutionManager = new DefaultHookExecutionManager();
        environment = Environment.createDefault();

        hookRegistry.register(HookEventType.POST_COMPACT, new InvokedSkillsRestoreHook(10));
        engine = DefaultCompactionEngine.withDefaults(new StubSummaryClient(), new HeuristicTokenEstimator(),
                hookExecutionManager);
    }

    @Test
    void compactionRestoresInvokedSkillsAsTrailingUserMessage() {
        TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate());
        memory.addUserMessage("please run a few skills");
        appendSkillCall(memory, "use-1", "commit", "--scope=feat");
        appendSkillCall(memory, "use-2", "summarize", "the meeting notes");
        appendSkillCall(memory, "use-3", "review", "src/main/java/Foo.java");

        CompactionResult result = engine.compact(CompactionRequest.builder().transcriptBuffer(memory)
                .trigger(CompactionTrigger.AUTO).model(LlmModel.builder().name("test-model").build())
                .hookRegistry(hookRegistry).environment(environment).build());

        assertThat(result.isSuccess()).isTrue();

        List<Message> rebuilt = memory.getMessages();
        // [boundary, summary, restored-skills] — the restore hook appends a single user message.
        assertThat(rebuilt).hasSize(3);
        assertThat(rebuilt.get(0).getRole()).isEqualTo(Role.USER);
        assertThat(rebuilt.get(0).getContent()).contains(CompactBoundary.BOUNDARY_OPEN_PREFIX);
        assertThat(rebuilt.get(1).getRole()).isEqualTo(Role.USER);
        assertThat(rebuilt.get(1).getContent()).contains(CompactBoundary.SUMMARY_OPEN_PREFIX)
                .contains("compacted summary");

        Message restored = rebuilt.get(2);
        assertThat(restored.getRole()).isEqualTo(Role.USER);
        String body = restored.getContent();
        assertThat(body).contains("[System note: skills invoked before conversation compaction]")
                .contains("- commit args=\"--scope=feat\"").contains("- summarize args=\"the meeting notes\"")
                .contains("- review args=\"src/main/java/Foo.java\"");

        // Order: commit precedes summarize precedes review in the body.
        int commitIdx = body.indexOf("- commit");
        int summarizeIdx = body.indexOf("- summarize");
        int reviewIdx = body.indexOf("- review");
        assertThat(commitIdx).isLessThan(summarizeIdx);
        assertThat(summarizeIdx).isLessThan(reviewIdx);
    }

    @Test
    void duplicateSkillInvocationsAreCollapsedToMostRecentPosition() {
        TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate());
        memory.addUserMessage("a few duplicates please");
        appendSkillCall(memory, "use-1", "commit", "--scope=feat");
        appendSkillCall(memory, "use-2", "summarize", "notes");
        // Re-invoking the same (name, args) pair must move it to the most-recent position, not duplicate it.
        appendSkillCall(memory, "use-3", "commit", "--scope=feat");

        CompactionResult result = engine.compact(CompactionRequest.builder().transcriptBuffer(memory)
                .trigger(CompactionTrigger.AUTO).model(LlmModel.builder().name("test-model").build())
                .hookRegistry(hookRegistry).environment(environment).build());

        assertThat(result.isSuccess()).isTrue();

        Message restored = memory.getMessages().get(2);
        String body = restored.getContent();
        // Only one "- commit" bullet — dedup collapses to most recent.
        assertThat(body.split("- commit", -1)).hasSize(2);
        // commit now appears AFTER summarize because it was re-invoked last.
        assertThat(body.indexOf("- summarize")).isLessThan(body.indexOf("- commit"));
    }

    @Test
    void emptyInvokedSkillsProducesPlainBoundaryAndSummaryOnly() {
        TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate());
        memory.addUserMessage("hello — no skills involved");
        memory.addAssistantMessage("hi back");

        CompactionResult result = engine.compact(CompactionRequest.builder().transcriptBuffer(memory)
                .trigger(CompactionTrigger.AUTO).model(LlmModel.builder().name("test-model").build())
                .hookRegistry(hookRegistry).environment(environment).build());

        assertThat(result.isSuccess()).isTrue();
        // No Skill tool_uses → restore hook returns success without appending anything.
        assertThat(memory.getMessages()).hasSize(2);
        assertThat(memory.getMessages().get(0).getContent()).contains(CompactBoundary.BOUNDARY_OPEN_PREFIX);
        assertThat(memory.getMessages().get(1).getContent()).contains(CompactBoundary.SUMMARY_OPEN_PREFIX);
    }

    private static void appendSkillCall(TranscriptBuffer memory, String useId, String skill, String args) {
        memory.addMessage(
                Message.assistant("", List.of(ToolUse.of(useId, "Skill", Map.of("skill", skill, "args", args)))));
        memory.addMessage(Message.toolUseResults(List.of(ToolUseResult.success(useId, "ok"))));
    }

    private static final class StubSummaryClient implements LlmClient {
        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            return LlmResponse.text("compacted summary");
        }

        @Override
        public String getProviderName() {
            return "stub";
        }
    }
}
