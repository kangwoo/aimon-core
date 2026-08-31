package at.aimon.core.hook.rewake.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.rewake.RewakeEnvelope;
import at.aimon.core.hook.rewake.RewakeFireListener;
import at.aimon.core.hook.rewake.RewakeQuotaManager;
import at.aimon.core.hook.rewake.RewakeTrigger;
import at.aimon.core.hook.rewake.RewakeTriggerCron;
import at.aimon.core.hook.rewake.RewakeTriggerDelay;
import at.aimon.core.hook.rewake.RewakeTriggerEvent;

class DefaultRewakeServiceTest {

    private RecordingListener listener;
    private ScheduledExecutorService scheduler;
    private DefaultRewakeService service;

    @BeforeEach
    void setUp() {
        listener = new RecordingListener();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "test-rewake-scheduler");
            t.setDaemon(true);
            return t;
        });
        service = new DefaultRewakeService(listener, Clock.systemUTC(), scheduler);
    }

    @AfterEach
    void tearDown() {
        service.close();
        scheduler.shutdownNow();
    }

    @Test
    void delayTriggerFiresAfterConfiguredDuration() throws InterruptedException {
        listener.expect(1);
        final RewakeEnvelope env = envelope("env-delay", new RewakeTriggerDelay(Duration.ofMillis(20)));

        service.schedule(env);

        assertThat(listener.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(listener.fired()).hasSize(1);
        assertThat(listener.fired().get(0).getEnvelopeId()).isEqualTo("env-delay");
        assertThat(service.listPending()).isEmpty();
    }

    @Test
    void cancelRemovesPendingAndPreventsFire() throws InterruptedException {
        final RewakeEnvelope env = envelope("env-cancel", new RewakeTriggerDelay(Duration.ofSeconds(10)));
        service.schedule(env);
        assertThat(service.listPending()).hasSize(1);

        assertThat(service.cancel("env-cancel")).isTrue();
        assertThat(service.listPending()).isEmpty();

        Thread.sleep(50);
        assertThat(listener.fired()).isEmpty();
    }

    @Test
    void cancelUnknownReturnsFalse() {
        assertThat(service.cancel("does-not-exist")).isFalse();
    }

    @Test
    void cancelByOriginatingHookIdCancelsMatching() {
        service.schedule(envelopeBuilder("env-1", new RewakeTriggerDelay(Duration.ofSeconds(10)))
                .originatingHookId("hook-A").build());
        service.schedule(envelopeBuilder("env-2", new RewakeTriggerDelay(Duration.ofSeconds(10)))
                .originatingHookId("hook-A").build());
        service.schedule(envelopeBuilder("env-3", new RewakeTriggerDelay(Duration.ofSeconds(10)))
                .originatingHookId("hook-B").build());

        final int cancelled = service.cancelByOriginatingHookId("hook-A");

        assertThat(cancelled).isEqualTo(2);
        assertThat(service.listPending()).extracting(RewakeEnvelope::getEnvelopeId).containsExactly("env-3");
    }

    @Test
    void cancelByOriginatingHookIdRejectsBlank() {
        assertThatNullPointerException().isThrownBy(() -> service.cancelByOriginatingHookId(null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.cancelByOriginatingHookId("   "));
    }

    @Test
    void resolveFiresMatchingEventTriggersAndMergesPayload() throws InterruptedException {
        listener.expect(1);
        final RewakeEnvelope env = envelopeBuilder("env-event", new RewakeTriggerEvent("webhook", "ticket-42"))
                .payload("ticket", "T-42").build();
        service.schedule(env);

        final int fired = service.resolve("webhook", "ticket-42", Map.of("status", "approved"));

        assertThat(fired).isEqualTo(1);
        assertThat(listener.await(1, TimeUnit.SECONDS)).isTrue();
        final RewakeEnvelope delivered = listener.fired().get(0);
        assertThat(delivered.getPayload()).containsEntry("ticket", "T-42").containsEntry("status", "approved");
        assertThat(service.listPending()).isEmpty();
    }

    @Test
    void resolveIncomingPayloadOverridesExisting() throws InterruptedException {
        listener.expect(1);
        final RewakeEnvelope env = envelopeBuilder("env-event", new RewakeTriggerEvent("webhook", "k1"))
                .payload("status", "pending").build();
        service.schedule(env);

        service.resolve("webhook", "k1", Map.of("status", "approved"));

        assertThat(listener.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(listener.fired().get(0).getPayload()).containsEntry("status", "approved");
    }

    @Test
    void resolveDoesNotMatchOtherEvents() {
        final RewakeEnvelope env = envelopeBuilder("env-event", new RewakeTriggerEvent("webhook", "ticket-42")).build();
        service.schedule(env);

        assertThat(service.resolve("webhook", "different-key", Map.of())).isZero();
        assertThat(service.resolve("different-type", "ticket-42", Map.of())).isZero();
        assertThat(service.listPending()).hasSize(1);
        assertThat(listener.fired()).isEmpty();
    }

    @Test
    void resolveOnlyMatchesEventTriggers() {
        service.schedule(envelopeBuilder("env-delay", new RewakeTriggerDelay(Duration.ofSeconds(10))).build());

        assertThat(service.resolve("webhook", "any", Map.of())).isZero();
        assertThat(service.listPending()).hasSize(1);
    }

    @Test
    void resolveRejectsNullAndBlankArguments() {
        assertThatNullPointerException().isThrownBy(() -> service.resolve(null, "k", Map.of()));
        assertThatNullPointerException().isThrownBy(() -> service.resolve("t", null, Map.of()));
        assertThatNullPointerException().isThrownBy(() -> service.resolve("t", "k", null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.resolve("", "k", Map.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> service.resolve("t", "  ", Map.of()));

        final Map<String, String> withNullValue = new HashMap<>();
        withNullValue.put("k", null);
        assertThatNullPointerException().isThrownBy(() -> service.resolve("t", "k", withNullValue));
    }

    @Test
    void cronTriggerThrowsUnsupported() {
        final RewakeEnvelope env = envelopeBuilder("env-cron", new RewakeTriggerCron("0 * * * *", ZoneId.of("UTC")))
                .build();

        assertThatThrownBy(() -> service.schedule(env)).isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Quartz");
        assertThat(service.listPending()).isEmpty();
    }

    @Test
    void scheduleIdempotentReplacesPrevious() throws InterruptedException {
        listener.expect(1);
        final RewakeEnvelope first = envelope("env-same", new RewakeTriggerDelay(Duration.ofSeconds(10)));
        final RewakeEnvelope second = envelopeBuilder("env-same", new RewakeTriggerDelay(Duration.ofMillis(20)))
                .reason("replaced").build();

        service.schedule(first);
        service.schedule(second);

        assertThat(listener.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(listener.fired()).hasSize(1);
        assertThat(listener.fired().get(0).getReason()).isEqualTo("replaced");
    }

    @Test
    void listPendingReturnsImmutableSnapshot() {
        service.schedule(envelopeBuilder("a", new RewakeTriggerDelay(Duration.ofSeconds(10))).build());
        service.schedule(envelopeBuilder("b", new RewakeTriggerEvent("t", "k")).build());

        final List<RewakeEnvelope> snap = service.listPending();
        assertThat(snap).hasSize(2);
        assertThatThrownBy(() -> snap.add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void closeCancelsAllAndRejectsFurtherSchedules() {
        service.schedule(envelope("env-1", new RewakeTriggerDelay(Duration.ofSeconds(10))));
        service.schedule(envelopeBuilder("env-2", new RewakeTriggerEvent("t", "k")).build());

        service.close();

        assertThat(service.listPending()).isEmpty();
        assertThatIllegalStateException().isThrownBy(
                () -> service.schedule(envelope("env-after-close", new RewakeTriggerDelay(Duration.ofSeconds(1)))));
    }

    @Test
    void closeIsIdempotent() {
        service.close();
        service.close();
    }

    @Test
    void listenerExceptionsDoNotPropagateOnDelayFire() throws InterruptedException {
        final CountDownLatch fired = new CountDownLatch(1);
        final RewakeFireListener throwing = envelope -> {
            try {
                throw new RuntimeException("boom");
            } finally {
                fired.countDown();
            }
        };
        try (DefaultRewakeService throwingService = new DefaultRewakeService(throwing, Clock.systemUTC(), scheduler)) {
            throwingService.schedule(envelope("env-throw", new RewakeTriggerDelay(Duration.ofMillis(20))));
            assertThat(fired.await(2, TimeUnit.SECONDS)).isTrue();
            // No assertion on rethrown — getting here means the timer survived.
        }
    }

    @Test
    void listenerExceptionsDoNotPropagateOnResolveFire() {
        final RewakeFireListener throwing = envelope -> {
            throw new RuntimeException("boom");
        };
        try (DefaultRewakeService throwingService = new DefaultRewakeService(throwing, Clock.systemUTC(), scheduler)) {
            throwingService.schedule(envelopeBuilder("env-throw", new RewakeTriggerEvent("t", "k")).build());
            // Must return cleanly even though the listener throws.
            assertThat(throwingService.resolve("t", "k", Map.of())).isEqualTo(1);
        }
    }

    @Test
    void quotaRejectsScheduleWhenContextAtCap() {
        final DefaultRewakeQuotaManager quota = new DefaultRewakeQuotaManager(2);
        service.withQuotaManager(quota);
        final AgentRuntimeId ctxId = AgentRuntimeId.fromName("agent-x");

        service.schedule(envelope("env-1", new RewakeTriggerDelay(Duration.ofSeconds(10))));
        service.schedule(envelope("env-2", new RewakeTriggerDelay(Duration.ofSeconds(10))));
        // Third would exceed the cap (2) — quota refuses; envelope is silently dropped.
        service.schedule(envelope("env-3", new RewakeTriggerDelay(Duration.ofSeconds(10))));

        assertThat(service.listPending()).hasSize(2);
        assertThat(quota.getCurrentUsage(ctxId)).isEqualTo(2);
    }

    @Test
    void quotaIsReleasedOnCancel() {
        final DefaultRewakeQuotaManager quota = new DefaultRewakeQuotaManager(1);
        service.withQuotaManager(quota);
        final AgentRuntimeId ctxId = AgentRuntimeId.fromName("agent-x");

        service.schedule(envelope("env-1", new RewakeTriggerDelay(Duration.ofSeconds(10))));
        assertThat(quota.getCurrentUsage(ctxId)).isEqualTo(1);

        assertThat(service.cancel("env-1")).isTrue();
        assertThat(quota.getCurrentUsage(ctxId)).isZero();

        // Now a new schedule fits.
        service.schedule(envelope("env-2", new RewakeTriggerDelay(Duration.ofSeconds(10))));
        assertThat(service.listPending()).hasSize(1);
    }

    @Test
    void quotaIsReleasedOnDelayFire() throws InterruptedException {
        final DefaultRewakeQuotaManager quota = new DefaultRewakeQuotaManager(1);
        service.withQuotaManager(quota);
        final AgentRuntimeId ctxId = AgentRuntimeId.fromName("agent-x");

        listener.expect(1);
        service.schedule(envelope("env-fire", new RewakeTriggerDelay(Duration.ofMillis(20))));
        assertThat(listener.await(2, TimeUnit.SECONDS)).isTrue();

        // Once the timer has fired the slot must be released.
        assertThat(quota.getCurrentUsage(ctxId)).isZero();
    }

    @Test
    void quotaIsReleasedOnEventResolve() {
        final DefaultRewakeQuotaManager quota = new DefaultRewakeQuotaManager(1);
        service.withQuotaManager(quota);
        final AgentRuntimeId ctxId = AgentRuntimeId.fromName("agent-x");

        service.schedule(envelopeBuilder("env-evt", new RewakeTriggerEvent("t", "k")).build());
        assertThat(quota.getCurrentUsage(ctxId)).isEqualTo(1);

        assertThat(service.resolve("t", "k", Map.of())).isEqualTo(1);
        assertThat(quota.getCurrentUsage(ctxId)).isZero();
    }

    @Test
    void quotaIsReleasedOnClose() {
        final DefaultRewakeQuotaManager quota = new DefaultRewakeQuotaManager(2);
        service.withQuotaManager(quota);
        final AgentRuntimeId ctxId = AgentRuntimeId.fromName("agent-x");

        service.schedule(envelope("env-1", new RewakeTriggerDelay(Duration.ofSeconds(10))));
        service.schedule(envelopeBuilder("env-2", new RewakeTriggerEvent("t", "k")).build());
        assertThat(quota.getCurrentUsage(ctxId)).isEqualTo(2);

        service.close();
        assertThat(quota.getCurrentUsage(ctxId)).isZero();
    }

    @Test
    void idempotentRescheduleDoesNotDoubleCharge() {
        final DefaultRewakeQuotaManager quota = new DefaultRewakeQuotaManager(1);
        service.withQuotaManager(quota);
        final AgentRuntimeId ctxId = AgentRuntimeId.fromName("agent-x");

        service.schedule(envelope("env-same", new RewakeTriggerDelay(Duration.ofSeconds(10))));
        // Re-scheduling the same envelopeId must replace, not double-charge.
        service.schedule(envelope("env-same", new RewakeTriggerDelay(Duration.ofSeconds(20))));

        assertThat(quota.getCurrentUsage(ctxId)).isEqualTo(1);
        assertThat(service.listPending()).hasSize(1);
    }

    @Test
    void quotaSlotIsReleasedWhenCronTriggerRejected() {
        final DefaultRewakeQuotaManager quota = new DefaultRewakeQuotaManager(1);
        service.withQuotaManager(quota);
        final AgentRuntimeId ctxId = AgentRuntimeId.fromName("agent-x");

        assertThatThrownBy(() -> service
                .schedule(envelopeBuilder("env-cron", new RewakeTriggerCron("0 * * * *", ZoneId.of("UTC"))).build()))
                .isInstanceOf(UnsupportedOperationException.class);
        // Even though acquire ran before the trigger-shape check, the catch-and-release ensures the slot is freed.
        assertThat(quota.getCurrentUsage(ctxId)).isZero();
    }

    @Test
    void withQuotaManagerRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> service.withQuotaManager(null));
    }

    @Test
    void withQuotaManagerNoopAllowsUnboundedScheduling() {
        // Sanity: NOOP is the default, but explicit set should also let an arbitrary number land.
        service.withQuotaManager(RewakeQuotaManager.NOOP);
        for (int i = 0; i < 100; i++) {
            service.schedule(envelope("env-" + i, new RewakeTriggerDelay(Duration.ofSeconds(10))));
        }
        assertThat(service.listPending()).hasSize(100);
    }

    @Test
    void scheduleRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> service.schedule(null));
    }

    @Test
    void cancelRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> service.cancel(null));
    }

    @Test
    void constructorRejectsNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> new DefaultRewakeService(null));
        assertThatNullPointerException().isThrownBy(() -> new DefaultRewakeService(null, Clock.systemUTC(), scheduler));
        assertThatNullPointerException()
                .isThrownBy(() -> new DefaultRewakeService(RewakeFireListener.NOOP, null, scheduler));
        assertThatNullPointerException()
                .isThrownBy(() -> new DefaultRewakeService(RewakeFireListener.NOOP, Clock.systemUTC(), null));
    }

    @Test
    void nowReturnsClockInstant() {
        final Instant fixed = Instant.parse("2026-01-01T00:00:00Z");
        try (DefaultRewakeService fixedService = new DefaultRewakeService(RewakeFireListener.NOOP,
                Clock.fixed(fixed, ZoneId.of("UTC")), scheduler)) {
            assertThat(fixedService.now()).isEqualTo(fixed);
        }
    }

    private static RewakeEnvelope envelope(String id, RewakeTrigger trigger) {
        return envelopeBuilder(id, trigger).build();
    }

    private static RewakeEnvelope.Builder envelopeBuilder(String id, RewakeTrigger trigger) {
        return RewakeEnvelope.builder().envelopeId(id).agentRuntimeId(AgentRuntimeId.fromName("agent-x"))
                .trigger(trigger).originalEventType(HookEventType.PRE_TOOL).originatingHookId("hook-1")
                .firstScheduledAt(Instant.parse("2026-01-01T00:00:00Z")).reason("test");
    }

    private static final class RecordingListener implements RewakeFireListener {
        private final ConcurrentLinkedQueue<RewakeEnvelope> fired = new ConcurrentLinkedQueue<>();
        private volatile CountDownLatch latch = new CountDownLatch(0);

        void expect(int count) {
            this.latch = new CountDownLatch(count);
        }

        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        List<RewakeEnvelope> fired() {
            return List.copyOf(fired);
        }

        @Override
        public void onFire(RewakeEnvelope envelope) {
            fired.add(envelope);
            latch.countDown();
        }
    }
}
