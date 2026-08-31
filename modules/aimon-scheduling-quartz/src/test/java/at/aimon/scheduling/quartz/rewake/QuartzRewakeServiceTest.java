package at.aimon.scheduling.quartz.rewake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.CronTrigger;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.rewake.RewakeEnvelope;
import at.aimon.core.hook.rewake.RewakeFireListener;
import at.aimon.core.hook.rewake.RewakeTriggerCron;
import at.aimon.core.hook.rewake.RewakeTriggerDelay;
import at.aimon.core.hook.rewake.RewakeTriggerEvent;
import at.aimon.core.scheduling.exception.InvalidCronExpressionException;

/**
 * Phase 4A WI-4A.5 — verifies the Quartz-backed {@link QuartzRewakeService} schedules delay/cron triggers via Quartz,
 * keeps event triggers in-memory, cancels jobs cleanly, and survives invariant interactions across the three trigger
 * shapes.
 */
class QuartzRewakeServiceTest {

    private static final AgentRuntimeId CTX_ID = AgentRuntimeId.fromName("agent-x");

    private Scheduler scheduler;
    private QuartzRewakeService service;
    private CopyOnWriteArrayList<RewakeEnvelope> fired;

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "RewakeTestScheduler-" + System.nanoTime());
        props.setProperty("org.quartz.scheduler.instanceId", "AUTO");
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "2");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        scheduler = new StdSchedulerFactory(props).getScheduler();
        scheduler.start();
        fired = new CopyOnWriteArrayList<>();
        final RewakeFireListener listener = fired::add;
        service = new QuartzRewakeService(scheduler, listener);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (scheduler != null) {
            scheduler.shutdown(true);
        }
    }

    @Test
    void delayTriggerFiresAfterDelay() {
        service.schedule(envelopeWith("env-delay", new RewakeTriggerDelay(Duration.ofMillis(150))));
        awaitUntil(() -> !fired.isEmpty(), Duration.ofSeconds(5));
        assertThat(fired).hasSize(1);
        assertThat(fired.get(0).getEnvelopeId()).isEqualTo("env-delay");
        assertThat(service.listPending()).isEmpty();
    }

    @Test
    void delayTriggerCanBeCancelledBeforeFiring() {
        service.schedule(envelopeWith("env-cancel", new RewakeTriggerDelay(Duration.ofSeconds(30))));
        assertThat(service.listPending()).extracting(RewakeEnvelope::getEnvelopeId).containsExactly("env-cancel");
        assertThat(service.cancel("env-cancel")).isTrue();
        assertThat(service.listPending()).isEmpty();
        assertThat(fired).isEmpty();
    }

    @Test
    void cancelUnknownEnvelopeReturnsFalse() {
        assertThat(service.cancel("does-not-exist")).isFalse();
    }

    @Test
    void rescheduleSameIdReplacesExistingRegistration() {
        service.schedule(envelopeWith("env-replace", new RewakeTriggerDelay(Duration.ofSeconds(30))));
        service.schedule(envelopeWith("env-replace", new RewakeTriggerDelay(Duration.ofMillis(150))));
        awaitUntil(() -> !fired.isEmpty(), Duration.ofSeconds(5));
        assertThat(fired).hasSize(1);
    }

    /**
     * The trigger carries the framework's five-field expression; Quartz must receive the six-field translation. This
     * is the assertion the production line {@code CronScheduleBuilder.cronSchedule(quartzCron)} exists for — before
     * the dialects were unified, the raw expression went straight through.
     */
    @Test
    void cronTriggerIsTranslatedIntoQuartzDialect() throws Exception {
        service.schedule(envelopeWith("env-cron-xlate", new RewakeTriggerCron("*/5 * * * *", ZoneId.of("UTC"))));

        final CronTrigger trigger = (CronTrigger) scheduler
                .getTrigger(TriggerKey.triggerKey("rewake:env-cron-xlate", QuartzRewakeService.TRIGGER_GROUP));
        assertThat(trigger.getCronExpression()).isEqualTo("0 */5 * * * ?");
    }

    /** Day-of-week is the field that silently changes meaning: Friday is 5 in the five-field dialect, 6 in Quartz. */
    @Test
    void cronTriggerRenumbersDayOfWeek() throws Exception {
        service.schedule(envelopeWith("env-cron-dow", new RewakeTriggerCron("0 0 * * 5", ZoneId.of("UTC"))).toBuilder()
                .timeout(Duration.ofDays(8)).build());

        final CronTrigger trigger = (CronTrigger) scheduler
                .getTrigger(TriggerKey.triggerKey("rewake:env-cron-dow", QuartzRewakeService.TRIGGER_GROUP));
        assertThat(trigger.getCronExpression()).isEqualTo("0 0 0 ? * 6");
    }

    /**
     * Five-field cron unions the two day fields; Quartz has no way to say that, so the translation is rejected. The
     * rejection happens before any service state is touched, so the envelope already scheduled under that id survives.
     */
    @Test
    void cronTriggerRestrictingBothDayFieldsIsRejectedWithoutDisturbingState() {
        service.schedule(envelopeWith("env-cron-keep", new RewakeTriggerDelay(Duration.ofSeconds(30))));

        assertThatThrownBy(() -> service
                .schedule(envelopeWith("env-cron-keep", new RewakeTriggerCron("0 0 1 * 1", ZoneId.of("UTC")))))
                .isInstanceOf(InvalidCronExpressionException.class);

        assertThat(service.listPending()).extracting(RewakeEnvelope::getEnvelopeId).containsExactly("env-cron-keep");
    }

    /**
     * The per-fire attempt cap (§6.1), asserted by firing the job directly. The five-field dialect's finest resolution
     * is one minute, so the once-per-second cron this used to ride on no longer exists — but {@code triggerJob} runs
     * the same {@code RewakeJob.execute} path (deliver → advance attempt → evict at the cap), which is what the cap
     * lives in. Quartz's own scheduling of the next tick is not what is under test here.
     */
    @Test
    void cronTriggerStopsAtMaxAttemptsCap() throws Exception {
        service.schedule(envelopeWith("env-cron-cap", new RewakeTriggerCron("* * * * *", ZoneId.of("UTC"))).toBuilder()
                .maxAttempts(2).build());
        final JobKey job = JobKey.jobKey("rewake:env-cron-cap", QuartzRewakeService.JOB_GROUP);

        scheduler.triggerJob(job);
        awaitUntil(() -> fired.size() >= 1, Duration.ofSeconds(5));
        assertThat(service.listPending()).extracting(RewakeEnvelope::getEnvelopeId).contains("env-cron-cap");

        scheduler.triggerJob(job);
        awaitUntil(() -> fired.size() >= 2, Duration.ofSeconds(5));
        awaitUntil(() -> service.listPending().isEmpty(), Duration.ofSeconds(5));

        assertThat(fired).hasSize(2);
        assertThat(service.listPending()).extracting(RewakeEnvelope::getEnvelopeId).doesNotContain("env-cron-cap");
    }

    /**
     * The timeout bound (§6.1) is {@code endAt(firstScheduledAt + timeout)} on the Quartz trigger. Asserting the
     * trigger's end time states that directly; the previous shape — sleep past a 2-second window, then sleep again to
     * prove no further fires — spent seven seconds inferring it, and could only do so because the cron ticked once a
     * second.
     */
    @Test
    void cronTriggerStopsAtTimeoutWindow() throws Exception {
        // Truncated to millis because Quartz stores the end time as a java.util.Date; an Instant carrying
        // microseconds would come back rounded and the comparison would fail on precision, not on behaviour.
        final Instant firstScheduledAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        service.schedule(RewakeEnvelope.builder().envelopeId("env-cron-timeout").agentRuntimeId(CTX_ID)
                .trigger(new RewakeTriggerCron("* * * * *", ZoneId.of("UTC"))).originalEventType(HookEventType.PRE_TOOL)
                .originatingHookId("approval-hook").firstScheduledAt(firstScheduledAt).reason("await-quota")
                .timeout(Duration.ofMinutes(30)).maxAttempts(100).build());

        final CronTrigger trigger = (CronTrigger) scheduler
                .getTrigger(TriggerKey.triggerKey("rewake:env-cron-timeout", QuartzRewakeService.TRIGGER_GROUP));
        assertThat(trigger.getEndTime().toInstant()).isEqualTo(firstScheduledAt.plus(Duration.ofMinutes(30)));

        service.cancel("env-cron-timeout");
    }

    @Test
    void eventTriggerIsHeldUntilResolveFires() {
        service.schedule(envelopeWith("env-event", new RewakeTriggerEvent("webhook", "ticket-1")));
        assertThat(service.listPending()).extracting(RewakeEnvelope::getEnvelopeId).containsExactly("env-event");
        assertThat(fired).isEmpty();

        final int matched = service.resolve("webhook", "ticket-1", Map.of("status", "approved"));
        assertThat(matched).isEqualTo(1);
        assertThat(fired).hasSize(1);
        assertThat(fired.get(0).getPayload()).containsEntry("status", "approved");
        assertThat(service.listPending()).isEmpty();
    }

    @Test
    void resolveDoesNotFireMismatchedEventTriggers() {
        service.schedule(envelopeWith("env-event", new RewakeTriggerEvent("webhook", "ticket-1")));
        final int matched = service.resolve("webhook", "ticket-other", Map.of());
        assertThat(matched).isZero();
        assertThat(fired).isEmpty();
        assertThat(service.listPending()).hasSize(1);
    }

    @Test
    void cancelByOriginatingHookIdRemovesAllMatchingEnvelopes() {
        service.schedule(envelopeWithHook("env-1", "approval-hook", new RewakeTriggerDelay(Duration.ofSeconds(30))));
        service.schedule(envelopeWithHook("env-2", "approval-hook", new RewakeTriggerEvent("webhook", "k")));
        service.schedule(envelopeWithHook("env-3", "other-hook", new RewakeTriggerDelay(Duration.ofSeconds(30))));

        final int cancelled = service.cancelByOriginatingHookId("approval-hook");

        assertThat(cancelled).isEqualTo(2);
        assertThat(service.listPending()).extracting(RewakeEnvelope::getEnvelopeId).containsExactly("env-3");
    }

    @Test
    void listPendingIsImmutable() {
        service.schedule(envelopeWith("env-1", new RewakeTriggerDelay(Duration.ofSeconds(30))));
        final List<RewakeEnvelope> snapshot = service.listPending();
        assertThat(snapshot).hasSize(1);
        final RewakeEnvelope another = envelopeWith("env-2", new RewakeTriggerDelay(Duration.ofSeconds(30)));
        assertThatThrownBy(() -> snapshot.add(another)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void cronTriggerHonoursNonUtcTimezone() {
        // The test does not wait for a fire — it asserts that scheduling succeeds with a non-UTC zone, so a
        // regression in the ZoneId → java.util.TimeZone bridge (production line in buildQuartzTrigger) would
        // surface here. Cron expression chosen to avoid firing within the test window. Timeout is bumped to
        // 25h so the per-spec endAt window covers at least one occurrence of the daily 03:00 fire (§6.1
        // bound).
        final ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        service.schedule(envelopeWith("env-cron-tokyo", new RewakeTriggerCron("0 3 * * *", tokyo)).toBuilder()
                .timeout(Duration.ofHours(25)).build());
        assertThat(service.listPending()).extracting(RewakeEnvelope::getEnvelopeId).containsExactly("env-cron-tokyo");
        assertThat(service.cancel("env-cron-tokyo")).isTrue();
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while awaiting condition", e);
            }
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("Condition not met within " + timeout);
        }
    }

    private static RewakeEnvelope envelopeWith(String id, at.aimon.core.hook.rewake.RewakeTrigger trigger) {
        return envelopeWithHook(id, "approval-hook", trigger);
    }

    private static RewakeEnvelope envelopeWithHook(String id, String hookId,
            at.aimon.core.hook.rewake.RewakeTrigger trigger) {
        return RewakeEnvelope.builder().envelopeId(id).agentRuntimeId(CTX_ID).trigger(trigger)
                .originalEventType(HookEventType.PRE_TOOL).originatingHookId(hookId).firstScheduledAt(Instant.now())
                .reason("await-quota").build();
    }
}
