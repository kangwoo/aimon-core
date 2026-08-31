package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUseResult;

@DisplayName("TimeBasedMicrocompact Tests")
class TimeBasedMicrocompactTest {

    private static final Instant T0 = Instant.parse("2026-04-24T10:00:00Z");

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("Rejects null maxAge")
        void rejectsNullMaxAge() {
            assertThatThrownBy(() -> new TimeBasedMicrocompact(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("maxAge");
        }

        @Test
        @DisplayName("Rejects zero maxAge")
        void rejectsZeroMaxAge() {
            assertThatThrownBy(() -> new TimeBasedMicrocompact(Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be positive");
        }

        @Test
        @DisplayName("Rejects negative maxAge")
        void rejectsNegativeMaxAge() {
            assertThatThrownBy(() -> new TimeBasedMicrocompact(Duration.ofMinutes(-1)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be positive");
        }

        @Test
        @DisplayName("Rejects negative keepRecent")
        void rejectsNegativeKeepRecent() {
            assertThatThrownBy(() -> new TimeBasedMicrocompact(Duration.ofMinutes(5), -1))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("keepRecent");
        }

        @Test
        @DisplayName("Rejects null clock")
        void rejectsNullClock() {
            assertThatThrownBy(() -> new TimeBasedMicrocompact(Duration.ofMinutes(5), 0, null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("clock");
        }
    }

    @Nested
    @DisplayName("compact()")
    class Compact {

        @Test
        @DisplayName("Empty memory returns 0")
        void emptyMemoryReturnsZero() {
            TranscriptBuffer memory = newMemory(T0);
            TimeBasedMicrocompact pass = new TimeBasedMicrocompact(Duration.ofMinutes(1), 0, fixed(T0));

            assertThat(pass.compact(memory)).isZero();
        }

        @Test
        @DisplayName("Memory with no tool messages returns 0")
        void noToolMessagesReturnsZero() {
            MutableClock clock = new MutableClock(T0);
            TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate(), null, List.of(), clock);
            memory.addUserMessage("hi");
            memory.addAssistantMessage("hello");

            clock.set(T0.plus(Duration.ofHours(1)));
            TimeBasedMicrocompact pass = new TimeBasedMicrocompact(Duration.ofMinutes(1), 0, clock);

            assertThat(pass.compact(memory)).isZero();
            assertThat(memory.getMessages().get(0).getContent()).isEqualTo("hi");
            assertThat(memory.getMessages().get(1).getContent()).isEqualTo("hello");
        }

        @Test
        @DisplayName("Tool result older than maxAge is replaced with placeholder")
        void staleToolResultIsCleared() {
            MutableClock clock = new MutableClock(T0);
            TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate(), null, List.of(), clock);
            memory.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("call_1", "huge stale body"))));

            clock.set(T0.plus(Duration.ofMinutes(10)));
            TimeBasedMicrocompact pass = new TimeBasedMicrocompact(Duration.ofMinutes(5), 0, clock);

            int cleared = pass.compact(memory);

            assertThat(cleared).isEqualTo(1);
            ToolUseResult result = memory.getMessages().get(0).getToolUseResults().get(0);
            assertThat(result.getContent()).isEqualTo(TimeBasedMicrocompact.CLEARED_PLACEHOLDER);
            assertThat(result.getToolUseId()).isEqualTo("call_1");
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Tool result younger than maxAge is preserved")
        void freshToolResultPreserved() {
            MutableClock clock = new MutableClock(T0);
            TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate(), null, List.of(), clock);
            memory.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("call_1", "fresh body"))));

            clock.set(T0.plus(Duration.ofMinutes(1)));
            TimeBasedMicrocompact pass = new TimeBasedMicrocompact(Duration.ofMinutes(5), 0, clock);

            assertThat(pass.compact(memory)).isZero();
            assertThat(memory.getMessages().get(0).getToolUseResults().get(0).getContent()).isEqualTo("fresh body");
        }

        @Test
        @DisplayName("Error flag is preserved when content is cleared")
        void errorFlagPreserved() {
            MutableClock clock = new MutableClock(T0);
            TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate(), null, List.of(), clock);
            memory.addMessage(Message.toolUseResults(List.of(ToolUseResult.error("call_1", "boom"))));

            clock.set(T0.plus(Duration.ofMinutes(10)));
            TimeBasedMicrocompact pass = new TimeBasedMicrocompact(Duration.ofMinutes(5), 0, clock);

            pass.compact(memory);

            ToolUseResult result = memory.getMessages().get(0).getToolUseResults().get(0);
            assertThat(result.getContent()).isEqualTo(TimeBasedMicrocompact.CLEARED_PLACEHOLDER);
            assertThat(result.isError()).isTrue();
        }

        @Test
        @DisplayName("keepRecent retains the most recent N tool messages verbatim")
        void keepRecentRetainsTail() {
            MutableClock clock = new MutableClock(T0);
            TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate(), null, List.of(), clock);

            clock.set(T0);
            memory.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("call_1", "first"))));
            clock.set(T0.plus(Duration.ofMinutes(1)));
            memory.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("call_2", "second"))));
            clock.set(T0.plus(Duration.ofMinutes(2)));
            memory.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("call_3", "third"))));

            // 5 minutes later — all three are stale, but keepRecent=2 should retain the last two.
            clock.set(T0.plus(Duration.ofMinutes(7)));
            TimeBasedMicrocompact pass = new TimeBasedMicrocompact(Duration.ofMinutes(3), 2, clock);

            int cleared = pass.compact(memory);

            assertThat(cleared).isEqualTo(1);
            assertThat(memory.getMessages().get(0).getToolUseResults().get(0).getContent())
                    .isEqualTo(TimeBasedMicrocompact.CLEARED_PLACEHOLDER);
            assertThat(memory.getMessages().get(1).getToolUseResults().get(0).getContent()).isEqualTo("second");
            assertThat(memory.getMessages().get(2).getToolUseResults().get(0).getContent()).isEqualTo("third");
        }

        @Test
        @DisplayName("Idempotent — re-running the pass does not re-clear or change counts")
        void idempotent() {
            MutableClock clock = new MutableClock(T0);
            TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate(), null, List.of(), clock);
            memory.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("call_1", "stale"))));

            clock.set(T0.plus(Duration.ofMinutes(10)));
            TimeBasedMicrocompact pass = new TimeBasedMicrocompact(Duration.ofMinutes(5), 0, clock);

            assertThat(pass.compact(memory)).isEqualTo(1);
            assertThat(pass.compact(memory)).isZero();
            assertThat(memory.getMessages().get(0).getToolUseResults().get(0).getContent())
                    .isEqualTo(TimeBasedMicrocompact.CLEARED_PLACEHOLDER);
        }

        @Test
        @DisplayName("Replacing message preserves its original timestamp")
        void timestampPreservedAcrossReplacement() {
            MutableClock clock = new MutableClock(T0);
            TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate(), null, List.of(), clock);
            memory.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("call_1", "stale"))));
            Instant originalStamp = memory.getMessageTimestamps().get(0);

            clock.set(T0.plus(Duration.ofMinutes(10)));
            new TimeBasedMicrocompact(Duration.ofMinutes(5), 0, clock).compact(memory);

            assertThat(memory.getMessageTimestamps()).containsExactly(originalStamp);
        }

        @Test
        @DisplayName("All ToolUseResults in a multi-result message are cleared")
        void multiResultMessageAllCleared() {
            MutableClock clock = new MutableClock(T0);
            TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate(), null, List.of(), clock);
            memory.addMessage(Message.toolUseResults(
                    List.of(ToolUseResult.success("call_1", "alpha"), ToolUseResult.error("call_2", "beta"))));

            clock.set(T0.plus(Duration.ofMinutes(10)));
            int cleared = new TimeBasedMicrocompact(Duration.ofMinutes(5), 0, clock).compact(memory);

            assertThat(cleared).isEqualTo(1);
            List<ToolUseResult> results = memory.getMessages().get(0).getToolUseResults();
            assertThat(results.get(0).getContent()).isEqualTo(TimeBasedMicrocompact.CLEARED_PLACEHOLDER);
            assertThat(results.get(0).isSuccess()).isTrue();
            assertThat(results.get(1).getContent()).isEqualTo(TimeBasedMicrocompact.CLEARED_PLACEHOLDER);
            assertThat(results.get(1).isError()).isTrue();
        }

        @Test
        @DisplayName("Mixed message types — only stale tool results are touched")
        void mixedMessagesPreserveNonToolMessages() {
            MutableClock clock = new MutableClock(T0);
            TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate(), null, List.of(), clock);

            memory.addUserMessage("user-old");
            memory.addAssistantMessage("assistant-old");
            memory.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("call_1", "tool-old"))));
            memory.addAssistantMessage("assistant-recent");

            clock.set(T0.plus(Duration.ofMinutes(10)));
            new TimeBasedMicrocompact(Duration.ofMinutes(5), 0, clock).compact(memory);

            assertThat(memory.getMessages().get(0).getContent()).isEqualTo("user-old");
            assertThat(memory.getMessages().get(1).getContent()).isEqualTo("assistant-old");
            assertThat(memory.getMessages().get(2).getToolUseResults().get(0).getContent())
                    .isEqualTo(TimeBasedMicrocompact.CLEARED_PLACEHOLDER);
            assertThat(memory.getMessages().get(3).getContent()).isEqualTo("assistant-recent");
        }

        @Test
        @DisplayName("Rejects null memory")
        void rejectsNullMemory() {
            TimeBasedMicrocompact pass = new TimeBasedMicrocompact(Duration.ofMinutes(5));
            assertThatThrownBy(() -> pass.compact(null)).isInstanceOf(NullPointerException.class);
        }
    }

    private static TranscriptBuffer newMemory(Instant fixed) {
        return new TranscriptBuffer(SessionId.generate(), null, List.of(), fixed(fixed));
    }

    private static Clock fixed(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    /** Test-only mutable clock helper for stepping through scripted instants. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant initial) {
            this.instant = initial;
        }

        void set(Instant next) {
            this.instant = next;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
