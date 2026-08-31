package at.aimon.core.agent.session.transcript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolUseResult;

@DisplayName("TranscriptBuffer Tests")
class TranscriptBufferTest {

    private TranscriptBuffer context;

    @BeforeEach
    void setUp() {
        context = createContext();
    }

    private SessionId createSessionId() {
        return new SessionId(UUID.randomUUID().toString());
    }

    private TranscriptBuffer createContext() {
        return new TranscriptBuffer(createSessionId());
    }

    private TranscriptBuffer createContext(String systemPrompt) {
        return new TranscriptBuffer(createSessionId(), systemPrompt);
    }

    private TranscriptBuffer createContext(String systemPrompt, List<Message> messages) {
        return new TranscriptBuffer(createSessionId(), systemPrompt, messages);
    }

    @Test
    @DisplayName("Should start empty")
    void shouldStartEmpty() {
        assertThat(context.isEmpty()).isTrue();
        assertThat(context.size()).isEqualTo(0);
        assertThat(context.getMessages()).isEmpty();
    }

    @Test
    @DisplayName("Should add user message")
    void shouldAddUserMessage() {
        context.addUserMessage("Hello");

        assertThat(context.size()).isEqualTo(1);
        assertThat(context.isEmpty()).isFalse();
        assertThat(context.getMessages()).hasSize(1);
        assertThat(context.getMessages().get(0).getRole()).isEqualTo(Role.USER);
        assertThat(context.getMessages().get(0).getContent()).isEqualTo("Hello");
    }

    @Test
    @DisplayName("Should add assistant message")
    void shouldAddAssistantMessage() {
        context.addAssistantMessage("Hi there");

        assertThat(context.size()).isEqualTo(1);
        assertThat(context.getMessages().get(0).getRole()).isEqualTo(Role.ASSISTANT);
        assertThat(context.getMessages().get(0).getContent()).isEqualTo("Hi there");
    }

    @Test
    @DisplayName("Should add message directly")
    void shouldAddMessageDirectly() {
        Message message = Message.user("Test");

        context.addMessage(message);

        assertThat(context.size()).isEqualTo(1);
        assertThat(context.getMessages().get(0)).isEqualTo(message);
    }

    @Test
    @DisplayName("Should add multiple messages")
    void shouldAddMultipleMessages() {
        context.addUserMessage("Question");
        context.addAssistantMessage("Answer");
        context.addUserMessage("Follow-up");

        assertThat(context.size()).isEqualTo(3);
        assertThat(context.getMessages()).hasSize(3);
    }

    @Test
    @DisplayName("Should return immutable messages list")
    void shouldReturnImmutableMessagesList() {
        context.addUserMessage("Test");

        List<Message> messages = context.getMessages();

        assertThatThrownBy(() -> messages.add(Message.user("Another")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should create defensive copy of messages")
    void shouldCreateDefensiveCopyOfMessages() {
        context.addUserMessage("Original");

        List<Message> messages1 = context.getMessages();
        context.addUserMessage("Added later");
        List<Message> messages2 = context.getMessages();

        assertThat(messages1).hasSize(1);
        assertThat(messages2).hasSize(2);
    }

    @Test
    @DisplayName("Should clear messages")
    void shouldClearMessages() {
        context.addUserMessage("Message 1");
        context.addUserMessage("Message 2");

        context.clear();

        assertThat(context.isEmpty()).isTrue();
        assertThat(context.size()).isEqualTo(0);
        assertThat(context.hasSystemPrompt()).isFalse();
        assertThat(context.getSystemPrompt()).isNull();
    }

    @Test
    @DisplayName("Should get last message")
    void shouldGetLastMessage() {
        context.addUserMessage("First");
        context.addAssistantMessage("Second");
        context.addUserMessage("Third");

        Message last = context.getLastMessage();

        assertThat(last).isNotNull();
        assertThat(last.getRole()).isEqualTo(Role.USER);
        assertThat(last.getContent()).isEqualTo("Third");
    }

    @Test
    @DisplayName("Should return null for last message when empty")
    void shouldReturnNullForLastMessageWhenEmpty() {
        assertThat(context.getLastMessage()).isNull();
    }

    @Test
    @DisplayName("Should get last N messages")
    void shouldGetLastNMessages() {
        context.addUserMessage("Message 1");
        context.addUserMessage("Message 2");
        context.addUserMessage("Message 3");
        context.addUserMessage("Message 4");

        List<Message> last2 = context.getLastMessages(2);

        assertThat(last2).hasSize(2);
        assertThat(last2.get(0).getContent()).isEqualTo("Message 3");
        assertThat(last2.get(1).getContent()).isEqualTo("Message 4");
    }

    @Test
    @DisplayName("Should return all messages if N is greater than size")
    void shouldReturnAllMessagesIfNIsGreaterThanSize() {
        context.addUserMessage("Message 1");
        context.addUserMessage("Message 2");

        List<Message> last10 = context.getLastMessages(10);

        assertThat(last10).hasSize(2);
    }

    @Test
    @DisplayName("Should return empty list for last 0 messages")
    void shouldReturnEmptyListForLast0Messages() {
        context.addUserMessage("Message");

        List<Message> last0 = context.getLastMessages(0);

        assertThat(last0).isEmpty();
    }

    @Test
    @DisplayName("Should reject negative count for last messages")
    void shouldRejectNegativeCountForLastMessages() {
        assertThatThrownBy(() -> context.getLastMessages(-1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Count cannot be negative");
    }

    @Test
    @DisplayName("Should count user messages")
    void shouldCountUserMessages() {
        context.addUserMessage("User 1");
        context.addAssistantMessage("Assistant 1");
        context.addUserMessage("User 2");
        context.addUserMessage("User 3");

        assertThat(context.countUserMessages()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should count assistant messages")
    void shouldCountAssistantMessages() {
        context.addUserMessage("User 1");
        context.addAssistantMessage("Assistant 1");
        context.addAssistantMessage("Assistant 2");
        context.addUserMessage("User 2");

        assertThat(context.countAssistantMessages()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should count zero when empty")
    void shouldCountZeroWhenEmpty() {
        assertThat(context.countUserMessages()).isEqualTo(0);
        assertThat(context.countAssistantMessages()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should reject null content in addUserMessage")
    void shouldRejectNullContentInAddUserMessage() {
        assertThatThrownBy(() -> context.addUserMessage(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Content cannot be null");
    }

    @Test
    @DisplayName("Should reject null content in addAssistantMessage")
    void shouldRejectNullContentInAddAssistantMessage() {
        assertThatThrownBy(() -> context.addAssistantMessage(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Content cannot be null");
    }

    @Test
    @DisplayName("Should reject null message in addMessage")
    void shouldRejectNullMessageInAddMessage() {
        assertThatThrownBy(() -> context.addMessage(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Message cannot be null");
    }

    @Test
    @DisplayName("Should have meaningful toString")
    void shouldHaveMeaningfulToString() {
        context.addUserMessage("User");
        context.addAssistantMessage("Assistant");

        String toString = context.toString();

        assertThat(toString).contains("TranscriptBuffer");
        assertThat(toString).contains("messages=2");
        assertThat(toString).contains("user=1");
        assertThat(toString).contains("assistant=1");
    }

    @Test
    @DisplayName("Should handle empty messages")
    void shouldHandleEmptyMessages() {
        context.addUserMessage("");
        context.addAssistantMessage("");

        assertThat(context.size()).isEqualTo(2);
        assertThat(context.getMessages().get(0).getContent()).isEmpty();
        assertThat(context.getMessages().get(1).getContent()).isEmpty();
    }

    @Test
    @DisplayName("Should handle multiline messages")
    void shouldHandleMultilineMessages() {
        String multiline = "Line 1\nLine 2\nLine 3";

        context.addUserMessage(multiline);

        assertThat(context.getMessages().get(0).getContent()).isEqualTo(multiline);
    }

    @Test
    @DisplayName("Should maintain message order")
    void shouldMaintainMessageOrder() {
        context.addUserMessage("First");
        context.addAssistantMessage("Second");
        context.addUserMessage("Third");
        context.addAssistantMessage("Fourth");

        List<Message> messages = context.getMessages();

        assertThat(messages.get(0).getContent()).isEqualTo("First");
        assertThat(messages.get(1).getContent()).isEqualTo("Second");
        assertThat(messages.get(2).getContent()).isEqualTo("Third");
        assertThat(messages.get(3).getContent()).isEqualTo("Fourth");
    }

    @Test
    @DisplayName("Should handle alternating user and assistant messages")
    void shouldHandleAlternatingMessages() {
        for (int i = 0; i < 10; i++) {
            context.addUserMessage("User " + i);
            context.addAssistantMessage("Assistant " + i);
        }

        assertThat(context.size()).isEqualTo(20);
        assertThat(context.countUserMessages()).isEqualTo(10);
        assertThat(context.countAssistantMessages()).isEqualTo(10);

        // Check alternating pattern
        List<Message> messages = context.getMessages();
        for (int i = 0; i < messages.size(); i++) {
            if (i % 2 == 0) {
                assertThat(messages.get(i).getRole()).isEqualTo(Role.USER);
            } else {
                assertThat(messages.get(i).getRole()).isEqualTo(Role.ASSISTANT);
            }
        }
    }

    @Test
    @DisplayName("Should handle real-world conversation pattern")
    void shouldHandleRealWorldConversationPattern() {
        // User initiates
        context.addUserMessage("Create a git commit for the changes");

        // Assistant responds
        context.addAssistantMessage("I'll check the git status first");

        // User provides more context
        context.addUserMessage("The changes are in the README file");

        // Assistant performs action
        context.addAssistantMessage("I've created a commit with message 'Update README'");

        assertThat(context.size()).isEqualTo(4);
        assertThat(context.countUserMessages()).isEqualTo(2);
        assertThat(context.countAssistantMessages()).isEqualTo(2);

        Message last = context.getLastMessage();
        assertThat(last.getRole()).isEqualTo(Role.ASSISTANT);
        assertThat(last.getContent()).contains("Update README");
    }

    @Test
    @DisplayName("Should create context without system prompt")
    void shouldCreateContextWithoutSystemPrompt() {
        TranscriptBuffer ctx = createContext();

        assertThat(ctx.getSystemPrompt()).isNull();
    }

    @Test
    @DisplayName("Should create context with system prompt")
    void shouldCreateContextWithSystemPrompt() {
        String systemPrompt = "You are a helpful assistant.";
        TranscriptBuffer ctx = createContext(systemPrompt);

        assertThat(ctx.getSystemPrompt()).isEqualTo(systemPrompt);
    }

    @Test
    @DisplayName("Should allow null system prompt in constructor")
    void shouldAllowNullSystemPromptInConstructor() {
        TranscriptBuffer ctx = createContext(null);

        assertThat(ctx.getSystemPrompt()).isNull();
    }

    @Test
    @DisplayName("Should create context with system prompt and messages")
    void shouldCreateContextWithSystemPromptAndMessages() {
        String systemPrompt = "You are a helpful assistant.";
        List<Message> messages = List.of(Message.user("Hello"), Message.assistant("Hi! How can I help you?"));

        TranscriptBuffer ctx = createContext(systemPrompt, messages);

        assertThat(ctx.getSystemPrompt()).isEqualTo(systemPrompt);
        assertThat(ctx.size()).isEqualTo(2);
        assertThat(ctx.getMessages().get(0).getContent()).isEqualTo("Hello");
        assertThat(ctx.getMessages().get(1).getContent()).isEqualTo("Hi! How can I help you?");
    }

    @Test
    @DisplayName("Should create defensive copy in constructor with messages")
    void shouldCreateDefensiveCopyInConstructorWithMessages() {
        List<Message> originalMessages = new ArrayList<>();
        originalMessages.add(Message.user("Original"));

        TranscriptBuffer ctx = createContext("Prompt", originalMessages);

        // Modify original list
        originalMessages.add(Message.user("Added after construction"));

        // Context should not be affected
        assertThat(ctx.size()).isEqualTo(1);
        assertThat(ctx.getMessages().get(0).getContent()).isEqualTo("Original");
    }

    @Test
    @DisplayName("Should reject null messages in constructor")
    void shouldRejectNullMessagesInConstructor() {
        assertThatThrownBy(() -> createContext("Prompt", null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Messages cannot be null");
    }

    @Test
    @DisplayName("Should allow empty messages list in constructor")
    void shouldAllowEmptyMessagesListInConstructor() {
        TranscriptBuffer ctx = createContext("Prompt", List.of());

        assertThat(ctx.getSystemPrompt()).isEqualTo("Prompt");
        assertThat(ctx.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("Should allow null system prompt with messages in constructor")
    void shouldAllowNullSystemPromptWithMessagesInConstructor() {
        List<Message> messages = List.of(Message.user("Test"));

        TranscriptBuffer ctx = createContext(null, messages);

        assertThat(ctx.getSystemPrompt()).isNull();
        assertThat(ctx.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should set system prompt")
    void shouldSetSystemPrompt() {
        context.setSystemPrompt("You are a code assistant.");

        assertThat(context.getSystemPrompt()).isEqualTo("You are a code assistant.");
    }

    @Test
    @DisplayName("Should update system prompt")
    void shouldUpdateSystemPrompt() {
        context.setSystemPrompt("First prompt");
        context.setSystemPrompt("Second prompt");

        assertThat(context.getSystemPrompt()).isEqualTo("Second prompt");
    }

    @Test
    @DisplayName("Should allow setting system prompt to null")
    void shouldAllowSettingSystemPromptToNull() {
        context.setSystemPrompt("Some prompt");
        context.setSystemPrompt(null);

        assertThat(context.getSystemPrompt()).isNull();
    }

    @Test
    @DisplayName("Should include system prompt status in toString")
    void shouldIncludeSystemPromptStatusInToString() {
        context.addUserMessage("Test");
        context.setSystemPrompt("You are helpful.");

        String toString = context.toString();

        assertThat(toString).contains("hasSystemPrompt=true");
    }

    @Test
    @DisplayName("Should show false for hasSystemPrompt when null")
    void shouldShowFalseForHasSystemPromptWhenNull() {
        String toString = context.toString();

        assertThat(toString).contains("hasSystemPrompt=false");
    }

    @Test
    @DisplayName("Should preserve system prompt with messages")
    void shouldPreserveSystemPromptWithMessages() {
        TranscriptBuffer ctx = createContext("You are an AI assistant.");
        ctx.addUserMessage("Hello");
        ctx.addAssistantMessage("Hi there!");

        assertThat(ctx.getSystemPrompt()).isEqualTo("You are an AI assistant.");
        assertThat(ctx.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should convert context to snapshot")
    void shouldConvertContextToSnapshot() {
        TranscriptBuffer ctx = createContext("You are helpful.");
        ctx.addUserMessage("Question");
        ctx.addAssistantMessage("Answer");

        SessionSnapshot snapshot = ctx.toSnapshot();

        assertThat(snapshot.getSystemPrompt()).isEqualTo("You are helpful.");
        assertThat(snapshot.getConversationHistory()).hasSize(2);
        assertThat(snapshot.getConversationHistory().get(0).getContent()).isEqualTo("Question");
        assertThat(snapshot.getConversationHistory().get(1).getContent()).isEqualTo("Answer");
    }

    @Test
    @DisplayName("Should create snapshot that is independent of context")
    void shouldCreateSnapshotThatIsIndependentOfContext() {
        TranscriptBuffer ctx = createContext("Original");
        ctx.addUserMessage("First");

        SessionSnapshot snapshot = ctx.toSnapshot();

        // Modify context after creating snapshot
        ctx.addUserMessage("Second");
        ctx.setSystemPrompt("Modified");

        // Snapshot should remain unchanged
        assertThat(snapshot.getSystemPrompt()).isEqualTo("Original");
        assertThat(snapshot.getConversationHistory()).hasSize(1);
    }

    @Test
    @DisplayName("Should create context from snapshot")
    void shouldCreateContextFromSnapshot() {
        SessionSnapshot snapshot = SessionSnapshot.of(new SessionId("test-conv-1"), "You are helpful.",
                List.of(Message.user("Question"), Message.assistant("Answer")));

        TranscriptBuffer ctx = TranscriptBuffer.fromSnapshot(snapshot);

        assertThat(ctx.getSystemPrompt()).isEqualTo("You are helpful.");
        assertThat(ctx.size()).isEqualTo(2);
        assertThat(ctx.getMessages().get(0).getContent()).isEqualTo("Question");
        assertThat(ctx.getMessages().get(1).getContent()).isEqualTo("Answer");
    }

    @Test
    @DisplayName("Should create context that is independent of snapshot")
    void shouldCreateContextThatIsIndependentOfSnapshot() {
        SessionSnapshot snapshot = SessionSnapshot.of(new SessionId("test-conv-2"), "Prompt",
                List.of(Message.user("Original")));

        TranscriptBuffer ctx = TranscriptBuffer.fromSnapshot(snapshot);

        // Modify context
        ctx.addUserMessage("Added");
        ctx.setSystemPrompt("Modified");

        // Original snapshot should remain unchanged
        assertThat(snapshot.getSystemPrompt()).isEqualTo("Prompt");
        assertThat(snapshot.getConversationHistory()).hasSize(1);

        // Context should have changes
        assertThat(ctx.getSystemPrompt()).isEqualTo("Modified");
        assertThat(ctx.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should reject null snapshot in fromSnapshot")
    void shouldRejectNullSnapshotInFromSnapshot() {
        assertThatThrownBy(() -> TranscriptBuffer.fromSnapshot(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Snapshot cannot be null");
    }

    @Test
    @DisplayName("Should round-trip context through snapshot")
    void shouldRoundTripContextThroughSnapshot() {
        // Original context
        TranscriptBuffer original = createContext("System prompt");
        original.addUserMessage("User 1");
        original.addAssistantMessage("Assistant 1");
        original.addUserMessage("User 2");

        // Convert to snapshot and back
        SessionSnapshot snapshot = original.toSnapshot();
        TranscriptBuffer restored = TranscriptBuffer.fromSnapshot(snapshot);

        // Should have same content
        assertThat(restored.getSystemPrompt()).isEqualTo(original.getSystemPrompt());
        assertThat(restored.size()).isEqualTo(original.size());
        assertThat(restored.getMessages()).isEqualTo(original.getMessages());
    }

    @Test
    @DisplayName("Should handle empty context in toSnapshot")
    void shouldHandleEmptyContextInToSnapshot() {
        TranscriptBuffer ctx = createContext("Prompt");

        SessionSnapshot snapshot = ctx.toSnapshot();

        assertThat(snapshot.getSystemPrompt()).isEqualTo("Prompt");
        assertThat(snapshot.getConversationHistory()).isEmpty();
    }

    @Test
    @DisplayName("Should handle empty snapshot in fromSnapshot")
    void shouldHandleEmptySnapshotInFromSnapshot() {
        SessionSnapshot snapshot = SessionSnapshot.of(new SessionId("test-conv-3"), "Prompt", List.of());

        TranscriptBuffer ctx = TranscriptBuffer.fromSnapshot(snapshot);

        assertThat(ctx.getSystemPrompt()).isEqualTo("Prompt");
        assertThat(ctx.isEmpty()).isTrue();
    }

    @Nested
    @DisplayName("Message timestamps")
    class MessageTimestamps {

        private Clock clockAt(Instant instant) {
            return Clock.fixed(instant, ZoneOffset.UTC);
        }

        @Test
        @DisplayName("Empty memory has no timestamps")
        void emptyMemoryHasNoTimestamps() {
            TranscriptBuffer memory = new TranscriptBuffer(createSessionId());

            assertThat(memory.getMessageTimestamps()).isEmpty();
        }

        @Test
        @DisplayName("Each add path records the clock instant in lockstep with messages")
        void addPathsRecordClockInstants() {
            Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
            MutableClock clock = new MutableClock(t0);
            TranscriptBuffer memory = new TranscriptBuffer(createSessionId(), null, List.of(), clock);

            clock.set(t0.plusSeconds(1));
            memory.addUserMessage("u");
            clock.set(t0.plusSeconds(2));
            memory.addAssistantMessage("a");
            clock.set(t0.plusSeconds(3));
            memory.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("call_1", "ok"))));

            assertThat(memory.getMessageTimestamps()).containsExactly(t0.plusSeconds(1), t0.plusSeconds(2),
                    t0.plusSeconds(3));
        }

        @Test
        @DisplayName("Initial messages constructor stamps every message with current clock instant")
        void initialMessagesAllStampedWithNow() {
            Instant fixed = Instant.parse("2026-01-15T12:00:00Z");
            TranscriptBuffer memory = new TranscriptBuffer(createSessionId(), null,
                    List.of(Message.user("a"), Message.user("b"), Message.user("c")), clockAt(fixed));

            assertThat(memory.getMessageTimestamps()).containsExactly(fixed, fixed, fixed);
        }

        @Test
        @DisplayName("clear() removes timestamps too")
        void clearRemovesTimestamps() {
            Instant fixed = Instant.parse("2026-01-15T12:00:00Z");
            TranscriptBuffer memory = new TranscriptBuffer(createSessionId(), null, List.of(), clockAt(fixed));
            memory.addUserMessage("hello");

            memory.clear();

            assertThat(memory.getMessageTimestamps()).isEmpty();
        }

        @Test
        @DisplayName("replaceWith() restamps every replacement message with current clock")
        void replaceWithRestampsAll() {
            Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
            MutableClock clock = new MutableClock(t0);
            TranscriptBuffer memory = new TranscriptBuffer(createSessionId(), null, List.of(), clock);

            memory.addUserMessage("u1");
            clock.set(t0.plusSeconds(60));
            memory.replaceWith(List.of(Message.user("summary"), Message.assistant("ack")));

            assertThat(memory.getMessageTimestamps()).containsExactly(t0.plusSeconds(60), t0.plusSeconds(60));
        }

        @Test
        @DisplayName("replaceWith(empty) clears the timestamp list")
        void replaceWithEmptyClearsTimestamps() {
            TranscriptBuffer memory = new TranscriptBuffer(createSessionId());
            memory.addUserMessage("u1");

            memory.replaceWith(List.of());

            assertThat(memory.getMessageTimestamps()).isEmpty();
        }

        @Test
        @DisplayName("getMessageTimestamps() returns an unmodifiable defensive copy")
        void timestampListIsUnmodifiable() {
            TranscriptBuffer memory = new TranscriptBuffer(createSessionId());
            memory.addUserMessage("u");

            List<Instant> snapshot = memory.getMessageTimestamps();

            assertThatThrownBy(() -> snapshot.add(Instant.now())).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("replaceMessageAt() preserves the slot's timestamp")
        void replaceMessageAtPreservesTimestamp() {
            Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
            MutableClock clock = new MutableClock(t0);
            TranscriptBuffer memory = new TranscriptBuffer(createSessionId(), null, List.of(), clock);

            memory.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("call_1", "huge body"))));
            Instant originalStamp = memory.getMessageTimestamps().get(0);

            clock.set(t0.plusSeconds(3600));
            memory.replaceMessageAt(0, Message.toolUseResults(List.of(ToolUseResult.success("call_1", "[scrubbed]"))));

            assertThat(memory.getMessageTimestamps()).containsExactly(originalStamp);
            assertThat(memory.getMessages().get(0).getToolUseResults().get(0).getContent()).isEqualTo("[scrubbed]");
        }

        @Test
        @DisplayName("replaceMessageAt() rejects out-of-bounds indices")
        void replaceMessageAtRejectsOutOfBounds() {
            TranscriptBuffer memory = new TranscriptBuffer(createSessionId());
            memory.addUserMessage("u");

            assertThatThrownBy(() -> memory.replaceMessageAt(-1, Message.user("x")))
                    .isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> memory.replaceMessageAt(1, Message.user("x")))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        @DisplayName("replaceMessageAt() rejects null replacement")
        void replaceMessageAtRejectsNull() {
            TranscriptBuffer memory = new TranscriptBuffer(createSessionId());
            memory.addUserMessage("u");

            assertThatThrownBy(() -> memory.replaceMessageAt(0, null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("newMessage cannot be null");
        }

        @Test
        @DisplayName("Clock-aware constructor rejects null clock")
        void clockConstructorRejectsNullClock() {
            assertThatThrownBy(() -> new TranscriptBuffer(createSessionId(), null, List.of(), null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Clock cannot be null");
        }
    }

    @Nested
    @DisplayName("Dirty tracking")
    class DirtyTracking {

        @Test
        @DisplayName("version starts at zero and increments on mutation")
        void versionStartsAtZeroAndIncrements() {
            final TranscriptBuffer memory = createContext();

            assertThat(memory.getVersion()).isZero();

            memory.addUserMessage("hi");
            assertThat(memory.getVersion()).isEqualTo(1L);

            memory.addAssistantMessage("hello");
            assertThat(memory.getVersion()).isEqualTo(2L);
        }

        @Test
        @DisplayName("dirty listener fires on every mutation")
        void dirtyListenerFiresOnEveryMutation() {
            final TranscriptBuffer memory = createContext();
            final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            memory.setDirtyListener(m -> calls.incrementAndGet());

            memory.addUserMessage("a");
            memory.addAssistantMessage("b");
            memory.setSystemPrompt("new-sys");
            memory.clear();

            assertThat(calls.get()).isEqualTo(4);
        }

        @Test
        @DisplayName("dirty listener is detached when set to null")
        void dirtyListenerCanBeDetached() {
            final TranscriptBuffer memory = createContext();
            final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            memory.setDirtyListener(m -> calls.incrementAndGet());

            memory.addUserMessage("a");
            memory.setDirtyListener(null);
            memory.addUserMessage("b");

            assertThat(calls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("replaceWith bumps version and notifies listener")
        void replaceWithBumpsVersionAndNotifies() {
            final TranscriptBuffer memory = createContext();
            memory.addUserMessage("seed");
            final long before = memory.getVersion();
            final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            memory.setDirtyListener(m -> calls.incrementAndGet());

            memory.replaceWith(List.of(Message.user("replaced")));

            assertThat(memory.getVersion()).isEqualTo(before + 1);
            assertThat(calls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("replaceMessageAt bumps version and notifies listener")
        void replaceMessageAtBumpsVersionAndNotifies() {
            final TranscriptBuffer memory = createContext();
            memory.addUserMessage("seed");
            final long before = memory.getVersion();
            final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            memory.setDirtyListener(m -> calls.incrementAndGet());

            memory.replaceMessageAt(0, Message.user("rewritten"));

            assertThat(memory.getVersion()).isEqualTo(before + 1);
            assertThat(calls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("readers do not bump version")
        void readersDoNotBumpVersion() {
            final TranscriptBuffer memory = createContext();
            memory.addUserMessage("a");
            final long after = memory.getVersion();

            memory.getMessages();
            memory.size();
            memory.toSnapshot();
            memory.getLastMessage();

            assertThat(memory.getVersion()).isEqualTo(after);
        }
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
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
