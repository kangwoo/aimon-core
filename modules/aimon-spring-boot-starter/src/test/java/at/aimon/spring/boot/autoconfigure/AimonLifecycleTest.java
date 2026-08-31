package at.aimon.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import at.aimon.bootstrap.AimonStack;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.base.Principal;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.scheduling.RoutineStep;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.exception.TaskSchedulerException;

/**
 * Tests for the two {@link SmartLifecycle} beans that own start-up and shutdown order.
 *
 * <p>
 * The thing under test is an <i>ordering</i>, and an ordering is only observable from something that sits between
 * the two ends of it. {@link SocketSpy} is that something: a lifecycle bean at Boot's own web-server phase that
 * records, at the two moments Boot would open and close the listening socket, what the stack's state was. What it
 * saw is asserted afterwards, because the interesting half happens during {@code close()} — after
 * {@code ApplicationContextRunner}'s consumer has already returned.
 *
 * <p>
 * The leak checks are the other half. They are what the design's completion criterion for this iteration actually
 * says ("zero non-daemon threads left after the context closes"), and unlike the ordering they cannot be asserted
 * from inside a running context at all.
 */
class AimonLifecycleTest {

    private static final String AGENT = "test-agent";

    /**
     * Boot's {@code WebServerStartStopLifecycle} phase, read out of {@code spring-boot} 3.5's bytecode.
     *
     * <p>
     * It is package-private with no public constant, so it cannot be referenced — but it is defined relative to
     * the one next to it that <i>is</i> public, and {@link #phasesStraddleTheWebServer()} re-derives it from that
     * constant at runtime rather than trusting this literal.
     */
    private static final int WEB_SERVER_PHASE = Integer.MAX_VALUE - 2048;

    /** How long a thread gets to actually die after the shutdown that asked it to. */
    private static final long THREAD_DEATH_TIMEOUT_MS = 15_000L;

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(AimonLlmAutoConfiguration.class, AimonFileSystemAutoConfiguration.class,
                    AimonSessionAutoConfiguration.class, AimonSchedulingAutoConfiguration.class,
                    AimonKnowledgeAutoConfiguration.class, AimonMemoryAutoConfiguration.class,
                    AimonObservabilityAutoConfiguration.class, AimonAutoConfiguration.class));

    private ApplicationContextRunner minimal(Path workspace) {
        return runner.withPropertyValues("aimon.workspace.root=" + workspace, "aimon.llm.api-key=test-key",
                "aimon.agent-defaults.default-agent=" + AGENT);
    }

    @Test
    @DisplayName("the two phases straddle Boot's web server, and neither ties with it")
    void phasesStraddleTheWebServer() {
        // Read reflectively: javac inlines a `static final int`, so referencing the constant directly would
        // freeze whatever value was on the compile classpath and keep passing after Boot moved it.
        final int gracefulShutdown = readGracefulShutdownPhase();
        final int webServer = gracefulShutdown - 1024;

        assertThat(webServer).as("the literal this test documents").isEqualTo(WEB_SERVER_PHASE);
        assertThat(AimonRuntimeLifecycle.PHASE).as("runtimes start before the socket opens").isLessThan(webServer);
        assertThat(AimonSchedulingLifecycle.PHASE).as("scheduling starts after the socket opens")
                .isGreaterThan(gracefulShutdown);

        // The tie is the failure mode worth naming: beans sharing a phase land in one LifecycleGroup, ordered
        // within it by bean-factory iteration order, which would make the ordering below a coincidence.
        assertThat(AimonRuntimeLifecycle.PHASE).isNotEqualTo(webServer).isNotEqualTo(gracefulShutdown);
        assertThat(AimonSchedulingLifecycle.PHASE).isNotEqualTo(webServer).isNotEqualTo(gracefulShutdown);
    }

    @Test
    @DisplayName("runtimes are registered before the socket opens, and scheduling starts after")
    void startOrderIsRuntimesThenSocketThenScheduling(@TempDir Path workspace) {
        final AtomicReference<SocketSpy> spyRef = new AtomicReference<>();

        minimal(workspace).withUserConfiguration(WebServerStandIn.class).run(ctx -> {
            spyRef.set(ctx.getBean(SocketSpy.class));

            // Fully started: both AIMON lifecycles have run.
            assertThat(ctx.getBean(AimonStack.class).isStarted()).isTrue();
            assertThat(ctx.getBean(AimonRuntimeLifecycle.class).isRunning()).isTrue();
            assertThat(ctx.getBean(AimonSchedulingLifecycle.class).isRunning()).isTrue();
            assertThat(ctx.getBean(AimonStack.class).health().isServing()).isTrue();
        });

        final SocketSpy spy = spyRef.get();
        assertThat(spy.startCalled).as("the stand-in ran at all").isTrue();
        assertThat(spy.schedulingResolvedAtOpen).as("a missing bean would read as 'not running' too").isTrue();
        assertThat(spy.runtimesStartedAtOpen).as("runtimes registered before the socket opened").isTrue();
        assertThat(spy.schedulingRunningAtOpen).as("scheduling had not started when the socket opened").isFalse();
    }

    @Test
    @DisplayName("scheduling stops before the socket closes, and teardown runs after")
    void stopOrderIsSchedulingThenSocketThenTeardown(@TempDir Path workspace) {
        final AtomicReference<SocketSpy> spyRef = new AtomicReference<>();
        final AtomicReference<AimonStack> stackRef = new AtomicReference<>();

        minimal(workspace).withUserConfiguration(WebServerStandIn.class).run(ctx -> {
            spyRef.set(ctx.getBean(SocketSpy.class));
            stackRef.set(ctx.getBean(AimonStack.class));
        });

        final SocketSpy spy = spyRef.get();
        assertThat(spy.stopCalled).as("the stand-in was stopped at all").isTrue();
        assertThat(spy.schedulingResolvedAtClose).as("a missing bean would read as 'not running' too").isTrue();
        assertThat(spy.schedulingRunningAtClose).as("scheduling stopped before the socket closed").isFalse();
        assertThat(spy.stackOpenAtClose).as("the stack was still open when the socket closed — teardown is later")
                .isTrue();
        assertThat(stackRef.get().isClosed()).as("teardown ran once the context destroyed its beans").isTrue();
    }

    @Test
    @DisplayName("closing the context leaves no non-daemon thread behind")
    void contextCloseLeavesNoNonDaemonThread(@TempDir Path workspace) throws InterruptedException {
        final Set<Thread> before = nonDaemonThreads();

        minimal(workspace).run(ctx -> assertThat(ctx.getBean(AimonStack.class).isStarted()).isTrue());

        // A thread does not stop existing the instant something calls shutdownNow() on its pool, so this is a
        // deadline rather than an immediate read — the assertion is "eventually zero", not "zero right now".
        final long deadline = System.currentTimeMillis() + THREAD_DEATH_TIMEOUT_MS;
        Set<Thread> leaked = leakedSince(before);
        while (!leaked.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100L);
            leaked = leakedSince(before);
        }
        assertThat(names(leaked)).as("non-daemon threads still alive after the context closed").isEmpty();
    }

    @Test
    @DisplayName("a second context does not inherit or double the first one's pools")
    void poolsDoNotAccumulateAcrossContexts(@TempDir Path first, @TempDir Path second) throws InterruptedException {
        final long baseline = countNamed("pending-turn-reaper");

        final long duringFirst = openAndCountReaperThreads(first);
        awaitCount("pending-turn-reaper", baseline);

        final long duringSecond = openAndCountReaperThreads(second);
        awaitCount("pending-turn-reaper", baseline);

        // One reaper per live context, not one per context ever opened. If close() left the pool running, the
        // second count would be the first plus one — which is the shape every pool leak in this stack has.
        assertThat(duringFirst).as("the first context's reaper").isEqualTo(baseline + 1);
        assertThat(duringSecond).as("the second context's reaper — not the first one's as well").isEqualTo(duringFirst);
    }

    /**
     * B-4 — the pool that hook bodies run on ends with the context that created it.
     *
     * <p>
     * The obvious form of this test sits next to {@link #poolsDoNotAccumulateAcrossContexts} and counts threads named
     * {@code hook-executor} across two contexts. It would pass without any of the production change: the pool is
     * cached, so it creates no thread until the first submit, and a context that never fires a hook compares zero
     * against zero. Neither would the leak check above have caught it — those threads are daemons, and
     * {@link #nonDaemonThreads()} filters daemons out by design.
     *
     * <p>
     * So a hook is fired through the assembled stack, its worker captured by identity, and the assertion is that
     * <i>that</i> thread is gone once the context closes. A cached worker also retires on its own, but only after 60s;
     * dying inside {@link #THREAD_DEATH_TIMEOUT_MS} is attributable to the teardown.
     */
    @Test
    @DisplayName("the hook thread pool dies with the context that created it")
    void hookExecutorPoolDoesNotOutliveTheContext(@TempDir Path workspace) throws InterruptedException {
        final AtomicReference<Thread> worker = new AtomicReference<>();

        minimal(workspace).run(ctx -> {
            fireOneHook(ctx.getBean(AimonStack.class), worker);
            assertThat(worker.get()).as("the hook body must have run on a pool thread").isNotNull();
            assertThat(worker.get().isAlive()).isTrue();
        });

        final Thread pooled = worker.get();
        pooled.join(THREAD_DEATH_TIMEOUT_MS);
        assertThat(pooled.isAlive()).as("hook pool thread still alive after the context closed").isFalse();
    }

    /** Runs one PreTool hook through the assembled stack and records the thread its body ran on. */
    private static void fireOneHook(AimonStack stack, AtomicReference<Thread> worker) {
        final HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.PRE_TOOL, (PreToolHook) hookContext -> {
            worker.set(Thread.currentThread());
            return HookResult.success();
        });

        stack.agentExecutor().getHookExecutionManager()
                .executePreTool(PreToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName(AGENT)
                        .hookRegistry(registry).environment(Environment.createDefault())
                        .toolUse(ToolUse.of("id", "Read", Map.of("file_path", "/tmp/x"))).iterationCount(1).build());
    }

    @Test
    @DisplayName("the session router is torn down gracefully, not interrupted")
    void teardownPlanDrainsTheRouter(@TempDir Path workspace) {
        minimal(workspace).run(ctx -> {
            final List<String> plan = ctx.getBean(AimonStack.class).teardownPlan();
            assertThat(plan).anyMatch(entry -> entry.contains("closeGracefully"));
        });
    }

    @Test
    @DisplayName("a selected backend is running by the time the context is up")
    void schedulingStartsWithTheContext(@TempDir Path workspace) {
        minimal(workspace).withPropertyValues("aimon.scheduling.backend=in-memory").run(ctx -> {
            assertThat(ctx.getBean(AimonSchedulingLifecycle.class).isRunning()).isTrue();
            // Accepting a cron task is the observable form of "running": both schedulers refuse one while
            // stopped, so this assertion fails for the case below and passes only here.
            assertThat(register(ctx.getBean(AimonStack.class))).isNotNull();
        });
    }

    @Test
    @DisplayName("auto-startup=false builds the engine and leaves it stopped")
    void autoStartupFalseHoldsTheEngineBack(@TempDir Path workspace) {
        minimal(workspace)
                .withPropertyValues("aimon.scheduling.backend=in-memory", "aimon.scheduling.auto-startup=false")
                .run(ctx -> {
                    final AimonStack stack = ctx.getBean(AimonStack.class);
                    assertThat(stack.schedulingEngine()).as("the engine is built, just not started").isPresent();
                    assertThat(ctx.getBean(AimonSchedulingLifecycle.class).isRunning()).isFalse();

                    // Worth pinning because it is the half that reads backwards: a held-back engine does not hold
                    // registrations either. Both schedulers refuse to take an enabled task while stopped, so the
                    // application's own startScheduling() has to come before it registers anything, not after.
                    assertThatThrownBy(() -> register(stack)).isInstanceOf(TaskSchedulerException.class)
                            .hasMessageContaining("not running");

                    stack.startScheduling();
                    assertThat(register(stack)).isNotNull();
                });
    }

    /** Registers an enabled cron task — the cheapest thing that fails when the scheduler is not running. */
    private static ScheduledTask register(AimonStack stack) {
        return stack.schedulingEngine().orElseThrow().getTaskManager()
                .register(ScheduledTask.builder().id(ScheduledTaskId.generate()).name("nightly")
                        .cronExpression("0 3 * * *").addStep(RoutineStep.of("Read", "{}")).owner(Principal.system())
                        .boundRuntimeId(stack.primaryRuntimeId()).enabled(true).build());
    }

    private long openAndCountReaperThreads(Path workspace) {
        final AtomicReference<Long> count = new AtomicReference<>();
        minimal(workspace).run(ctx -> {
            assertThat(ctx.getBean(AimonStack.class).isStarted()).isTrue();
            count.set(countNamed("pending-turn-reaper"));
        });
        return count.get();
    }

    private static int readGracefulShutdownPhase() {
        try {
            return WebServerGracefulShutdownLifecycle.class.getField("SMART_LIFECYCLE_PHASE").getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Boot no longer exposes SMART_LIFECYCLE_PHASE", e);
        }
    }

    private static Set<Thread> nonDaemonThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive).filter(t -> !t.isDaemon())
                .collect(Collectors.toSet());
    }

    private static Set<Thread> leakedSince(Set<Thread> before) {
        return nonDaemonThreads().stream().filter(t -> !before.contains(t)).collect(Collectors.toSet());
    }

    private static List<String> names(Set<Thread> threads) {
        return threads.stream().map(Thread::getName).sorted().collect(Collectors.toList());
    }

    private static long countNamed(String name) {
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive)
                .filter(t -> name.equals(t.getName())).count();
    }

    private static void awaitCount(String name, long expected) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + THREAD_DEATH_TIMEOUT_MS;
        while (countNamed(name) != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100L);
        }
        assertThat(countNamed(name)).as("live '%s' threads", name).isEqualTo(expected);
    }

    /** Registers the stand-in at the phase Boot's web server would occupy. */
    @Configuration(proxyBeanMethods = false)
    static class WebServerStandIn {

        @Bean
        SocketSpy socketSpy(AimonStack stack, ObjectProvider<AimonSchedulingLifecycle> scheduling) {
            return new SocketSpy(stack, scheduling);
        }
    }

    /**
     * Stands in for {@code WebServerStartStopLifecycle} and records what it could see from there.
     *
     * <p>
     * A real web server would need a servlet context, which would take these cases out of the unit tier for the
     * sake of a socket nothing connects to. What matters here is only the phase.
     */
    static final class SocketSpy implements SmartLifecycle {

        private final AimonStack stack;
        private final ObjectProvider<AimonSchedulingLifecycle> scheduling;

        private volatile boolean running;

        private volatile boolean startCalled;
        private volatile boolean runtimesStartedAtOpen;
        private volatile boolean schedulingRunningAtOpen;
        private volatile boolean schedulingResolvedAtOpen;

        private volatile boolean stopCalled;
        private volatile boolean schedulingRunningAtClose;
        private volatile boolean schedulingResolvedAtClose;
        private volatile boolean stackOpenAtClose;

        SocketSpy(AimonStack stack, ObjectProvider<AimonSchedulingLifecycle> scheduling) {
            this.stack = stack;
            this.scheduling = scheduling;
        }

        @Override
        public int getPhase() {
            return WEB_SERVER_PHASE;
        }

        @Override
        public void start() {
            startCalled = true;
            runtimesStartedAtOpen = stack.isStarted();
            final AimonSchedulingLifecycle lifecycle = scheduling.getIfAvailable();
            schedulingResolvedAtOpen = lifecycle != null;
            schedulingRunningAtOpen = lifecycle != null && lifecycle.isRunning();
            running = true;
        }

        @Override
        public void stop() {
            stopCalled = true;
            final AimonSchedulingLifecycle lifecycle = scheduling.getIfAvailable();
            schedulingResolvedAtClose = lifecycle != null;
            schedulingRunningAtClose = lifecycle != null && lifecycle.isRunning();
            stackOpenAtClose = !stack.isClosed();
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }
}
