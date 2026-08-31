package at.aimon.spring.boot.autoconfigure;

import java.util.Objects;

import org.springframework.context.SmartLifecycle;

import at.aimon.bootstrap.AimonStack;

/**
 * Starts the scheduling engine after the web server is accepting requests, and stops it before the web server
 * stops.
 *
 * <p>
 * The engine is the one part of the stack that <i>originates</i> work. Everything else answers a request that
 * already arrived, so it is the only component whose timing has to be stated relative to the front door in both
 * directions: a cron that fires before the application is serving produces work nothing is ready to handle, and
 * one that fires during shutdown adds work to a process that is trying to finish.
 *
 * <h2>The phase number</h2>
 *
 * <p>
 * {@code Integer.MAX_VALUE} — {@code SmartLifecycle.DEFAULT_PHASE} — is the highest phase there is, which means
 * started last and stopped first. Boot's two web phases sit below it ({@code WebServerStartStopLifecycle} at
 * {@code MAX_VALUE - 2048}, {@code WebServerGracefulShutdownLifecycle} at {@code MAX_VALUE - 1024}) and
 * {@link AimonRuntimeLifecycle} below those again, which together give the order the design asks for: runtimes
 * registered, socket open, scheduler running; and in reverse, scheduler stopped, in-flight requests drained,
 * socket closed, then the stack's ordered teardown drains what is left. Spring's own {@code SchedulerFactoryBean}
 * and Spring Kafka's {@code KafkaListenerEndpointRegistry} pick the same phase for the same reason.
 *
 * <h2>Registered whether or not there is an engine</h2>
 *
 * <p>
 * This bean exists unconditionally. Under {@code aimon.scheduling.backend=none} it is inert —
 * {@link AimonStack#startScheduling()} finds no engine and returns — and it is registered anyway because the
 * ordering it encodes is a property of the <i>design</i>: a deployment that later selects a backend should not
 * also acquire a new start-up order to discover.
 *
 * <h2>Holding the engine back</h2>
 *
 * <p>
 * {@code aimon.scheduling.auto-startup=false} builds the engine and does not start it, leaving the moment
 * scheduling begins to the application — a passive node awaiting election, a batch window, an operator switch.
 * That moment is {@link AimonStack#startScheduling()}, on the stack bean.
 *
 * <p>
 * A held-back engine is not a queue. Both schedulers refuse {@code scheduleRecurrently} while stopped, and
 * {@code ScheduledTaskManager.register} schedules any task that is enabled — so registering one before the start
 * call fails rather than being held until firing is allowed. The start call comes first, and whatever registers
 * cron tasks comes after it.
 *
 * <p>
 * The asymmetry that follows is worth stating: {@code DefaultLifecycleProcessor} only calls {@code stop()} on
 * beans that report {@link #isRunning()}, so a lifecycle that never auto-started is also never stopped here even
 * if the application started the engine through the stack. What stops it then is the stack's own {@code close()},
 * which runs later, in {@code destroyBeans()} — after the web server rather than before it. That is the trade the
 * flag makes, and it is the right way round: a deployment taking manual control of when scheduling starts is
 * better served by an engine that keeps running until teardown than by one this bean stops at a moment it was
 * told not to manage.
 *
 * <h2>No timeout, and none to be had</h2>
 *
 * <p>
 * {@code stop()} runs synchronously, and that is not something a timeout can change:
 * {@code SmartLifecycle.stop(Runnable)}'s default implementation calls {@code stop()} and then the callback on
 * the caller's thread, so the latch {@code DefaultLifecycleProcessor} waits on has already counted down by the
 * time it waits. {@code spring.lifecycle.timeout-per-shutdown-phase} therefore bounds nothing here — and nothing
 * in the stack's own teardown either, which runs later still, in {@code destroyBeans()}, where no Spring timeout
 * reaches. The knob that does shorten AIMON's shutdown is {@code aimon.session.shutdown-drain-timeout}.
 */
final class AimonSchedulingLifecycle implements SmartLifecycle {

    /** The highest phase: started last, stopped first. */
    static final int PHASE = Integer.MAX_VALUE;

    private final AimonStack stack;
    private final boolean autoStartup;
    private volatile boolean running;

    AimonSchedulingLifecycle(AimonStack stack, boolean autoStartup) {
        this.stack = Objects.requireNonNull(stack, "stack must not be null");
        this.autoStartup = autoStartup;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public boolean isAutoStartup() {
        return autoStartup;
    }

    @Override
    public void start() {
        stack.startScheduling();
        running = true;
    }

    @Override
    public void stop() {
        stack.stopScheduling();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
