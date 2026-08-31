package at.aimon.core.hook.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.compact.InvokedSkillRecord;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.PostCompactContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;

/** Unit tests for {@link InvokedSkillsRestoreHook}. */
class InvokedSkillsRestoreHookTest {

    private HookRegistry hookRegistry;
    private Environment environment;

    @BeforeEach
    void setUp() {
        hookRegistry = new DefaultHookRegistry();
        environment = Environment.createDefault();
    }

    @Test
    void constructorRejectsNonPositiveMaxRecords() {
        assertThatThrownBy(() -> new InvokedSkillsRestoreHook(0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRecords");
        assertThatThrownBy(() -> new InvokedSkillsRestoreHook(-3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executeRejectsNullContext() {
        assertThatThrownBy(() -> new InvokedSkillsRestoreHook().execute(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void emptySnapshotProducesSuccessAndDoesNotTouchMemory() {
        TranscriptBuffer memory = freshMemory();
        memory.addUserMessage("pre-existing");
        int before = memory.size();

        HookResult result = new InvokedSkillsRestoreHook().execute(contextFor(memory, List.of()));

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(memory.size()).isEqualTo(before);
    }

    @Test
    void appendsBulletedListInOrderUnderCap() {
        TranscriptBuffer memory = freshMemory();

        HookResult result = new InvokedSkillsRestoreHook(10).execute(contextFor(memory,
                List.of(InvokedSkillRecord.of("commit"), InvokedSkillRecord.of("summarize", "the meeting notes"),
                        InvokedSkillRecord.of("review", "src/main/java/Foo.java"))));

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        Message appended = memory.getLastMessage();
        assertThat(appended.getRole()).isEqualTo(Role.USER);
        // Names appear in order; only entries with args carry the args= segment.
        assertThat(appended.getContent()).contains("[System note: skills invoked before conversation compaction]")
                .contains("\n- commit\n").contains("- summarize args=\"the meeting notes\"")
                .contains("- review args=\"src/main/java/Foo.java\"");
        // Order: commit precedes summarize precedes review in the body.
        int commitIdx = appended.getContent().indexOf("- commit");
        int summarizeIdx = appended.getContent().indexOf("- summarize");
        int reviewIdx = appended.getContent().indexOf("- review");
        assertThat(commitIdx).isLessThan(summarizeIdx);
        assertThat(summarizeIdx).isLessThan(reviewIdx);
    }

    @Test
    void capKeepsMostRecentRecords() {
        TranscriptBuffer memory = freshMemory();

        new InvokedSkillsRestoreHook(2).execute(contextFor(memory,
                List.of(InvokedSkillRecord.of("a"), InvokedSkillRecord.of("b"), InvokedSkillRecord.of("c"))));

        String body = memory.getLastMessage().getContent();
        assertThat(body).contains("- b").contains("- c").doesNotContain("- a");
    }

    @Test
    void truncatesLongArgsWithEllipsis() {
        TranscriptBuffer memory = freshMemory();
        String longArgs = "x".repeat(InvokedSkillsRestoreHook.ARGS_PREVIEW_MAX_LENGTH + 25);

        new InvokedSkillsRestoreHook().execute(contextFor(memory, List.of(InvokedSkillRecord.of("commit", longArgs))));

        String body = memory.getLastMessage().getContent();
        // The truncated preview is exactly ARGS_PREVIEW_MAX_LENGTH chars + ellipsis.
        assertThat(body).contains("…").doesNotContain(longArgs);
        assertThat(body).contains("x".repeat(InvokedSkillsRestoreHook.ARGS_PREVIEW_MAX_LENGTH));
    }

    @Test
    void doesNotTouchMemoryWhenAllRecordsCappedToZero() {
        // Sanity: with maxRecords=1 and a snapshot of size 1, we still append exactly one entry.
        TranscriptBuffer memory = freshMemory();

        new InvokedSkillsRestoreHook(1).execute(contextFor(memory, List.of(InvokedSkillRecord.of("commit"))));

        assertThat(memory.getLastMessage().getContent()).contains("- commit");
    }

    private TranscriptBuffer freshMemory() {
        return new TranscriptBuffer(SessionId.generate());
    }

    private PostCompactContext contextFor(TranscriptBuffer memory, List<InvokedSkillRecord> invokedSkills) {
        Instant now = Instant.now();
        CompactionMetadata metadata = CompactionMetadata.builder().trigger(CompactionTrigger.AUTO).startedAt(now)
                .completedAt(now).build();
        return PostCompactContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("test")
                .hookRegistry(hookRegistry).environment(environment).trigger(CompactionTrigger.AUTO)
                .compactionMetadata(metadata).compactSummary("summary").transcriptBuffer(memory)
                .invokedSkills(invokedSkills).timestamp(now).build();
    }
}
