package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmModel;

@DisplayName("CompactionObserver: the no-op and the fail-safe wrapper")
class CompactionObserverTest {

    private static final CompactionStart START = CompactionStart.builder().trigger(CompactionTrigger.AUTO)
            .strategy(CompactionStart.VIEW).messagesBefore(3).build();
    private static final LlmCallMetadata BASE = LlmCallMetadata.builder().component("caller").build();

    private static CompactionResult success() {
        final Instant now = Instant.now();
        return CompactionResult.success("s",
                CompactionMetadata.builder().trigger(CompactionTrigger.AUTO).startedAt(now).completedAt(now).build());
    }

    @Nested
    @DisplayName("none()")
    class None {

        @Test
        @DisplayName("enrich returns the base instance itself, and null stays null")
        void enrichReturnsTheBaseInstance() {
            final CompactionObserver.Attempt attempt = CompactionObserver.none().begin(START);

            assertThat(attempt.enrich(BASE)).isSameAs(BASE);
            assertThat(attempt.enrich(null)).isNull();
        }

        @Test
        @DisplayName("record returns its argument and close does nothing")
        void recordReturnsItsArgument() {
            final CompactionResult result = success();
            final CompactionObserver.Attempt attempt = CompactionObserver.none().begin(START);

            assertThat(attempt.record(result)).isSameAs(result);
            assertThatCode(attempt::close).doesNotThrowAnyException();
            assertThatCode(attempt::close).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("is a shared singleton")
        void isShared() {
            assertThat(CompactionObserver.none()).isSameAs(CompactionObserver.none());
        }
    }

    @Nested
    @DisplayName("failSafe()")
    class FailSafe {

        @Test
        @DisplayName("is idempotent and leaves none() as it is")
        void isIdempotent() {
            final CompactionObserver observer = new RecordingCompactionObserver();
            final CompactionObserver wrapped = CompactionObserver.failSafe(observer);

            assertThat(CompactionObserver.failSafe(wrapped)).isSameAs(wrapped);
            assertThat(CompactionObserver.failSafe(CompactionObserver.none())).isSameAs(CompactionObserver.none());
            assertThatThrownBy(() -> CompactionObserver.failSafe(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("passes through to a well-behaved observer")
        void passesThrough() {
            final RecordingCompactionObserver observer = new RecordingCompactionObserver();
            final CompactionResult result = success();

            try (CompactionObserver.Attempt attempt = CompactionObserver.failSafe(observer).begin(START)) {
                assertThat(attempt.enrich(BASE).getTags()).containsEntry(RecordingCompactionObserver.ATTEMPT_TAG, "1");
                assertThat(attempt.record(result)).isSameAs(result);
            }

            assertThat(observer.single().start()).isEqualTo(START);
            assertThat(observer.single().recorded()).containsExactly(result);
            assertThat(observer.single().closes()).isEqualTo(1);
        }

        @Test
        @DisplayName("a throwing begin yields a no-op attempt")
        void throwingBegin() {
            final CompactionObserver throwing = start -> {
                throw new IllegalStateException("observer down");
            };

            final CompactionObserver.Attempt attempt = CompactionObserver.failSafe(throwing).begin(START);

            assertThat(attempt.enrich(BASE)).isSameAs(BASE);
            assertThat(attempt.enrich(null)).isNull();
        }

        @Test
        @DisplayName("a begin returning null yields a no-op attempt")
        void nullAttempt() {
            final CompactionObserver.Attempt attempt = CompactionObserver.failSafe(start -> null).begin(START);

            assertThat(attempt.enrich(BASE)).isSameAs(BASE);
        }

        @Test
        @DisplayName("throwing enrich, record and close are swallowed; enrich then returns the base")
        void throwingAttempt() {
            final CompactionObserver observer = start -> new CompactionObserver.Attempt() {
                @Override
                public LlmCallMetadata enrich(LlmCallMetadata base) {
                    throw new IllegalStateException("enrich down");
                }

                @Override
                public CompactionResult record(CompactionResult result) {
                    throw new IllegalStateException("record down");
                }

                @Override
                public void close() {
                    throw new IllegalStateException("close down");
                }
            };
            final CompactionResult result = success();

            final CompactionObserver.Attempt attempt = CompactionObserver.failSafe(observer).begin(START);

            assertThat(attempt.enrich(BASE)).isSameAs(BASE);
            assertThat(attempt.record(result)).isSameAs(result);
            assertThatCode(attempt::close).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an enrich returning null falls back to the base")
        void nullEnrich() {
            final CompactionObserver observer = start -> new CompactionObserver.Attempt() {
                @Override
                public LlmCallMetadata enrich(LlmCallMetadata base) {
                    return null;
                }

                @Override
                public CompactionResult record(CompactionResult result) {
                    return null;
                }

                @Override
                public void close() {
                }
            };
            final CompactionResult result = success();

            final CompactionObserver.Attempt attempt = CompactionObserver.failSafe(observer).begin(START);

            assertThat(attempt.enrich(BASE)).isSameAs(BASE);
            assertThat(attempt.record(result)).as("the argument, whatever the delegate returned").isSameAs(result);
        }
    }

    @Nested
    @DisplayName("the requests")
    class Requests {

        @Test
        @DisplayName("default to none() and wrap what they are given")
        void defaultAndWrap() {
            final CompactionObserver observer = new RecordingCompactionObserver();
            final CompactionGuardRequest.Builder builder = CompactionGuardRequest.builder()
                    .transcriptBuffer(new TranscriptBuffer(SessionId.of("s")))
                    .model(LlmModel.builder().name("m").build()).hookRegistry(new DefaultHookRegistry());

            assertThat(builder.build().getCompactionObserver()).isSameAs(CompactionObserver.none());
            assertThat(builder.compactionObserver(null).build().getCompactionObserver())
                    .isSameAs(CompactionObserver.none());
            final CompactionObserver set = builder.compactionObserver(observer).build().getCompactionObserver();
            assertThat(set).isNotSameAs(observer).isSameAs(CompactionObserver.failSafe(set));
        }
    }

    @Test
    @DisplayName("CompactionStart validates its fields")
    void startValidates() {
        assertThatThrownBy(() -> CompactionStart.builder().strategy(CompactionStart.VIEW).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CompactionStart.builder().trigger(CompactionTrigger.AUTO).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CompactionStart.builder().trigger(CompactionTrigger.AUTO)
                .strategy(CompactionStart.VIEW).messagesBefore(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(START.getTrigger()).isEqualTo(CompactionTrigger.AUTO);
        assertThat(START.getStrategy()).isEqualTo("view");
        assertThat(START.isBlockingForced()).isFalse();
        assertThat(START.getMessagesBefore()).isEqualTo(3);
        assertThat(Map.of(START, 1)).containsKey(CompactionStart.builder().trigger(CompactionTrigger.AUTO)
                .strategy(CompactionStart.VIEW).messagesBefore(3).build());
    }
}
