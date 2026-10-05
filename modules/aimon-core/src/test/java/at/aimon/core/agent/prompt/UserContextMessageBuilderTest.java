package at.aimon.core.agent.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentEnvironmentSnapshot;
import at.aimon.core.base.UserLocale;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;

/**
 * Unit tests for {@link UserContextMessageBuilder}.
 *
 * <p>
 * Exercises the synthetic {@code messages[0]} user-context builder that wraps agent-environment facts in
 * {@code <system-reminder>} blocks for the LLM.
 */
class UserContextMessageBuilderTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-04-23T12:34:56Z");

    @Nested
    @DisplayName("build(AgentEnvironmentSnapshot, executionWorkingDirectory)")
    class Build {

        @Test
        @DisplayName("throws NullPointerException when AgentEnvironmentSnapshot is null")
        void nullAgentEnvironmentSnapshotThrows() {
            assertThatThrownBy(() -> UserContextMessageBuilder.build(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("agentEnvironmentSnapshot");
        }

        @Test
        @DisplayName("emits working-directory and current-date reminders in a user-role message")
        void happyPathEmitsRequiredReminders() {
            final AgentEnvironmentSnapshot snapshot = AgentEnvironmentSnapshot.builder().currentDate(FIXED_INSTANT)
                    .userLocale(UserLocale.createDefault()).build();

            final Optional<Message> result = UserContextMessageBuilder.build(snapshot, "/workspace/project");

            assertThat(result).isPresent();
            final Message message = result.get();
            assertThat(message.getRole()).isEqualTo(Role.USER);
            assertThat(message.getContent()).isEqualTo("<system-reminder key=\"working-directory\">\n"
                    + "/workspace/project\n" + "</system-reminder>\n" + "\n"
                    + "<system-reminder key=\"current-date\">\n" + "2026-04-23T12:34:56Z\n" + "</system-reminder>");
        }

        @Test
        @DisplayName("appends extensions verbatim after the core reminders, in iteration order")
        void extensionsAppearedAfterCoreEntries() {
            final Map<String, String> extensions = new LinkedHashMap<>();
            extensions.put("claude-md", "# Project Rules\n- be concise");
            extensions.put("git-branch", "main");

            final AgentEnvironmentSnapshot snapshot = AgentEnvironmentSnapshot.builder().currentDate(FIXED_INSTANT)
                    .userLocale(UserLocale.createDefault()).extensions(extensions).build();

            final Optional<Message> result = UserContextMessageBuilder.build(snapshot, "/ws");

            assertThat(result).isPresent();
            assertThat(result.get().getContent()).isEqualTo("<system-reminder key=\"working-directory\">\n" + "/ws\n"
                    + "</system-reminder>\n" + "\n" + "<system-reminder key=\"current-date\">\n"
                    + "2026-04-23T12:34:56Z\n" + "</system-reminder>\n" + "\n" + "<system-reminder key=\"claude-md\">\n"
                    + "# Project Rules\n" + "- be concise\n" + "</system-reminder>\n" + "\n"
                    + "<system-reminder key=\"git-branch\">\n" + "main\n" + "</system-reminder>");
        }

        @Test
        @DisplayName("without an execution there is no working-directory entry; current-date is still emitted")
        void snapshotAloneHasNoWorkingDirectory() {
            final AgentEnvironmentSnapshot snapshot = AgentEnvironmentSnapshot.builder().currentDate(FIXED_INSTANT)
                    .userLocale(UserLocale.createDefault()).build();

            final Optional<Message> result = UserContextMessageBuilder.build(snapshot);

            assertThat(result).isPresent();
            assertThat(result.get().getContent()).isEqualTo(
                    "<system-reminder key=\"current-date\">\n" + "2026-04-23T12:34:56Z\n" + "</system-reminder>");
            assertThat(result.get().getContent()).doesNotContain("working-directory");
        }

        @Test
        @DisplayName("an execution without a working directory gets no entry — the snapshot's is not a fallback (EE-24)")
        void blankExecutionWorkingDirectoryDoesNotFallBackToTheSnapshot() {
            // An unavailable environment's descriptor has a blank working directory, and nothing stands in for it
            // (execution-environment design §5.1). The snapshot used to: it carried a working directory of its own,
            // collected once per agent, which is why it no longer has one (EE-10).
            final AgentEnvironmentSnapshot snapshot = AgentEnvironmentSnapshot.builder().currentDate(FIXED_INSTANT)
                    .userLocale(UserLocale.createDefault()).build();

            for (final String blank : new String[]{"", "   ", null}) {
                final Optional<Message> result = UserContextMessageBuilder.build(snapshot, blank);

                assertThat(result).isPresent();
                assertThat(result.get().getContent()).as("execution working directory [%s]", blank).isEqualTo(
                        "<system-reminder key=\"current-date\">\n" + "2026-04-23T12:34:56Z\n" + "</system-reminder>");
            }
        }

        @Test
        @DisplayName("skips extension entries whose key is blank; emits only well-formed entries")
        void blankExtensionKeysSkipped() {
            // Note: AgentEnvironmentSnapshot.Builder defensively copies extensions via Map.copyOf, which rejects null
            // values —
            // so the null-value branch in UserContextMessageBuilder is not reachable through the public builder API
            // and is therefore not covered here. The blank-key branch is observable and exercised below.
            final Map<String, String> extensions = new LinkedHashMap<>();
            extensions.put("kept", "value");
            extensions.put("   ", "blank-key-skipped");

            final AgentEnvironmentSnapshot snapshot = AgentEnvironmentSnapshot.builder().currentDate(FIXED_INSTANT)
                    .userLocale(UserLocale.createDefault()).extensions(extensions).build();

            final Optional<Message> result = UserContextMessageBuilder.build(snapshot);

            assertThat(result).isPresent();
            final String body = result.get().getContent();
            assertThat(body).contains("<system-reminder key=\"kept\">");
            assertThat(body).doesNotContain("blank-key-skipped");
        }
    }
}
