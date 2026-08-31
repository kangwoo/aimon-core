package at.aimon.core.hook.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.PostCompactContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;

class RecentFilesRestoreHookTest {

    private RecordingReadTool readTool;
    private HookRegistry hookRegistry;
    private Environment environment;

    @BeforeEach
    void setUp() {
        readTool = new RecordingReadTool();
        hookRegistry = new DefaultHookRegistry();
        environment = Environment.createDefault();
    }

    @Test
    void constructorRejectsNullToolAndNonPositiveMaxFiles() {
        assertThatThrownBy(() -> new RecentFilesRestoreHook(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecentFilesRestoreHook(readTool, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecentFilesRestoreHook(readTool, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executeRejectsNullContext() {
        RecentFilesRestoreHook hook = new RecentFilesRestoreHook(readTool);
        assertThatThrownBy(() -> hook.execute(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void emptyRecentFilesYieldsSuccessAndDoesNotTouchMemory() {
        TranscriptBuffer memory = freshMemory();
        memory.addUserMessage("pre-existing");
        int before = memory.size();

        HookResult result = new RecentFilesRestoreHook(readTool).execute(contextFor(memory, List.of()));

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(memory.size()).isEqualTo(before);
        assertThat(readTool.invocations).isEmpty();
    }

    @Test
    void appendsRecentFilesUpToCap() {
        TranscriptBuffer memory = freshMemory();
        readTool.responses.put("/a", "alpha-content");
        readTool.responses.put("/b", "beta-content");
        readTool.responses.put("/c", "gamma-content");

        HookResult result = new RecentFilesRestoreHook(readTool, 2)
                .execute(contextFor(memory, List.of("/a", "/b", "/c")));

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(readTool.invocations).containsExactly("/b", "/c");

        Message appended = memory.getLastMessage();
        assertThat(appended.getContent()).contains("[System note: re-attaching").contains("=== /b ===")
                .contains("beta-content").contains("=== /c ===").contains("gamma-content").doesNotContain("=== /a ===");
    }

    @Test
    void readErrorsAreSkippedAndDoNotAbortRestore() {
        TranscriptBuffer memory = freshMemory();
        readTool.responses.put("/a", "alpha");
        readTool.errorPaths.add("/b");
        readTool.responses.put("/c", "gamma");

        HookResult result = new RecentFilesRestoreHook(readTool).execute(contextFor(memory, List.of("/a", "/b", "/c")));

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(readTool.invocations).containsExactly("/a", "/b", "/c");

        Message appended = memory.getLastMessage();
        assertThat(appended.getContent()).contains("=== /a ===").contains("alpha").contains("=== /c ===")
                .contains("gamma").doesNotContain("=== /b ===");
    }

    @Test
    void readExceptionsAreSwallowed() {
        TranscriptBuffer memory = freshMemory();
        readTool.responses.put("/a", "alpha");
        readTool.exceptionPaths.add("/b");

        HookResult result = new RecentFilesRestoreHook(readTool).execute(contextFor(memory, List.of("/a", "/b")));

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        Message appended = memory.getLastMessage();
        assertThat(appended.getContent()).contains("=== /a ===").doesNotContain("=== /b ===");
    }

    @Test
    void allFailuresMeansNoMessageAppended() {
        TranscriptBuffer memory = freshMemory();
        memory.addUserMessage("baseline");
        int before = memory.size();
        readTool.errorPaths.add("/a");
        readTool.errorPaths.add("/b");

        HookResult result = new RecentFilesRestoreHook(readTool).execute(contextFor(memory, List.of("/a", "/b")));

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(memory.size()).isEqualTo(before);
    }

    private TranscriptBuffer freshMemory() {
        return new TranscriptBuffer(SessionId.generate());
    }

    private PostCompactContext contextFor(TranscriptBuffer memory, List<String> recentPaths) {
        Instant now = Instant.now();
        CompactionMetadata metadata = CompactionMetadata.builder().trigger(CompactionTrigger.AUTO).startedAt(now)
                .completedAt(now).build();
        return PostCompactContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("test")
                .hookRegistry(hookRegistry).environment(environment).trigger(CompactionTrigger.AUTO)
                .compactionMetadata(metadata).compactSummary("summary").transcriptBuffer(memory)
                .recentReadFilePaths(recentPaths).timestamp(now).build();
    }

    private static final class RecordingReadTool implements Tool {
        private final Map<String, String> responses = new java.util.LinkedHashMap<>();
        private final List<String> invocations = new ArrayList<>();
        private final java.util.Set<String> errorPaths = new java.util.HashSet<>();
        private final java.util.Set<String> exceptionPaths = new java.util.HashSet<>();

        @Override
        public ToolDefinition getDefinition() {
            return ToolDefinition.of("Read", "stub", Map.of("type", "object"));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            String path = input.getRequiredString("file_path");
            invocations.add(path);
            if (exceptionPaths.contains(path)) {
                throw new IllegalStateException("read blew up: " + path);
            }
            if (errorPaths.contains(path)) {
                return ToolResult.error("denied: " + path);
            }
            String body = responses.getOrDefault(path, "<missing>");
            return ToolResult.success(body);
        }
    }
}
