package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.impl.RecentFilesRestoreHook;
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
import at.aimon.core.tools.file.ReadTool;

/**
 * Integration test for design §11.2 scenario E (PostCompact restore E2E).
 *
 * <p>
 * Wires the real {@link DefaultCompactionEngine}, {@link DefaultHookExecutionManager}, {@link DefaultHookRegistry},
 * {@link RecentFilesRestoreHook}, real {@link ReadTool}, and real {@link LocalFileSystem} (under a {@code @TempDir}).
 * A conversation containing five Read {@code tool_use}/{@code tool_result} pairs is compacted; the test verifies that
 * after compaction the memory contains the boundary marker pair followed by a single restored {@code USER} message
 * carrying the actual on-disk contents of every Read-accessed file.
 *
 * <p>
 * The summary LLM call is stubbed (the engine wiring around it is what we exercise — not the LLM provider).
 */
class PostCompactRestoreScenarioETest {

    @TempDir
    Path tempDir;

    private VirtualFileSystem fileSystem;
    private DefaultCompactionEngine engine;
    private DefaultHookRegistry hookRegistry;
    private DefaultHookExecutionManager hookExecutionManager;
    private Environment environment;

    @BeforeEach
    void setUp() {
        fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();

        hookRegistry = new DefaultHookRegistry();
        hookExecutionManager = new DefaultHookExecutionManager();
        environment = Environment.createDefault();

        hookRegistry.register(HookEventType.POST_COMPACT, new RecentFilesRestoreHook(new ReadTool(fileSystem), 5));
        engine = DefaultCompactionEngine.withDefaults(new StubSummaryClient(), new HeuristicTokenEstimator(),
                hookExecutionManager);
    }

    @AfterEach
    void tearDown() {
        if (fileSystem != null) {
            fileSystem.close();
        }
    }

    @Test
    void compactionRestoresAllRecentlyReadFilesAsTrailingUserMessage() throws Exception {
        Map<String, String> files = Map.of("a.txt", "alpha-content", "b.txt", "beta-content", "c.txt", "gamma-content",
                "d.txt", "delta-content", "e.txt", "epsilon-content");
        for (Map.Entry<String, String> e : files.entrySet()) {
            Files.writeString(tempDir.resolve(e.getKey()), e.getValue());
        }

        TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate());
        memory.addUserMessage("please look at these files");
        appendReadCall(memory, "use-1", "a.txt", "alpha-content");
        appendReadCall(memory, "use-2", "b.txt", "beta-content");
        appendReadCall(memory, "use-3", "c.txt", "gamma-content");
        appendReadCall(memory, "use-4", "d.txt", "delta-content");
        appendReadCall(memory, "use-5", "e.txt", "epsilon-content");

        CompactionResult result = engine.compact(CompactionRequest.builder().transcriptBuffer(memory)
                .trigger(CompactionTrigger.AUTO).model(LlmModel.builder().name("test-model").build())
                .hookRegistry(hookRegistry).environment(environment).build());

        assertThat(result.isSuccess()).isTrue();

        List<Message> rebuilt = memory.getMessages();
        // [boundary, summary, restored-files] — the restore hook appends a single user message.
        assertThat(rebuilt).hasSize(3);
        assertThat(rebuilt.get(0).getRole()).isEqualTo(Role.USER);
        assertThat(rebuilt.get(0).getContent()).contains(CompactBoundary.BOUNDARY_OPEN_PREFIX);
        assertThat(rebuilt.get(1).getRole()).isEqualTo(Role.USER);
        assertThat(rebuilt.get(1).getContent()).contains(CompactBoundary.SUMMARY_OPEN_PREFIX)
                .contains("compacted summary");

        Message restored = rebuilt.get(2);
        assertThat(restored.getRole()).isEqualTo(Role.USER);
        String body = restored.getContent();
        assertThat(body).contains("re-attaching recently-read files");
        for (Map.Entry<String, String> e : files.entrySet()) {
            assertThat(body).contains("=== " + e.getKey() + " ===").contains(e.getValue());
        }
    }

    @Test
    void emptyRecentFilesProducesPlainBoundaryAndSummaryOnly() {
        TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate());
        memory.addUserMessage("hello — no files involved");
        memory.addAssistantMessage("hi back");

        CompactionResult result = engine.compact(CompactionRequest.builder().transcriptBuffer(memory)
                .trigger(CompactionTrigger.AUTO).model(LlmModel.builder().name("test-model").build())
                .hookRegistry(hookRegistry).environment(environment).build());

        assertThat(result.isSuccess()).isTrue();
        // No Read tool_uses → restore hook returns success without appending anything.
        assertThat(memory.getMessages()).hasSize(2);
        assertThat(memory.getMessages().get(0).getContent()).contains(CompactBoundary.BOUNDARY_OPEN_PREFIX);
        assertThat(memory.getMessages().get(1).getContent()).contains(CompactBoundary.SUMMARY_OPEN_PREFIX);
    }

    private static void appendReadCall(TranscriptBuffer memory, String useId, String path, String contents) {
        memory.addMessage(Message.assistant("", List.of(ToolUse.of(useId, "Read", Map.of("file_path", path)))));
        memory.addMessage(Message.toolUseResults(List.of(ToolUseResult.success(useId, contents))));
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
