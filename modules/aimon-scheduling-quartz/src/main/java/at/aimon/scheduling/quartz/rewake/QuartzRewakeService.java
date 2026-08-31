package at.aimon.scheduling.quartz.rewake;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

import org.quartz.CronScheduleBuilder;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerContext;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.hook.rewake.RewakeEnvelope;
import at.aimon.core.hook.rewake.RewakeFireListener;
import at.aimon.core.hook.rewake.RewakeService;
import at.aimon.core.hook.rewake.RewakeTrigger;
import at.aimon.core.hook.rewake.RewakeTriggerCron;
import at.aimon.core.hook.rewake.RewakeTriggerDelay;
import at.aimon.core.hook.rewake.RewakeTriggerEvent;
import at.aimon.scheduling.quartz.cron.QuartzCronTranslator;

/**
 * Quartz-backed implementation of {@link RewakeService}.
 *
 * <p>
 * Capabilities:
 * <ul>
 * <li><b>Delay triggers</b> — registered as a one-shot Quartz job
 * ({@code SimpleScheduleBuilder.simpleSchedule().withRepeatCount(0)}) starting at {@code now + delay}.
 * Misfire policy is {@code SimpleTrigger.MISFIRE_INSTRUCTION_FIRE_NOW} (via
 * {@code withMisfireHandlingInstructionFireNow()}): a missed one-shot fires as soon as the scheduler is
 * back up.
 * <li><b>Cron triggers</b> — the trigger carries the framework's five-field expression, which
 * {@link QuartzCronTranslator} converts to Quartz's six-field dialect before it reaches
 * {@code CronScheduleBuilder}; the {@link RewakeTriggerCron#getZone() time zone} passes through unchanged.
 * Translation happens <i>before</i> any service state is touched, so an expression Quartz cannot express
 * (both day fields restricted — five-field cron unions them, Quartz cannot) leaves the envelope map as it
 * was. Bounded on both axes:
 * the Quartz trigger gets {@code endAt(firstScheduledAt + timeout)} so the scheduler stops firing once
 * the per-spec {@link RewakeEnvelope#getTimeout() timeout} window elapses, and {@link RewakeJob#execute}
 * bumps {@link RewakeEnvelope#getAttemptNumber() attemptNumber} after every fire and cancels the Quartz
 * job once the next attempt would exceed {@link RewakeEnvelope#getMaxAttempts() maxAttempts} (per-fire
 * cap; cron schedules have no native Quartz repeat-count). Misfire policy is
 * {@code CronTrigger.MISFIRE_INSTRUCTION_FIRE_ONCE_NOW} (via
 * {@code withMisfireHandlingInstructionFireAndProceed()}): a missed scheduled fire is replayed once
 * immediately and the cron schedule then resumes. In both cases the envelope's
 * {@link RewakeEnvelope#getFirstScheduledAt() firstScheduledAt} lets downstream code decide whether the
 * replayed fire is still relevant.
 * <li><b>Event triggers</b> — held in an in-memory registry exactly like the in-memory default; fired
 * synchronously when {@link #resolve(String, String, Map)} is invoked. Quartz never schedules the job for
 * these envelopes.
 * </ul>
 *
 * <p>
 * <b>Persistence (MVP scope).</b> The canonical envelope state is held in a {@link ConcurrentHashMap}
 * inside this service — Quartz {@link JobDataMap} only carries the {@code envelopeId}. That keeps
 * polymorphic JSON serialization of {@link at.aimon.core.hook.HookEventType HookEventType},
 * {@link at.aimon.core.agent.tool.ToolInput ToolInput}, and the {@link RewakeTrigger} sealed family
 * out of this iteration. The trade-off: pending envelopes do <i>not</i> survive JVM restart even when a
 * persistent Quartz {@code JobStore} is configured. Full envelope persistence is tracked as a follow-up
 * (see {@code docs/design/hook/async-rewake.md} §8).
 *
 * <p>
 * <b>Lifecycle.</b> Application-scoped per AIMON's three-tier scope model. The caller supplies a started
 * Quartz {@link Scheduler} and a {@link RewakeFireListener} at construction time; both are stashed in
 * the scheduler's {@link SchedulerContext} so the inner {@link RewakeJob} can resolve them on each fire.
 * The service does not own the scheduler — shutting it down is the caller's responsibility.
 *
 * <p>
 * <b>Thread-safety.</b> Every public method is safe for concurrent invocation. The envelope map uses a
 * {@link ConcurrentHashMap}; Quartz handles its own internal locking.
 */
public final class QuartzRewakeService implements RewakeService {

    private static final Logger log = LoggerFactory.getLogger(QuartzRewakeService.class);

    static final String JOB_GROUP = "aimon-rewake-jobs";
    static final String TRIGGER_GROUP = "aimon-rewake-triggers";
    static final String ENVELOPE_ID_KEY = "envelopeId";
    static final String LISTENER_CONTEXT_KEY = "aimon.rewake.listener";
    static final String SERVICE_CONTEXT_KEY = "aimon.rewake.service";

    private final Scheduler scheduler;
    private final RewakeFireListener listener;
    private final Map<String, RewakeEnvelope> envelopes = new ConcurrentHashMap<>();

    /**
     * Creates the service against an externally-started Quartz scheduler.
     *
     * <p>
     * The caller must {@link Scheduler#start() start} the scheduler before scheduling envelopes, and is
     * responsible for {@link Scheduler#shutdown() shutdown}. The constructor publishes {@code this} and
     * the listener into the scheduler's {@link SchedulerContext}; that publication is required so the
     * {@link RewakeJob} can resolve them when Quartz fires.
     *
     * @param scheduler
     *            Quartz scheduler (must not be null)
     * @param listener
     *            fire listener (must not be null; pass {@link RewakeFireListener#NOOP} for stand-alone)
     * @throws NullPointerException
     *             if any argument is null
     * @throws IllegalStateException
     *             if the scheduler context publication fails
     */
    public QuartzRewakeService(Scheduler scheduler, RewakeFireListener listener) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        this.listener = Objects.requireNonNull(listener, "listener cannot be null");
        try {
            final SchedulerContext context = scheduler.getContext();
            context.put(LISTENER_CONTEXT_KEY, listener);
            context.put(SERVICE_CONTEXT_KEY, this);
        } catch (SchedulerException e) {
            throw new IllegalStateException("Failed to publish rewake bindings to Quartz scheduler context", e);
        }
    }

    @Override
    public void schedule(RewakeEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope cannot be null");
        final RewakeTrigger trigger = envelope.getTrigger();
        final String envelopeId = envelope.getEnvelopeId();

        // Translate before touching any state: a five-field expression that restricts both day fields is
        // valid to the framework but inexpressible in Quartz, and that rejection must not cancel the
        // envelope that is already scheduled under this id.
        final String quartzCron = trigger instanceof RewakeTriggerCron cron
                ? QuartzCronTranslator.toQuartz(cron.getExpression())
                : null;

        cancel(envelopeId);
        // Put the envelope into the map BEFORE handing the job to Quartz: a sub-millisecond delay can fire
        // immediately after scheduleJob() returns, and RewakeJob.execute() must be able to resolve the
        // envelope. On Quartz failure we use an atomic value-match remove so a concurrent re-schedule
        // racing with the rollback is not clobbered.
        envelopes.put(envelopeId, envelope);

        if (trigger instanceof RewakeTriggerEvent) {
            log.debug("Registered event-trigger envelope {} (waiting for resolve)", envelopeId);
            return;
        }

        try {
            final JobDetail job = JobBuilder.newJob(RewakeJob.class).withIdentity(jobKey(envelopeId))
                    .usingJobData(ENVELOPE_ID_KEY, envelopeId).storeDurably(false).build();
            final Trigger quartzTrigger = buildQuartzTrigger(envelope, quartzCron);
            scheduler.scheduleJob(job, quartzTrigger);
            log.debug("Scheduled rewake envelope {} with trigger {}", envelopeId, trigger);
        } catch (SchedulerException e) {
            envelopes.remove(envelopeId, envelope);
            throw new IllegalStateException("Failed to schedule rewake envelope " + envelopeId + ": " + e.getMessage(),
                    e);
        }
    }

    private Trigger buildQuartzTrigger(RewakeEnvelope envelope, String quartzCron) {
        final String envelopeId = envelope.getEnvelopeId();
        final RewakeTrigger trigger = envelope.getTrigger();
        final TriggerBuilder<Trigger> base = TriggerBuilder.newTrigger().withIdentity("rewake:" + envelopeId,
                TRIGGER_GROUP);
        if (trigger instanceof RewakeTriggerDelay delay) {
            final long delayMillis = Math.max(0L, delay.getDelay().toMillis());
            return base.startAt(Date.from(Instant.now().plusMillis(delayMillis))).withSchedule(
                    SimpleScheduleBuilder.simpleSchedule().withRepeatCount(0).withMisfireHandlingInstructionFireNow())
                    .build();
        }
        if (trigger instanceof RewakeTriggerCron cron) {
            // Hard upper bound: stop firing once firstScheduledAt + timeout has elapsed. Quartz enforces this
            // natively via endAt; the per-fire attempt cap is enforced inside RewakeJob.execute.
            final Instant endAt = envelope.getFirstScheduledAt().plus(envelope.getTimeout());
            return base.endAt(Date.from(endAt)).withSchedule(CronScheduleBuilder.cronSchedule(quartzCron)
                    .inTimeZone(TimeZone.getTimeZone(cron.getZone())).withMisfireHandlingInstructionFireAndProceed())
                    .build();
        }
        throw new UnsupportedOperationException("Unsupported trigger shape: " + trigger.getClass().getName());
    }

    @Override
    public boolean cancel(String envelopeId) {
        Objects.requireNonNull(envelopeId, "envelopeId cannot be null");
        final RewakeEnvelope removed = envelopes.remove(envelopeId);
        if (removed == null) {
            return false;
        }
        if (removed.getTrigger() instanceof RewakeTriggerEvent) {
            return true;
        }
        try {
            scheduler.deleteJob(jobKey(envelopeId));
        } catch (SchedulerException e) {
            log.warn("Failed to delete Quartz job for envelope {}: {}", envelopeId, e.getMessage(), e);
        }
        return true;
    }

    @Override
    public int cancelByOriginatingHookId(String originatingHookId) {
        Objects.requireNonNull(originatingHookId, "originatingHookId cannot be null");
        if (originatingHookId.isBlank()) {
            throw new IllegalArgumentException("originatingHookId cannot be blank");
        }
        int cancelled = 0;
        for (RewakeEnvelope env : List.copyOf(envelopes.values())) {
            if (originatingHookId.equals(env.getOriginatingHookId()) && cancel(env.getEnvelopeId())) {
                cancelled++;
            }
        }
        return cancelled;
    }

    @Override
    public int resolve(String eventType, String eventKey, Map<String, String> payload) {
        Objects.requireNonNull(eventType, "eventType cannot be null");
        Objects.requireNonNull(eventKey, "eventKey cannot be null");
        Objects.requireNonNull(payload, "payload cannot be null");
        if (eventType.isBlank() || eventKey.isBlank()) {
            throw new IllegalArgumentException("eventType / eventKey cannot be blank");
        }
        payload.forEach((k, v) -> {
            Objects.requireNonNull(k, "payload key cannot be null");
            Objects.requireNonNull(v, "payload value cannot be null");
        });

        final List<RewakeEnvelope> matched = new ArrayList<>();
        for (RewakeEnvelope env : List.copyOf(envelopes.values())) {
            if (!(env.getTrigger() instanceof RewakeTriggerEvent ev)) {
                continue;
            }
            if (ev.getEventType().equals(eventType) && ev.getEventKey().equals(eventKey)
                    && cancel(env.getEnvelopeId())) {
                matched.add(mergePayload(env, payload));
            }
        }
        for (RewakeEnvelope env : matched) {
            deliver(env);
        }
        return matched.size();
    }

    private static RewakeEnvelope mergePayload(RewakeEnvelope envelope, Map<String, String> incoming) {
        if (incoming.isEmpty()) {
            return envelope;
        }
        final Map<String, String> merged = new LinkedHashMap<>(envelope.getPayload());
        merged.putAll(incoming);
        return envelope.toBuilder().payload(merged).build();
    }

    @Override
    public List<RewakeEnvelope> listPending() {
        return List.copyOf(envelopes.values());
    }

    void deliver(RewakeEnvelope envelope) {
        try {
            listener.onFire(envelope);
        } catch (Exception e) {
            log.warn("RewakeFireListener threw for envelope {} (originatingHookId={}): {}", envelope.getEnvelopeId(),
                    envelope.getOriginatingHookId(), e.getMessage(), e);
        }
    }

    /**
     * Looks up an envelope by id. Used by {@link RewakeJob} to fetch the canonical envelope state at fire
     * time. Returns {@code null} when the envelope has been cancelled in the gap between Quartz firing
     * and the job picking it up.
     *
     * @param envelopeId
     *            envelope id (must not be null)
     * @return envelope or {@code null}
     */
    RewakeEnvelope getEnvelope(String envelopeId) {
        Objects.requireNonNull(envelopeId, "envelopeId cannot be null");
        return envelopes.get(envelopeId);
    }

    /**
     * Removes the envelope from the map iff its trigger is a one-shot {@link RewakeTriggerDelay}. No-op for
     * cron and event triggers, which must stay registered across fires. The trigger-shape guard lives
     * inside this method so that {@link RewakeJob} can call it unconditionally without re-checking the
     * trigger type — and so that a future caller cannot accidentally evict a cron envelope (which would
     * leave its Quartz job firing forever against a missing envelope).
     *
     * @param envelopeId
     *            envelope id (must not be null)
     */
    void removeIfOneShot(String envelopeId) {
        Objects.requireNonNull(envelopeId, "envelopeId cannot be null");
        final RewakeEnvelope env = envelopes.get(envelopeId);
        if (env != null && env.getTrigger() instanceof RewakeTriggerDelay) {
            envelopes.remove(envelopeId, env);
        }
    }

    /**
     * Bumps the cron envelope's {@code attemptNumber} after a successful fire and, when the next attempt
     * would exceed {@link RewakeEnvelope#getMaxAttempts() maxAttempts}, cancels the Quartz job so the cron
     * stops firing. No-op for non-cron envelopes — delay triggers are evicted by
     * {@link #removeIfOneShot(String)} and event triggers never fire from Quartz.
     *
     * <p>
     * The attempt cap is the per-fire counterpart to the {@code endAt(firstScheduledAt + timeout)} bound
     * applied at scheduling: timeout is enforced by Quartz autonomously, the attempt cap is enforced here
     * because Quartz cron schedules have no native repeat-count.
     *
     * @param envelopeId
     *            envelope id (must not be null)
     */
    void advanceCronAttempt(String envelopeId) {
        Objects.requireNonNull(envelopeId, "envelopeId cannot be null");
        final RewakeEnvelope current = envelopes.get(envelopeId);
        if (current == null || !(current.getTrigger() instanceof RewakeTriggerCron)) {
            return;
        }
        final int nextAttempt = current.getAttemptNumber() + 1;
        if (nextAttempt > current.getMaxAttempts()) {
            log.debug("Cron envelope {} reached attempt cap ({}/{}) — cancelling", envelopeId,
                    current.getAttemptNumber(), current.getMaxAttempts());
            envelopes.remove(envelopeId, current);
            try {
                scheduler.deleteJob(jobKey(envelopeId));
            } catch (SchedulerException e) {
                log.warn("Failed to delete Quartz job for capped cron envelope {}: {}", envelopeId, e.getMessage(), e);
            }
            return;
        }
        envelopes.replace(envelopeId, current, current.toBuilder().attemptNumber(nextAttempt).build());
    }

    private static JobKey jobKey(String envelopeId) {
        return JobKey.jobKey("rewake:" + envelopeId, JOB_GROUP);
    }

    /**
     * Quartz {@link Job} that resolves the {@link QuartzRewakeService} and {@link RewakeFireListener} from
     * the scheduler context and delivers the envelope identified by the job-data key
     * {@link #ENVELOPE_ID_KEY}. The job is registered as a non-durable static class so Quartz can
     * instantiate it via reflection.
     */
    public static final class RewakeJob implements Job {

        private static final Logger log = LoggerFactory.getLogger(RewakeJob.class);

        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            final JobDataMap data = context.getJobDetail().getJobDataMap();
            final String envelopeId = data.getString(ENVELOPE_ID_KEY);
            if (envelopeId == null || envelopeId.isBlank()) {
                log.warn("Rewake job fired without envelopeId — dropping");
                return;
            }
            try {
                final SchedulerContext schedulerContext = context.getScheduler().getContext();
                final QuartzRewakeService service = (QuartzRewakeService) schedulerContext.get(SERVICE_CONTEXT_KEY);
                if (service == null) {
                    log.warn("Rewake job fired but no QuartzRewakeService bound (envelopeId={})", envelopeId);
                    return;
                }
                final RewakeEnvelope envelope = service.getEnvelope(envelopeId);
                if (envelope == null) {
                    log.debug("Rewake job fired for cancelled/unknown envelope {} — dropping", envelopeId);
                    return;
                }
                service.deliver(envelope);
                service.removeIfOneShot(envelopeId);
                service.advanceCronAttempt(envelopeId);
            } catch (SchedulerException e) {
                log.error("Failed to access scheduler context for envelope {}: {}", envelopeId, e.getMessage(), e);
                throw new JobExecutionException("Failed to fire rewake envelope: " + envelopeId, e);
            } catch (RuntimeException e) {
                log.error("Unexpected error firing rewake envelope {}: {}", envelopeId, e.getMessage(), e);
            }
        }
    }
}
