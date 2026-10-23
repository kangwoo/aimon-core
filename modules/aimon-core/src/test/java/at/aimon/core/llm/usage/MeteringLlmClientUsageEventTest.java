package at.aimon.core.llm.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ServiceTier;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;

/**
 * {@link MeteringLlmClient} delivering {@link LlmUsageEvent}s: the measured tier, cache counters, served model and
 * timing, on every call path, and the bridge that keeps four-argument recorders working.
 */
class MeteringLlmClientUsageEventTest {

    private static final Instant T0 = Instant.parse("2026-10-23T00:00:00Z");
    private static final TokenUsage MEASURED = TokenUsage.of(272001, 200, 272201, 50).withCacheTokens(270000, 1001);
    private static final LlmResponse RESPONSE = LlmResponse.of("ok", List.of(), MEASURED)
            .withServedBy("gpt-x-2026-01-01", ServiceTier.FLEX);

    private final EventRecorder recorder = new EventRecorder();
    private final ScriptedClient delegate = new ScriptedClient();
    private final MeteringLlmClient metering = new MeteringLlmClient(delegate, recorder, steppingClock());

    /** A clock that advances one second per reading: start = T0, completion = T0 + 1s. */
    private static Clock steppingClock() {
        return new Clock() {
            private int readings;

            @Override
            public Instant instant() {
                return T0.plusSeconds(readings++);
            }

            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }
        };
    }

    private static LlmModel model(String name) {
        return LlmModel.builder().name(name).build();
    }

    @Nested
    @DisplayName("completed calls")
    class Completed {

        @Test
        @DisplayName("blocking: the event carries the response's usage, tier and model, plus the call's timing")
        void blockingCall() {
            delegate.respond(() -> RESPONSE);

            metering.sendMessage("sys", List.of(), List.of(), model("gpt-x"), LlmCallMetadata.empty());

            assertThat(recorder.events).hasSize(1);
            final LlmUsageEvent event = recorder.events.get(0);
            assertThat(event.getOutcome()).isEqualTo(LlmUsageEvent.Outcome.COMPLETED);
            assertThat(event.getProvider()).isEqualTo("Scripted");
            assertThat(event.getRequestedModel()).contains("gpt-x");
            assertThat(event.getServedModel()).contains("gpt-x-2026-01-01");
            assertThat(event.getUsage()).contains(MEASURED);
            assertThat(event.getServiceTier()).contains(ServiceTier.FLEX);
            assertThat(event.getStartedAt()).isEqualTo(T0);
            assertThat(event.getCompletedAt()).isEqualTo(T0.plusSeconds(1));
            assertThat(event.getLatency()).isEqualTo(Duration.ofSeconds(1));
        }

        @Test
        @DisplayName("the response's tier wins: nothing about the request is recorded as the applied tier")
        void responseTierIsRecorded() {
            for (ServiceTier tier : List.of(ServiceTier.STANDARD, ServiceTier.FAST, ServiceTier.FLEX,
                    ServiceTier.BATCH)) {
                delegate.respond(() -> LlmResponse.of("ok", List.of(), MEASURED).withServedBy(null, tier));
                metering.sendMessage("sys", List.of(), List.of(), model("gpt-x"),
                        LlmCallMetadata.builder().tag(LlmUsageEvent.TAG_SERVICE_TIER, "standard").build());
            }

            assertThat(recorder.events).extracting(e -> e.getServiceTier().orElseThrow())
                    .containsExactly(ServiceTier.STANDARD, ServiceTier.FAST, ServiceTier.FLEX, ServiceTier.BATCH);
        }

        @Test
        @DisplayName("a tier the provider did not report stays empty — never defaulted to standard")
        void unreportedTierStaysEmpty() {
            delegate.respond(() -> LlmResponse.of("ok", List.of(), TokenUsage.of(10, 5, 15)));

            metering.sendMessage("sys", List.of(), List.of(), model("gpt-x"), LlmCallMetadata.empty());

            final LlmUsageEvent event = recorder.events.get(0);
            assertThat(event.getServiceTier()).isEmpty();
            assertThat(event.getServedModel()).isEmpty();
            assertThat(event.getModel()).contains("gpt-x");
            assertThat(event.getUsage().orElseThrow().getCacheReadTokens()).isEmpty();
        }

        @Test
        @DisplayName("streaming records exactly one event, identical in content to the blocking one")
        void streamingMatchesBlockingAndRecordsOnce() {
            delegate.respond(() -> RESPONSE);
            metering.sendMessage(SystemPromptParts.empty(), List.of(), List.of(), model("gpt-x"),
                    LlmCallMetadata.empty(), LlmCancellation.none());
            delegate.streamChunks(3);
            metering.sendMessageStreaming(SystemPromptParts.empty(), List.of(), List.of(), model("gpt-x"),
                    LlmCallMetadata.empty(), LlmStreamingOptions.defaults(), LlmStreamSink.discarding());

            assertThat(recorder.events).hasSize(2);
            final LlmUsageEvent blocking = recorder.events.get(0);
            final LlmUsageEvent streaming = recorder.events.get(1);
            assertThat(streaming.getUsage()).isEqualTo(blocking.getUsage());
            assertThat(streaming.getServiceTier()).isEqualTo(blocking.getServiceTier());
            assertThat(streaming.getServedModel()).isEqualTo(blocking.getServedModel());
            assertThat(streaming.getOutcome()).isEqualTo(LlmUsageEvent.Outcome.COMPLETED);
        }

        @Test
        @DisplayName("each actual call is its own event — a retried call records once per attempt that answered")
        void everyCallIsRecorded() {
            delegate.respond(() -> RESPONSE);

            metering.sendMessage("sys", List.of(), List.of(), model("gpt-x"), LlmCallMetadata.empty());
            metering.sendMessage("sys", List.of(), List.of(), model("gpt-x"), LlmCallMetadata.empty());

            assertThat(recorder.events).hasSize(2);
        }
    }

    @Nested
    @DisplayName("calls that throw")
    class Aborted {

        @Test
        @DisplayName("a stream that fails after the first chunk is recorded as ABORTED with no usage, and rethrown")
        void streamFailingMidwayIsAborted() {
            delegate.streamChunksThenFail(2, new IllegalStateException("connection reset"));

            assertThatThrownBy(
                    () -> metering.sendMessageStreaming(SystemPromptParts.empty(), List.of(), List.of(), model("gpt-x"),
                            LlmCallMetadata.empty(), LlmStreamingOptions.defaults(), LlmStreamSink.discarding()))
                    .hasMessage("connection reset");

            assertThat(recorder.events).hasSize(1);
            final LlmUsageEvent event = recorder.events.get(0);
            assertThat(event.getOutcome()).isEqualTo(LlmUsageEvent.Outcome.ABORTED);
            assertThat(event.getUsage()).isEmpty();
            assertThat(event.getRequestedModel()).contains("gpt-x");
            assertThat(event.getCompletedAt()).isEqualTo(T0.plusSeconds(1));
        }

        @Test
        @DisplayName("a stream that fails before any chunk records nothing — nothing was answered")
        void streamFailingBeforeAnswerRecordsNothing() {
            delegate.streamChunksThenFail(0, new IllegalStateException("401"));

            assertThatThrownBy(
                    () -> metering.sendMessageStreaming(SystemPromptParts.empty(), List.of(), List.of(), model("gpt-x"),
                            LlmCallMetadata.empty(), LlmStreamingOptions.defaults(), LlmStreamSink.discarding()))
                    .hasMessage("401");

            assertThat(recorder.events).isEmpty();
        }

        @Test
        @DisplayName("a blocking call cut by its cancellation token is recorded as ABORTED")
        void cancelledInFlightIsAborted() {
            final Trippable token = new Trippable();
            delegate.respond(() -> {
                token.trip();
                throw new IllegalStateException("cancelled");
            });

            assertThatThrownBy(() -> metering.sendMessage(SystemPromptParts.empty(), List.of(), List.of(),
                    model("gpt-x"), LlmCallMetadata.empty(), token)).hasMessage("cancelled");

            assertThat(recorder.events).singleElement()
                    .satisfies(e -> assertThat(e.getOutcome()).isEqualTo(LlmUsageEvent.Outcome.ABORTED));
        }

        @Test
        @DisplayName("a call whose token was already tripped is never sent, so nothing is recorded")
        void cancelledBeforeSendRecordsNothing() {
            final Trippable token = new Trippable();
            token.trip();
            delegate.respond(() -> {
                throw new IllegalStateException("cancelled before start");
            });

            assertThatThrownBy(() -> metering.sendMessage(SystemPromptParts.empty(), List.of(), List.of(),
                    model("gpt-x"), LlmCallMetadata.empty(), token)).isInstanceOf(IllegalStateException.class);

            assertThat(recorder.events).isEmpty();
        }

        @Test
        @DisplayName("a plain blocking failure records nothing")
        void blockingFailureRecordsNothing() {
            delegate.respond(() -> {
                throw new IllegalStateException("500");
            });

            assertThatThrownBy(
                    () -> metering.sendMessage("sys", List.of(), List.of(), model("gpt-x"), LlmCallMetadata.empty()))
                    .hasMessage("500");

            assertThat(recorder.events).isEmpty();
        }
    }

    @Nested
    @DisplayName("four-argument recorders")
    class LegacyBridge {

        private final List<LlmCallMetadata> received = new ArrayList<>();
        private final LlmUsageRecorder legacy = (provider, model, usage, metadata) -> received.add(metadata);
        private final MeteringLlmClient legacyMetering = new MeteringLlmClient(delegate, legacy, steppingClock());

        @Test
        @DisplayName("measured tier and cache counters arrive as tags; the caller's metadata object is untouched")
        void measuredValuesArriveAsTags() {
            delegate.respond(() -> RESPONSE);
            final LlmCallMetadata callerMetadata = LlmCallMetadata.builder().component("orca").tag("tenant", "acme")
                    .build();

            legacyMetering.sendMessage("sys", List.of(), List.of(), model("gpt-x"), callerMetadata);

            assertThat(received).singleElement().satisfies(metadata -> {
                assertThat(metadata.getComponent()).contains("orca");
                assertThat(metadata.getTags()).containsEntry("tenant", "acme")
                        .containsEntry(LlmUsageEvent.TAG_SERVICE_TIER, "flex")
                        .containsEntry(LlmUsageEvent.TAG_CACHED_INPUT_TOKENS, "270000")
                        .containsEntry(LlmUsageEvent.TAG_CACHE_WRITE_TOKENS, "1001");
            });
            assertThat(callerMetadata.getTags()).containsOnlyKeys("tenant");
        }

        @Test
        @DisplayName("caller tags under reserved keys are removed, not mistaken for measurements")
        void spoofedTagsAreRemoved() {
            delegate.respond(() -> LlmResponse.of("ok", List.of(), TokenUsage.of(10, 5, 15)));
            final LlmCallMetadata spoofed = LlmCallMetadata.builder().tag(LlmUsageEvent.TAG_SERVICE_TIER, "standard")
                    .tag(LlmUsageEvent.TAG_CACHED_INPUT_TOKENS, "0").tag(LlmUsageEvent.TAG_CACHE_WRITE_TOKENS, "0")
                    .tag(LlmUsageEvent.TAG_CACHE_USAGE_KNOWN, "true").tag(LlmUsageEvent.TAG_CONTEXT_INPUT_TOKENS, "10")
                    .tag("tenant", "acme").build();

            legacyMetering.sendMessage("sys", List.of(), List.of(), model("gpt-x"), spoofed);

            assertThat(received).singleElement()
                    .satisfies(metadata -> assertThat(metadata.getTags()).containsOnlyKeys("tenant"));
        }

        @Test
        @DisplayName("a measured zero is written; an unmeasured counter is left out")
        void zeroIsWrittenUnknownIsOmitted() {
            delegate.respond(() -> LlmResponse.of("ok", List.of(), TokenUsage.of(10, 5, 15).withCacheTokens(0, null)));

            legacyMetering.sendMessage("sys", List.of(), List.of(), model("gpt-x"), LlmCallMetadata.empty());

            assertThat(received).singleElement()
                    .satisfies(metadata -> assertThat(metadata.getTags())
                            .containsEntry(LlmUsageEvent.TAG_CACHED_INPUT_TOKENS, "0")
                            .doesNotContainKey(LlmUsageEvent.TAG_CACHE_WRITE_TOKENS)
                            .doesNotContainKey(LlmUsageEvent.TAG_SERVICE_TIER));
        }

        @Test
        @DisplayName("aborted calls are not forwarded — they have no usage to report")
        void abortedCallsAreNotForwarded() {
            delegate.streamChunksThenFail(1, new IllegalStateException("reset"));

            assertThatThrownBy(() -> legacyMetering.sendMessageStreaming(SystemPromptParts.empty(), List.of(),
                    List.of(), model("gpt-x"), LlmCallMetadata.empty(), LlmStreamingOptions.defaults(),
                    LlmStreamSink.discarding())).isInstanceOf(IllegalStateException.class);

            assertThat(received).isEmpty();
        }
    }

    private static final class EventRecorder implements LlmUsageRecorder {
        private final List<LlmUsageEvent> events = new ArrayList<>();

        @Override
        public void record(String provider, String model, TokenUsage usage, LlmCallMetadata metadata) {
            throw new AssertionError("the typed method must be called");
        }

        @Override
        public void record(LlmUsageEvent event) {
            events.add(event);
        }
    }

    private static final class Trippable implements LlmCancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        void trip() {
            cancelled.set(true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public void onCancel(Runnable abort) {
        }
    }

    /** A delegate whose blocking answer and streaming behaviour each test scripts. */
    private static final class ScriptedClient implements LlmClient {
        private Supplier<LlmResponse> answer = () -> RESPONSE;
        private int chunks;
        private RuntimeException streamFailure;

        void respond(Supplier<LlmResponse> answer) {
            this.answer = answer;
        }

        void streamChunks(int chunks) {
            this.chunks = chunks;
            this.streamFailure = null;
        }

        void streamChunksThenFail(int chunks, RuntimeException failure) {
            this.chunks = chunks;
            this.streamFailure = failure;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return answer.get();
        }

        @Override
        public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata,
                LlmCancellation cancellation) {
            return answer.get();
        }

        @Override
        public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
                LlmStreamSink sink) {
            for (int i = 0; i < chunks; i++) {
                sink.accept(LlmStreamChunk.textDelta(i, "t"));
            }
            if (streamFailure != null) {
                throw streamFailure;
            }
            sink.accept(LlmStreamChunk.streamEnd(chunks, RESPONSE.getTokenUsage(), Optional.empty()));
            return answer.get();
        }

        @Override
        public String getProviderName() {
            return "Scripted";
        }
    }
}
