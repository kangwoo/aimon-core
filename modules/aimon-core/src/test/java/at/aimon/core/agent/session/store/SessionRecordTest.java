package at.aimon.core.agent.session.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.session.transcript.SessionTranscript;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;

/**
 * Characterization tests for {@link SessionRecord}.
 *
 * <p>
 * These pin the observable contract that the rest of the codebase already depends on — defensive copying, the exact
 * shape of {@code getMessages()}, constructor validation order and messages, and which fields survive
 * {@link SessionRecord#copyOf(SessionRecordView)}. They exist so that refactoring the internals (e.g. moving
 * {@code systemPrompt} + {@code messages} behind {@link SessionTranscript}) cannot silently change behavior at
 * the seams.
 *
 * <p>
 * Two behaviors here look like accidents but are load-bearing and asserted deliberately:
 *
 * <ul>
 * <li>the message list is copied with {@code new ArrayList<>(..)}, not {@code List.copyOf(..)}, so <em>null elements
 * are tolerated</em> — swapping in {@code List.copyOf} would turn a lenient constructor into a throwing one, and
 * would make {@code getMessages().contains(null)} throw instead of answering {@code false};
 * <li>the history is fixed at construction — this type has no append method, so a list handed out by
 * {@code getMessages()} can never go stale. Appending belongs to {@link TranscriptBuffer}; the transcript-level
 * append semantics are pinned in {@code SessionTranscriptTest}.
 * </ul>
 */
@DisplayName("SessionRecord")
class SessionRecordTest {

    private static final Message HELLO = Message.user("hello");
    private static final Message HI = Message.assistant("hi");

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("id-only constructor leaves every optional field at its documented default")
        void idOnlyDefaults() {
            final SessionRecord record = new SessionRecord(SessionId.of("c-1"));

            assertThat(record.getId()).isEqualTo(SessionId.of("c-1"));
            assertThat(record.getSystemPrompt()).isNull();
            assertThat(record.getMessages()).isEmpty();
            assertThat(record.getCompactionFailureCount()).isZero();
            assertThat(record.getAgentRef()).isEmpty();
            assertThat(record.getSessionTotals()).isEqualTo(SessionTotals.empty());
            assertThat(record.getBudgetOverride()).isEmpty();
        }

        @Test
        @DisplayName("the canonical constructor retains all seven fields verbatim")
        void canonicalConstructorRetainsAllFields() {
            final SessionTotals totals = SessionTotals.of(2, 5, TokenUsage.of(10, 4, 14));
            final ExecutionBudget budget = ExecutionBudget.builder().maxIterations(7).build();

            final SessionRecord record = new SessionRecord(SessionId.of("c-2"), "sys", List.of(HELLO, HI), 3, "agent-a",
                    totals, budget);

            assertThat(record.getId()).isEqualTo(SessionId.of("c-2"));
            assertThat(record.getSystemPrompt()).isEqualTo("sys");
            assertThat(record.getMessages()).containsExactly(HELLO, HI);
            assertThat(record.getCompactionFailureCount()).isEqualTo(3);
            assertThat(record.getAgentRef()).contains("agent-a");
            assertThat(record.getSessionTotals()).isEqualTo(totals);
            assertThat(record.getBudgetOverride()).contains(budget);
        }

        @Test
        @DisplayName("the incoming message list is defensively copied")
        void messagesAreDefensivelyCopied() {
            final List<Message> source = new ArrayList<>(List.of(HELLO));

            final SessionRecord record = new SessionRecord(SessionId.of("c-3"), null, source);
            source.add(HI);

            assertThat(record.getMessages()).containsExactly(HELLO);
        }

        @Test
        @DisplayName("null message elements are tolerated (ArrayList copy, not List.copyOf)")
        void nullMessageElementsAreTolerated() {
            final List<Message> withNull = Arrays.asList(HELLO, null);

            final SessionRecord record = new SessionRecord(SessionId.of("c-4"), null, withNull);

            assertThat(record.getMessages()).hasSize(2).containsExactly(HELLO, null);
        }
    }

    @Nested
    @DisplayName("constructor validation")
    class Validation {

        @Test
        @DisplayName("null id is rejected first, before any other argument is inspected")
        void nullIdRejectedFirst() {
            // Every other argument is simultaneously invalid; the id check must still be the one that fires.
            assertThatNullPointerException().isThrownBy(() -> new SessionRecord(null, null, null, -1, null, null, null))
                    .withMessage("Id cannot be null");
        }

        @Test
        @DisplayName("null messages is rejected before sessionTotals and the failure count")
        void nullMessagesRejectedBeforeTotals() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new SessionRecord(SessionId.of("c-5"), null, null, -1, null, null, null))
                    .withMessage("Messages cannot be null");
        }

        @Test
        @DisplayName("null sessionTotals is rejected before the failure count")
        void nullTotalsRejectedBeforeCount() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new SessionRecord(SessionId.of("c-6"), null, List.of(), -1, null, null, null))
                    .withMessage("sessionTotals cannot be null");
        }

        @Test
        @DisplayName("a negative compactionFailureCount is rejected with the value echoed back")
        void negativeCompactionFailureCountRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SessionRecord(SessionId.of("c-7"), null, List.of(), -1))
                    .withMessage("compactionFailureCount must be >= 0, got: -1");
        }

        @Test
        @DisplayName("setCompactionFailureCount applies the same rule as the constructor")
        void setterRejectsNegativeCount() {
            final SessionRecord record = new SessionRecord(SessionId.of("c-8"));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> record.setCompactionFailureCount(-5))
                    .withMessage("compactionFailureCount must be >= 0, got: -5");
            assertThat(record.getCompactionFailureCount()).isZero();
        }

        @Test
        @DisplayName("setSessionTotals rejects null and leaves the previous value intact")
        void setConversationTotalsRejectsNull() {
            final SessionRecord record = new SessionRecord(SessionId.of("c-9"));

            assertThatNullPointerException().isThrownBy(() -> record.setSessionTotals(null))
                    .withMessage("sessionTotals cannot be null");
            assertThat(record.getSessionTotals()).isEqualTo(SessionTotals.empty());
        }
    }

    @Nested
    @DisplayName("getMessages()")
    class GetMessages {

        @Test
        @DisplayName("returns a list that cannot be modified by the caller")
        void returnedListIsUnmodifiable() {
            final SessionRecord record = new SessionRecord(SessionId.of("c-10"), null, List.of(HELLO));

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> record.getMessages().add(HI));
        }

        @Test
        @DisplayName("a handed-out list survives the one mutator that swaps the transcript")
        void returnedListSurvivesPromptChanges() {
            final SessionRecord record = new SessionRecord(SessionId.of("c-11"), "sys", List.of(HELLO));

            final List<Message> before = record.getMessages();
            // setSystemPrompt replaces the transcript reference. It must carry the same message list over rather than
            // rebuild it, and must not disturb a list already handed to a caller.
            record.setSystemPrompt("changed");

            assertThat(before).containsExactly(HELLO);
            assertThat(record.getMessages()).containsExactly(HELLO);
        }

        @Test
        @DisplayName("answers contains(null) with false instead of throwing")
        void toleratesNullQueries() {
            final SessionRecord record = new SessionRecord(SessionId.of("c-12"), null, List.of(HELLO));

            // List.copyOf-backed lists throw NullPointerException here. Callers rely on the lenient behavior.
            assertThatCode(() -> assertThat(record.getMessages().contains(null)).isFalse()).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("mutators")
    class Mutators {

        @Test
        @DisplayName("the constructor preserves message order, which is the only way to supply a history")
        void constructorPreservesOrder() {
            final SessionRecord record = new SessionRecord(SessionId.of("c-13"), null,
                    List.of(Message.user("first"), Message.assistant("second"), Message.user("third")));

            assertThat(record.getMessages()).extracting(Message::getContent).containsExactly("first", "second",
                    "third");
        }

        @Test
        @DisplayName("setSystemPrompt accepts null to clear the prompt")
        void systemPromptIsNullable() {
            final SessionRecord record = new SessionRecord(SessionId.of("c-15"), "sys");

            record.setSystemPrompt(null);

            assertThat(record.getSystemPrompt()).isNull();
        }

        @Test
        @DisplayName("setAgentRef accepts null to clear the binding")
        void agentRefIsNullable() {
            final SessionRecord record = new SessionRecord(SessionId.of("c-16"), null, List.of(), 0, "bound");

            assertThat(record.getAgentRef()).contains("bound");
            record.setAgentRef(null);
            assertThat(record.getAgentRef()).isEmpty();
        }

        @Test
        @DisplayName("setBudgetOverride accepts null to clear the override")
        void budgetOverrideIsNullable() {
            final SessionRecord record = new SessionRecord(SessionId.of("c-17"));

            record.setBudgetOverride(ExecutionBudget.unlimited());
            assertThat(record.getBudgetOverride()).isPresent();

            record.setBudgetOverride(null);
            assertThat(record.getBudgetOverride()).isEmpty();
        }
    }

    @Nested
    @DisplayName("copyOf(SessionRecordView)")
    class CopyOf {

        @Test
        @DisplayName("carries over all seven fields")
        void carriesOverAllFields() {
            final SessionTotals totals = SessionTotals.of(4, 9, TokenUsage.of(1, 2, 3));
            final ExecutionBudget budget = ExecutionBudget.builder().maxTokens(500).build();
            final SessionRecord source = new SessionRecord(SessionId.of("c-18"), "sys", List.of(HELLO, HI), 2,
                    "agent-b", totals, budget);

            final SessionRecord copy = SessionRecord.copyOf(source);

            assertThat(copy.getId()).isEqualTo(source.getId());
            assertThat(copy.getSystemPrompt()).isEqualTo("sys");
            assertThat(copy.getMessages()).containsExactly(HELLO, HI);
            assertThat(copy.getCompactionFailureCount()).isEqualTo(2);
            assertThat(copy.getAgentRef()).contains("agent-b");
            assertThat(copy.getSessionTotals()).isEqualTo(totals);
            assertThat(copy.getBudgetOverride()).contains(budget);
        }

        @Test
        @DisplayName("shares the transcript by reference but keeps the side fields independent")
        void sharesTranscriptButNotSideFields() {
            final SessionRecord source = new SessionRecord(SessionId.of("c-19"), "sys", List.of(HELLO, HI));

            final SessionRecord copy = SessionRecord.copyOf(source);

            // Sharing is the point of the copy path: it is what makes save/load cost zero list copies. It is only
            // safe because the transcript is immutable, so this identity assertion is the guard on that reasoning.
            assertThat(copy.getTranscript()).isSameAs(source.getTranscript());

            // The mutable halves must still be separate instances.
            copy.setAgentRef("copy-only");
            copy.setCompactionFailureCount(4);
            copy.setSystemPrompt("changed");

            assertThat(source.getAgentRef()).isEmpty();
            assertThat(source.getCompactionFailureCount()).isZero();
            assertThat(source.getSystemPrompt()).isEqualTo("sys");
            assertThat(source.getMessages()).containsExactly(HELLO, HI);
            assertThat(copy.getMessages()).containsExactly(HELLO, HI);
        }

        @Test
        @DisplayName("works from an arbitrary SessionRecordView implementation, not just SessionRecord")
        void worksFromForeignView() {
            final SessionRecordView view = new SessionRecordView() {
                @Override
                public SessionId getId() {
                    return SessionId.of("c-20");
                }

                @Override
                public String getSystemPrompt() {
                    return "foreign";
                }

                @Override
                public List<Message> getMessages() {
                    return List.of(HELLO);
                }

                @Override
                public Optional<String> getAgentRef() {
                    return Optional.of("agent-c");
                }

                @Override
                public int getCompactionFailureCount() {
                    return 1;
                }
            };

            final SessionRecord copy = SessionRecord.copyOf(view);

            assertThat(copy.getId()).isEqualTo(SessionId.of("c-20"));
            assertThat(copy.getSystemPrompt()).isEqualTo("foreign");
            assertThat(copy.getMessages()).containsExactly(HELLO);
            assertThat(copy.getAgentRef()).contains("agent-c");
            assertThat(copy.getCompactionFailureCount()).isEqualTo(1);
            // The view interface defaults these two, so the copy must land on the documented defaults rather than null.
            assertThat(copy.getSessionTotals()).isEqualTo(SessionTotals.empty());
            assertThat(copy.getBudgetOverride()).isEmpty();
        }

        @Test
        @DisplayName("rejects a null view")
        void rejectsNullView() {
            assertThatNullPointerException().isThrownBy(() -> SessionRecord.copyOf(null))
                    .withMessage("View cannot be null");
        }
    }

    @Nested
    @DisplayName("interop with SessionSnapshot")
    class SnapshotInterop {

        @Test
        @DisplayName("fromSnapshot() restores the history and defaults every side field the snapshot never carries")
        void fromSnapshotZeroesSideFields() {
            final SessionRecord source = new SessionRecord(SessionId.of("c-21"), "sys", List.of(HELLO, HI), 3,
                    "agent-d", SessionTotals.of(2, 5, TokenUsage.of(10, 4, 14)),
                    ExecutionBudget.builder().maxIterations(2).build());

            final SessionRecord restored = SessionRecord.fromSnapshot(SessionSnapshot.from(source));

            assertThat(restored.getId()).isEqualTo(source.getId());
            assertThat(restored.getSystemPrompt()).isEqualTo("sys");
            assertThat(restored.getMessages()).containsExactly(HELLO, HI);
            // All four side fields are deliberately outside the snapshot — they are preserved across saves by
            // SessionRecordStore#mergeFromSnapshot, not by the snapshot itself. The counter was the lone
            // exception until it was dropped from the snapshot; a round trip through a snapshot must not be able to
            // carry a second, stale value of a field the record owns.
            assertThat(restored.getCompactionFailureCount()).isZero();
            assertThat(restored.getAgentRef()).isEmpty();
            assertThat(restored.getSessionTotals()).isEqualTo(SessionTotals.empty());
            assertThat(restored.getBudgetOverride()).isEmpty();
        }
    }

    @Nested
    @DisplayName("toString()")
    class ToString {

        @Test
        @DisplayName("summarizes the history without leaking prompt or message content")
        void summarizesWithoutLeakingContent() {
            final SessionRecord record = new SessionRecord(SessionId.of("c-22"), "secret-prompt", List.of(HELLO, HI));

            final String rendered = record.toString();

            assertThat(rendered).contains("hasSystemPrompt=true").contains("messages=2").doesNotContain("secret-prompt")
                    .doesNotContain("hello");
        }
    }
}
