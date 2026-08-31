package at.aimon.core.llm.invoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.exception.LlmAuthException;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.exception.LlmOverloadedException;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.exception.LlmRateLimitedException;
import at.aimon.core.llm.retry.LlmFallbackPolicy;
import at.aimon.core.llm.retry.LlmRetryPolicy;
import at.aimon.core.llm.streaming.LlmStreamTarget;
import at.aimon.core.llm.streaming.LlmStreamingOptions;
import at.aimon.core.llm.streaming.StreamingRetryListener;

@DisplayName("LlmCallGateway")
class LlmCallGatewayTest {

    private static final String SYSTEM = "you are helpful";
    private static final List<Message> MESSAGES = List.of(Message.user("hello"));
    private static final List<ToolDefinition> TOOLS = List.of();

    private static LlmModel model(String name) {
        return LlmModel.builder().name(name).build();
    }

    private static LlmResponse okResponse(String text) {
        return LlmResponse.of(text, List.of());
    }

    /**
     * Characterization tests pinning the single shared "pass-through" gateway config that the agent and subagent
     * executors all build through {@link LlmCallGateway#withDefaultRetry(LlmClient)}. If a future change alters the
     * retry/fallback/prompt-too-long defaults, these fail loudly instead of silently diverging the execution paths.
     */
    @Nested
    @DisplayName("withDefaultRetry factory")
    class WithDefaultRetryFactory {

        @Test
        @DisplayName("uses the default retry policy, no fallback, no prompt-too-long handler, and the given client")
        void producesCanonicalConfig() {
            QueuedClient client = new QueuedClient();

            LlmCallGateway<Void> gateway = LlmCallGateway.withDefaultRetry(client);

            assertThat(gateway.getClient()).isSameAs(client);
            assertThat(gateway.getRetryPolicy()).isEqualTo(LlmRetryPolicy.defaultPolicy());
            assertThat(gateway.getFallbackPolicy()).isEqualTo(LlmFallbackPolicy.none());
            assertThat(gateway.getPromptTooLongHandler()).isEmpty();
        }

        @Test
        @DisplayName("rejects a null client")
        void rejectsNullClient() {
            assertThatThrownBy(() -> LlmCallGateway.withDefaultRetry(null)).isInstanceOf(NullPointerException.class);
        }
    }

    private static LlmRetryPolicy retryPolicy(int maxAttempts) {
        return LlmRetryPolicy.builder().maxAttempts(maxAttempts).backoffBase(Duration.ofMillis(10)).jitterFactor(0.0)
                .retryableExceptions(Set.of(LlmRateLimitedException.class, LlmOverloadedException.class)).build();
    }

    private static LlmFallbackPolicy fallbackPolicy(List<LlmModel> chain,
            Set<Class<? extends LlmClientException>> activating) {
        return LlmFallbackPolicy.builder().fallbackChain(chain).activatingExceptions(activating).build();
    }

    private static LlmCallGateway.Builder<Void> baseBuilder(QueuedClient client) {
        return LlmCallGateway.<Void>builder().client(client).retryPolicy(retryPolicy(3))
                .fallbackPolicy(LlmFallbackPolicy.none()).random(new Random(42)).sleeper(new RecordingSleeper());
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("returns response on first try with no retries or fallbacks")
        void returnsFirstResponse() {
            QueuedClient client = new QueuedClient();
            client.enqueueSuccess(okResponse("ok"));
            RecordingSleeper sleeper = new RecordingSleeper();

            LlmCallGateway<Void> gateway = LlmCallGateway.<Void>builder().client(client).retryPolicy(retryPolicy(3))
                    .sleeper(sleeper).build();

            LlmResponse response = gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, model("primary"));

            assertThat(response.getTextContent()).isEqualTo("ok");
            assertThat(client.callCount()).isEqualTo(1);
            assertThat(sleeper.records()).isEmpty();
        }
    }

    @Nested
    @DisplayName("retry")
    class Retry {

        @Test
        @DisplayName("retries on rate-limit and succeeds on third attempt")
        void retriesUntilSuccess() {
            QueuedClient client = new QueuedClient();
            client.enqueueFailure(new LlmRateLimitedException("429"));
            client.enqueueFailure(new LlmRateLimitedException("429"));
            client.enqueueSuccess(okResponse("ok"));

            RecordingSleeper sleeper = new RecordingSleeper();
            LlmCallGateway<Void> gateway = baseBuilder(client).sleeper(sleeper).build();

            LlmResponse response = gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, model("primary"));

            assertThat(response.getTextContent()).isEqualTo("ok");
            assertThat(client.callCount()).isEqualTo(3);
            // Two sleeps: before attempt 2 (attemptNumber=1) and before attempt 3 (attemptNumber=2).
            assertThat(sleeper.records()).hasSize(2);
            assertThat(sleeper.records().get(0)).isEqualTo(Duration.ofMillis(10));
            assertThat(sleeper.records().get(1)).isEqualTo(Duration.ofMillis(20));
        }
    }

    @Nested
    @DisplayName("Retry-After honoring")
    class RetryAfterHonoring {

        @Test
        @DisplayName("honors a server Retry-After hint instead of the computed backoff")
        void honorsServerRetryAfter() {
            QueuedClient client = new QueuedClient();
            client.enqueueFailure(new LlmRateLimitedException("429", Duration.ofSeconds(2)));
            client.enqueueSuccess(okResponse("ok"));

            RecordingSleeper sleeper = new RecordingSleeper();
            LlmCallGateway<Void> gateway = baseBuilder(client).sleeper(sleeper).build();

            LlmResponse response = gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, model("primary"));

            assertThat(response.getTextContent()).isEqualTo("ok");
            // The server hint (2s) wins over the policy's computed backoff (10ms).
            assertThat(sleeper.records()).containsExactly(Duration.ofSeconds(2));
        }

        @Test
        @DisplayName("clamps an excessive Retry-After hint to maxRetryAfter")
        void clampsExcessiveRetryAfter() {
            QueuedClient client = new QueuedClient();
            client.enqueueFailure(new LlmRateLimitedException("429", Duration.ofSeconds(999)));
            client.enqueueSuccess(okResponse("ok"));

            RecordingSleeper sleeper = new RecordingSleeper();
            LlmCallGateway<Void> gateway = baseBuilder(client).maxRetryAfter(Duration.ofSeconds(5)).sleeper(sleeper)
                    .build();

            gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, model("primary"));

            assertThat(sleeper.records()).containsExactly(Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("falls back to the computed backoff when the rate-limit carries no hint")
        void fallsBackWhenNoHint() {
            QueuedClient client = new QueuedClient();
            client.enqueueFailure(new LlmRateLimitedException("429"));
            client.enqueueSuccess(okResponse("ok"));

            RecordingSleeper sleeper = new RecordingSleeper();
            LlmCallGateway<Void> gateway = baseBuilder(client).sleeper(sleeper).build();

            gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, model("primary"));

            // No server hint → policy backoff (backoffBase 10ms, jitter 0) is used.
            assertThat(sleeper.records()).containsExactly(Duration.ofMillis(10));
        }

        @Test
        @DisplayName("overload failures (no Retry-After) use the computed backoff")
        void overloadedUsesComputedBackoff() {
            QueuedClient client = new QueuedClient();
            client.enqueueFailure(new LlmOverloadedException("503"));
            client.enqueueSuccess(okResponse("ok"));

            RecordingSleeper sleeper = new RecordingSleeper();
            LlmCallGateway<Void> gateway = baseBuilder(client).sleeper(sleeper).build();

            gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, model("primary"));

            assertThat(sleeper.records()).containsExactly(Duration.ofMillis(10));
        }
    }

    @Nested
    @DisplayName("retry exhausted")
    class RetryExhausted {

        @Test
        @DisplayName("propagates final exception after max attempts with prior errors suppressed")
        void propagatesAfterExhaustion() {
            QueuedClient client = new QueuedClient();
            LlmRateLimitedException e1 = new LlmRateLimitedException("429-1");
            LlmRateLimitedException e2 = new LlmRateLimitedException("429-2");
            LlmRateLimitedException e3 = new LlmRateLimitedException("429-3");
            client.enqueueFailure(e1);
            client.enqueueFailure(e2);
            client.enqueueFailure(e3);

            RecordingSleeper sleeper = new RecordingSleeper();
            LlmCallGateway<Void> gateway = baseBuilder(client).sleeper(sleeper).build();

            assertThatThrownBy(() -> gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, model("primary"))).isSameAs(e3)
                    .hasSuppressedException(e2);
            assertThat(client.callCount()).isEqualTo(3);
            assertThat(sleeper.records()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("fallback")
    class Fallback {

        @Test
        @DisplayName("switches to next model on activating exception and succeeds")
        void fallsOverToSecondModel() {
            LlmModel primary = model("primary");
            LlmModel secondary = model("secondary");

            QueuedClient client = new QueuedClient();
            client.enqueueFailure(new LlmOverloadedException("503"));
            client.enqueueSuccess(okResponse("from-secondary"));

            RecordingSleeper sleeper = new RecordingSleeper();
            LlmCallGateway<Void> gateway = baseBuilder(client)
                    .fallbackPolicy(fallbackPolicy(List.of(primary, secondary), Set.of(LlmOverloadedException.class)))
                    .sleeper(sleeper).build();

            LlmResponse response = gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, primary);

            assertThat(response.getTextContent()).isEqualTo("from-secondary");
            assertThat(client.callCount()).isEqualTo(2);
            assertThat(client.modelsUsed()).containsExactly(primary, secondary);
            // Fallback should not sleep — sleep is only for retry-policy-driven retries.
            assertThat(sleeper.records()).isEmpty();
        }
    }

    @Nested
    @DisplayName("fallback exhausted")
    class FallbackExhausted {

        @Test
        @DisplayName("propagates final exception after all chain members overload")
        void exhaustsChain() {
            LlmModel primary = model("primary");
            LlmModel secondary = model("secondary");

            QueuedClient client = new QueuedClient();
            LlmOverloadedException e1 = new LlmOverloadedException("503-1");
            LlmOverloadedException e2 = new LlmOverloadedException("503-2");
            client.enqueueFailure(e1);
            // After fallback, the retry policy may still run for the second model (maxAttempts=3, but we'll stop early
            // because we enqueue only failures to fully consume). Second model: one overload, then retry policy may
            // attempt again — enqueue enough failures.
            client.enqueueFailure(e2);
            LlmOverloadedException e3 = new LlmOverloadedException("503-3");
            client.enqueueFailure(e3);

            // Use a retry policy with maxAttempts=1 on the second model so we don't need too many enqueues.
            // We can't differentiate per-model retry caps in the policy, so use maxAttempts=2 and enqueue matching.
            LlmCallGateway<Void> gateway = LlmCallGateway.<Void>builder().client(client).retryPolicy(retryPolicy(2))
                    .fallbackPolicy(fallbackPolicy(List.of(primary, secondary), Set.of(LlmOverloadedException.class)))
                    .sleeper(new RecordingSleeper()).random(new Random(42)).build();

            assertThatThrownBy(() -> gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, primary))
                    .isInstanceOf(LlmOverloadedException.class);
            // primary: attempt 1 fails -> fallback to secondary (reset attempts).
            // secondary attempt 1 fails -> no fallback left -> retry (maxAttempts=2, so attempt becomes 2).
            // secondary attempt 2 fails -> no more retries -> propagate.
            assertThat(client.callCount()).isEqualTo(3);
            assertThat(client.modelsUsed()).containsExactly(primary, secondary, secondary);
        }
    }

    @Nested
    @DisplayName("consecutive-overload fallback threshold")
    class ConsecutiveOverloadFallback {

        private LlmFallbackPolicy thresholdPolicy(List<LlmModel> chain, int threshold) {
            return LlmFallbackPolicy.builder().fallbackChain(chain)
                    .activatingExceptions(Set.of(LlmOverloadedException.class)).consecutiveFailureThreshold(threshold)
                    .build();
        }

        @Test
        @DisplayName("retries the same model until the threshold, then escalates")
        void retriesSameModelUntilThreshold() {
            LlmModel primary = model("primary");
            LlmModel secondary = model("secondary");

            QueuedClient client = new QueuedClient();
            client.enqueueFailure(new LlmOverloadedException("503-1"));
            client.enqueueFailure(new LlmOverloadedException("503-2"));
            client.enqueueSuccess(okResponse("from-secondary"));

            RecordingSleeper sleeper = new RecordingSleeper();
            LlmCallGateway<Void> gateway = baseBuilder(client)
                    .fallbackPolicy(thresholdPolicy(List.of(primary, secondary), 2)).sleeper(sleeper).build();

            LlmResponse response = gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, primary);

            assertThat(response.getTextContent()).isEqualTo("from-secondary");
            // Two consecutive overloads on primary reach threshold=2 → escalate; secondary then succeeds.
            assertThat(client.modelsUsed()).containsExactly(primary, primary, secondary);
            // Only the same-model retry (primary attempt 1 -> attempt 2) sleeps; escalation never sleeps.
            assertThat(sleeper.records()).containsExactly(Duration.ofMillis(10));
        }

        @Test
        @DisplayName("a non-activating failure resets the consecutive counter")
        void nonActivatingFailureResetsCounter() {
            LlmModel primary = model("primary");
            LlmModel secondary = model("secondary");

            QueuedClient client = new QueuedClient();
            client.enqueueFailure(new LlmOverloadedException("503-1")); // activating -> count 1
            client.enqueueFailure(new LlmRateLimitedException("429")); // non-activating -> count reset to 0
            client.enqueueFailure(new LlmOverloadedException("503-2")); // activating -> count 1 (< threshold 2)
            client.enqueueSuccess(okResponse("from-primary"));

            LlmCallGateway<Void> gateway = LlmCallGateway.<Void>builder().client(client).retryPolicy(retryPolicy(5))
                    .fallbackPolicy(thresholdPolicy(List.of(primary, secondary), 2)).sleeper(new RecordingSleeper())
                    .random(new Random(42)).build();

            LlmResponse response = gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, primary);

            assertThat(response.getTextContent()).isEqualTo("from-primary");
            // The interleaved rate-limit resets the counter, so the second overload never reaches threshold=2 and
            // the gateway never escalates to the secondary model.
            assertThat(client.modelsUsed()).containsExactly(primary, primary, primary, primary);
        }

        @Test
        @DisplayName("escalates as a last resort when the retry budget is exhausted before the threshold")
        void escalatesWhenRetryBudgetExhaustedBeforeThreshold() {
            LlmModel primary = model("primary");
            LlmModel secondary = model("secondary");

            QueuedClient client = new QueuedClient();
            client.enqueueFailure(new LlmOverloadedException("503-1"));
            client.enqueueFailure(new LlmOverloadedException("503-2"));
            client.enqueueSuccess(okResponse("from-secondary"));

            // A high threshold (5) the 2-attempt retry budget can never reach: the exhausted budget must still trigger
            // the last-resort fallback to the next model.
            LlmCallGateway<Void> gateway = LlmCallGateway.<Void>builder().client(client).retryPolicy(retryPolicy(2))
                    .fallbackPolicy(thresholdPolicy(List.of(primary, secondary), 5)).sleeper(new RecordingSleeper())
                    .random(new Random(42)).build();

            LlmResponse response = gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, primary);

            assertThat(response.getTextContent()).isEqualTo("from-secondary");
            assertThat(client.modelsUsed()).containsExactly(primary, primary, secondary);
        }

        @Test
        @DisplayName("default threshold of 1 escalates on the first activating failure without a same-model retry")
        void defaultThresholdEscalatesImmediately() {
            LlmModel primary = model("primary");
            LlmModel secondary = model("secondary");

            QueuedClient client = new QueuedClient();
            client.enqueueFailure(new LlmOverloadedException("503"));
            client.enqueueSuccess(okResponse("from-secondary"));

            RecordingSleeper sleeper = new RecordingSleeper();
            LlmCallGateway<Void> gateway = baseBuilder(client)
                    .fallbackPolicy(thresholdPolicy(List.of(primary, secondary), 1)).sleeper(sleeper).build();

            LlmResponse response = gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, primary);

            assertThat(response.getTextContent()).isEqualTo("from-secondary");
            assertThat(client.modelsUsed()).containsExactly(primary, secondary);
            assertThat(sleeper.records()).isEmpty();
        }

        @Test
        @DisplayName("resets the consecutive counter on a successful model switch (needs a 3-model chain to observe)")
        void resetsCounterOnModelSwitch() {
            LlmModel primary = model("primary");
            LlmModel secondary = model("secondary");
            LlmModel tertiary = model("tertiary");

            QueuedClient client = new QueuedClient();
            client.enqueueFailure(new LlmOverloadedException("p-1")); // primary count 1
            client.enqueueFailure(new LlmOverloadedException("p-2")); // primary count 2 -> switch to secondary
            client.enqueueFailure(new LlmOverloadedException("s-1")); // secondary count 1 (proves reset from 2)
            client.enqueueFailure(new LlmOverloadedException("s-2")); // secondary count 2 -> switch to tertiary
            client.enqueueSuccess(okResponse("from-tertiary"));

            RecordingSleeper sleeper = new RecordingSleeper();
            LlmCallGateway<Void> gateway = LlmCallGateway.<Void>builder().client(client).retryPolicy(retryPolicy(5))
                    .fallbackPolicy(thresholdPolicy(List.of(primary, secondary, tertiary), 2)).sleeper(sleeper)
                    .random(new Random(42)).build();

            LlmResponse response = gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, primary);

            assertThat(response.getTextContent()).isEqualTo("from-tertiary");
            // Secondary is called TWICE. Had the counter carried the value 2 across the switch instead of resetting,
            // secondary's first overload would already satisfy threshold=2 and escalate immediately — yielding a single
            // secondary call: [primary, primary, secondary, tertiary]. The extra secondary call pins invariant 2(a).
            assertThat(client.modelsUsed()).containsExactly(primary, primary, secondary, secondary, tertiary);
            // One same-model retry sleep per model before its threshold-triggered switch.
            assertThat(sleeper.records()).containsExactly(Duration.ofMillis(10), Duration.ofMillis(10));
        }

        @Test
        @DisplayName("parts-aware sendMessage overload applies the same threshold escalation")
        void partsAwareOverloadEscalatesAfterThreshold() {
            LlmModel primary = model("primary");
            LlmModel secondary = model("secondary");

            QueuedClient client = new QueuedClient();
            client.enqueueFailure(new LlmOverloadedException("503-1"));
            client.enqueueFailure(new LlmOverloadedException("503-2"));
            client.enqueueSuccess(okResponse("from-secondary"));

            RecordingSleeper sleeper = new RecordingSleeper();
            LlmCallGateway<Void> gateway = baseBuilder(client)
                    .fallbackPolicy(thresholdPolicy(List.of(primary, secondary), 2)).sleeper(sleeper).build();

            LlmResponse response = gateway.sendMessage(SystemPromptParts.empty(), MESSAGES, TOOLS, primary,
                    LlmCallMetadata.empty());

            assertThat(response.getTextContent()).isEqualTo("from-secondary");
            assertThat(client.modelsUsed()).containsExactly(primary, primary, secondary);
            assertThat(sleeper.records()).containsExactly(Duration.ofMillis(10));
        }

        @Test
        @DisplayName("a handler-driven prompt-too-long retry leaves the consecutive counter untouched (invariant 5)")
        void promptTooLongRetryDoesNotDisturbConsecutiveCounter() {
            LlmModel primary = model("primary");
            LlmModel secondary = model("secondary");

            QueuedClient client = new QueuedClient();
            client.enqueueFailure(new LlmOverloadedException("503-1")); // activating -> count 1
            client.enqueueFailure(new LlmPromptTooLongException("too long")); // handler RETRY, counter must stay 1
            client.enqueueFailure(new LlmOverloadedException("503-2")); // activating -> count 2 -> escalate
            client.enqueueSuccess(okResponse("from-secondary"));

            PromptTooLongHandler<Void> handler = event -> Optional.of(HandlerOutcome.RETRY);
            LlmCallGateway<Void> gateway = LlmCallGateway.<Void>builder().client(client).retryPolicy(retryPolicy(5))
                    .fallbackPolicy(thresholdPolicy(List.of(primary, secondary), 2)).promptTooLongHandler(handler)
                    .sleeper(new RecordingSleeper()).random(new Random(42)).build();

            LlmResponse response = gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, primary);

            assertThat(response.getTextContent()).isEqualTo("from-secondary");
            // The interposed PTL retry neither reset nor incremented the counter: the second overload still reaches
            // threshold=2 and escalates. Had PTL reset the counter, the second overload would be count=1 (< 2) and the
            // gateway would retry primary instead — never reaching the secondary model.
            assertThat(client.modelsUsed()).containsExactly(primary, primary, primary, secondary);
        }
    }

    @Nested
    @DisplayName("consecutive-overload fallback (streaming loop)")
    class StreamingConsecutiveOverloadFallback {

        private LlmFallbackPolicy thresholdPolicy(List<LlmModel> chain, int threshold) {
            return LlmFallbackPolicy.builder().fallbackChain(chain)
                    .activatingExceptions(Set.of(LlmOverloadedException.class)).consecutiveFailureThreshold(threshold)
                    .build();
        }

        @Test
        @DisplayName("direct mode: escalates after the threshold and fires retry + fallback onRetry callbacks")
        void streamingEscalatesAfterThresholdAndFiresCallbacks() {
            LlmModel primary = model("primary");
            LlmModel secondary = model("secondary");

            QueuedClient client = new QueuedClient();
            client.enqueueFailure(new LlmOverloadedException("503-1"));
            client.enqueueFailure(new LlmOverloadedException("503-2"));
            client.enqueueSuccess(okResponse("from-secondary"));

            RecordingSleeper sleeper = new RecordingSleeper();
            RecordingListener listener = new RecordingListener();
            LlmCallGateway<Void> gateway = baseBuilder(client)
                    .fallbackPolicy(thresholdPolicy(List.of(primary, secondary), 2)).sleeper(sleeper).build();

            LlmResponse response = gateway.sendMessageStreaming(SystemPromptParts.empty(), MESSAGES, TOOLS, primary,
                    LlmCallMetadata.empty(),
                    LlmStreamTarget.builder().options(LlmStreamingOptions.defaults()).sink(chunk -> {
                    }).retryListener(listener).build());

            assertThat(response.getTextContent()).isEqualTo("from-secondary");
            assertThat(client.modelsUsed()).containsExactly(primary, primary, secondary);
            assertThat(sleeper.records()).containsExactly(Duration.ofMillis(10));
            // Direct mode surfaces both callbacks: the same-model retry (attempt 0->1) then the model switch
            // (attempt 1->0, "fallback_model"), the latter fired BEFORE the attempt counter is reset.
            assertThat(listener.events).containsExactly("0->1:LlmOverloadedException", "1->0:fallback_model");
        }

        @Test
        @DisplayName("buffered mode: suppresses onRetry callbacks while still escalating on the threshold")
        void streamingBufferedModeSuppressesRetryCallbacks() {
            LlmModel primary = model("primary");
            LlmModel secondary = model("secondary");

            QueuedClient client = new QueuedClient();
            client.enqueueFailure(new LlmOverloadedException("503-1"));
            client.enqueueFailure(new LlmOverloadedException("503-2"));
            client.enqueueSuccess(okResponse("from-secondary"));

            RecordingListener listener = new RecordingListener();
            LlmStreamingOptions buffered = LlmStreamingOptions.builder().bufferUntilFirstSuccess(true).build();
            LlmCallGateway<Void> gateway = baseBuilder(client)
                    .fallbackPolicy(thresholdPolicy(List.of(primary, secondary), 2)).build();

            LlmResponse response = gateway.sendMessageStreaming(SystemPromptParts.empty(), MESSAGES, TOOLS, primary,
                    LlmCallMetadata.empty(), LlmStreamTarget.builder().options(buffered).sink(chunk -> {
                    }).retryListener(listener).build());

            assertThat(response.getTextContent()).isEqualTo("from-secondary");
            assertThat(client.modelsUsed()).containsExactly(primary, primary, secondary);
            // Buffered mode never exposed partial chunks to the outer sink, so no reset callback is emitted.
            assertThat(listener.events).isEmpty();
        }
    }

    @Nested
    @DisplayName("prompt too long")
    class PromptTooLong {

        @Test
        @DisplayName("handler returns RETRY and gateway reattempts with same model to success")
        void handlerRetrySucceeds() {
            QueuedClient client = new QueuedClient();
            LlmPromptTooLongException ptl = new LlmPromptTooLongException("context exceeded");
            client.enqueueFailure(ptl);
            client.enqueueSuccess(okResponse("compacted-ok"));

            AtomicInteger handlerInvocations = new AtomicInteger();
            PromptTooLongHandler<Void> handler = event -> {
                handlerInvocations.incrementAndGet();
                assertThat(event.getException()).isSameAs(ptl);
                assertThat(event.getAttempt()).isEqualTo(1);
                return Optional.of(HandlerOutcome.RETRY);
            };

            LlmCallGateway<Void> gateway = baseBuilder(client).promptTooLongHandler(handler).build();

            LlmResponse response = gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, model("primary"));

            assertThat(response.getTextContent()).isEqualTo("compacted-ok");
            assertThat(handlerInvocations.get()).isEqualTo(1);
            assertThat(client.callCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("handler-driven retry cap stops the gateway and propagates the last exception")
        void handlerRetryCapStops() {
            QueuedClient client = new QueuedClient();
            // Always fail with prompt-too-long.
            for (int i = 0; i < 10; i++) {
                client.enqueueFailure(new LlmPromptTooLongException("still too long " + i));
            }

            PromptTooLongHandler<Void> handler = event -> Optional.of(HandlerOutcome.RETRY);

            LlmCallGateway<Void> gateway = baseBuilder(client).promptTooLongHandler(handler)
                    .maxPromptTooLongHandlerRetries(2).build();

            assertThatThrownBy(() -> gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, model("primary")))
                    .isInstanceOf(LlmPromptTooLongException.class);
            // Invocations: 1 (initial fail) + 2 (handler-driven retries) = 3 total calls.
            assertThat(client.callCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("event carries the memory snapshot supplied to the gateway")
        void eventCarriesMemorySnapshot() {
            QueuedClient client = new QueuedClient();
            LlmPromptTooLongException ptl = new LlmPromptTooLongException("context exceeded");
            client.enqueueFailure(ptl);
            client.enqueueSuccess(okResponse("ok"));

            String memory = "snapshot-42";
            List<String> observed = new ArrayList<>();
            PromptTooLongHandler<String> handler = event -> {
                observed.add(event.getMemorySnapshot().orElse(null));
                return Optional.of(HandlerOutcome.RETRY);
            };

            LlmCallGateway<String> gateway = LlmCallGateway.<String>builder().client(client).retryPolicy(retryPolicy(3))
                    .promptTooLongHandler(handler).memorySnapshotSupplier(() -> memory).sleeper(new RecordingSleeper())
                    .random(new Random(42)).build();

            gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, model("primary"));

            assertThat(observed).containsExactly("snapshot-42");
        }
    }

    @Nested
    @DisplayName("prompt too long abort")
    class PromptTooLongAbort {

        @Test
        @DisplayName("handler returns ABORT and the original exception propagates")
        void abortPropagates() {
            QueuedClient client = new QueuedClient();
            LlmPromptTooLongException ptl = new LlmPromptTooLongException("context exceeded");
            client.enqueueFailure(ptl);

            PromptTooLongHandler<Void> handler = event -> Optional.of(HandlerOutcome.ABORT);
            LlmCallGateway<Void> gateway = baseBuilder(client).promptTooLongHandler(handler).build();

            assertThatThrownBy(() -> gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, model("primary"))).isSameAs(ptl);
            assertThat(client.callCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("handler returns empty Optional and the original exception propagates")
        void emptyOptionalTreatedAsAbort() {
            QueuedClient client = new QueuedClient();
            LlmPromptTooLongException ptl = new LlmPromptTooLongException("context exceeded");
            client.enqueueFailure(ptl);

            PromptTooLongHandler<Void> handler = event -> Optional.empty();
            LlmCallGateway<Void> gateway = baseBuilder(client).promptTooLongHandler(handler).build();

            assertThatThrownBy(() -> gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, model("primary"))).isSameAs(ptl);
            assertThat(client.callCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("prompt too long no handler")
    class PromptTooLongNoHandler {

        @Test
        @DisplayName("propagates original exception when no handler is injected")
        void rethrowsWithoutHandler() {
            QueuedClient client = new QueuedClient();
            LlmPromptTooLongException ptl = new LlmPromptTooLongException("context exceeded");
            client.enqueueFailure(ptl);

            LlmCallGateway<Void> gateway = baseBuilder(client).build();

            assertThatThrownBy(() -> gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, model("primary"))).isSameAs(ptl);
            assertThat(client.callCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("non-retryable")
    class NonRetryable {

        @Test
        @DisplayName("auth exception is rethrown immediately without retries")
        void authExceptionImmediateRethrow() {
            QueuedClient client = new QueuedClient();
            LlmAuthException auth = new LlmAuthException("401");
            client.enqueueFailure(auth);

            RecordingSleeper sleeper = new RecordingSleeper();
            LlmCallGateway<Void> gateway = baseBuilder(client).sleeper(sleeper).build();

            assertThatThrownBy(() -> gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, model("primary"))).isSameAs(auth);
            assertThat(client.callCount()).isEqualTo(1);
            assertThat(sleeper.records()).isEmpty();
        }

        @Test
        @DisplayName("cancellation on the legacy String-prompt overload is terminal even under an adversarial retry/fallback policy")
        void cancellationOnLegacyOverloadIsTerminal() {
            QueuedClient client = new QueuedClient();
            LlmCallCancelledException cancelled = new LlmCallCancelledException("caller aborted");
            client.enqueueFailure(cancelled);

            RecordingSleeper sleeper = new RecordingSleeper();
            // Adversarial policy: it would retry AND fall back on the entire LlmClientException hierarchy.
            // Cancellation must still be terminal — the legacy overload's catch guard rethrows before the policies
            // are ever consulted, and the policies themselves carve cancellation out as a further safety net.
            LlmCallGateway<Void> gateway = LlmCallGateway.<Void>builder().client(client)
                    .retryPolicy(LlmRetryPolicy.builder().maxAttempts(3).backoffBase(Duration.ofMillis(10))
                            .jitterFactor(0.0).retryableExceptions(Set.of(LlmClientException.class)).build())
                    .fallbackPolicy(
                            LlmFallbackPolicy.builder().fallbackChain(List.of(model("primary"), model("secondary")))
                                    .activatingExceptions(Set.of(LlmClientException.class)).build())
                    .random(new Random(42)).sleeper(sleeper).build();

            assertThatThrownBy(() -> gateway.sendMessage(SYSTEM, MESSAGES, TOOLS, model("primary")))
                    .isSameAs(cancelled);
            assertThat(client.callCount()).isEqualTo(1);
            assertThat(sleeper.records()).isEmpty();
        }
    }

    // ---------------- test fakes ----------------

    /**
     * In-memory fake {@link LlmClient} driven by a queue of enqueued outcomes.
     */
    static final class QueuedClient implements LlmClient {
        private final Deque<Outcome> queue = new ArrayDeque<>();
        private final List<LlmModel> modelsUsed = new ArrayList<>();

        void enqueueSuccess(LlmResponse response) {
            queue.add(new Outcome(response, null));
        }

        void enqueueFailure(LlmClientException exception) {
            queue.add(new Outcome(null, exception));
        }

        int callCount() {
            return modelsUsed.size();
        }

        List<LlmModel> modelsUsed() {
            return modelsUsed;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            modelsUsed.add(modelConfig);
            Outcome next = queue.poll();
            if (next == null) {
                throw new AssertionError("QueuedClient exhausted — test did not enqueue enough outcomes");
            }
            if (next.exception != null) {
                throw next.exception;
            }
            return next.response;
        }

        @Override
        public String getProviderName() {
            return "queued-fake";
        }

        private static final class Outcome {
            final LlmResponse response;
            final LlmClientException exception;

            Outcome(LlmResponse response, LlmClientException exception) {
                this.response = response;
                this.exception = exception;
            }
        }
    }

    /**
     * Recording {@link Sleeper} that captures requested durations without actually sleeping.
     */
    static final class RecordingSleeper implements Sleeper {
        private final List<Duration> records = new ArrayList<>();

        List<Duration> records() {
            return records;
        }

        @Override
        public void sleep(Duration duration) {
            records.add(duration);
        }
    }

    /**
     * Recording {@link StreamingRetryListener} that captures each {@code onRetry} invocation as
     * {@code "<previousAttempt>-><nextAttempt>:<reason>"} so tests can assert exact call order and arguments.
     */
    static final class RecordingListener implements StreamingRetryListener {
        private final List<String> events = new ArrayList<>();

        @Override
        public void onRetry(int previousAttempt, int nextAttempt, String reason) {
            events.add(previousAttempt + "->" + nextAttempt + ":" + reason);
        }
    }
}
