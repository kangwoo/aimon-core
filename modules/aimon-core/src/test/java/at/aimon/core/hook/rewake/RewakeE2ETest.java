package at.aimon.core.hook.rewake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.rewake.impl.DefaultRewakeFireListener;
import at.aimon.core.hook.rewake.impl.DefaultRewakeService;
import at.aimon.core.llm.ToolUse;

/**
 * Phase 4A WI-4A.10 — End-to-end async-rewake test exercising the full pipeline:
 *
 * <ol>
 * <li>A {@link PreToolHook} returns a {@link HookResult} carrying a {@link RewakeSpec} (delay trigger).
 * <li>{@link DefaultHookExecutionManager} feeds the spec to {@link DefaultRewakeService} which schedules an
 * envelope on a {@link ScheduledExecutorService}.
 * <li>After the delay elapses, {@link DefaultRewakeFireListener} resolves the originating
 * {@link RewakeCapableRuntime} from {@link DefaultAgentRuntimeRegistry} and re-dispatches the hook via the
 * captured {@link HookRegistry}.
 * </ol>
 *
 * <p>
 * Cron repeat is exercised at the impl level by
 * {@code at.aimon.scheduling.quartz.rewake.QuartzRewakeServiceTest#cronTriggerFiresRecurrently} —
 * {@link DefaultRewakeService} rejects cron triggers with {@link UnsupportedOperationException}, so a cron E2E in
 * this module is impossible without pulling the Quartz scheduler in. Surviving JVM restart is also out of scope for
 * the in-memory MVP; persistence belongs to the Quartz-backed impl.
 */
class RewakeE2ETest {

    private static final String AGENT_NAME = "e2e-agent";
    private static final AgentRuntimeId CTX_ID = AgentRuntimeId.fromName(AGENT_NAME);

    private ScheduledExecutorService scheduler;
    private DefaultRewakeService rewakeService;
    private DefaultAgentRuntimeRegistry agentRuntimeRegistry;
    private DefaultHookExecutionManager executionManager;
    private DefaultHookRegistry hookRegistry;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "rewake-e2e-scheduler");
            t.setDaemon(true);
            return t;
        });
        agentRuntimeRegistry = new DefaultAgentRuntimeRegistry();
        rewakeService = new DefaultRewakeService(new DefaultRewakeFireListener(agentRuntimeRegistry), Clock.systemUTC(),
                scheduler);
        executionManager = new DefaultHookExecutionManager().withRewakeService(rewakeService);
        hookRegistry = new DefaultHookRegistry();
        agentRuntimeRegistry.register(new CapableContext(hookRegistry));
    }

    @AfterEach
    void tearDown() {
        rewakeService.close();
        scheduler.shutdownNow();
    }

    @Test
    void delayRewakeFiresAndRedispatchesHookEndToEnd() {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<PreToolContext> secondInvocation = new AtomicReference<>();
        final RewakeSpec spec = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofMillis(50)))
                .reason("await approval").build();

        final PreToolHook hook = ctx -> {
            final int callIndex = calls.incrementAndGet();
            if (callIndex == 1) {
                return HookResult.asyncRewake(spec);
            }
            secondInvocation.set(ctx);
            return HookResult.allow();
        };
        hookRegistry.register(HookEventType.PRE_TOOL, hook);

        executionManager.executePreTool(buildPreToolContext("Bash", "echo hi"));

        // Envelope was scheduled by the manager → service holds exactly one pending registration.
        assertThat(rewakeService.listPending()).hasSize(1);

        // Wait for the delay listener to fire and the second hook invocation to land.
        await().atMost(Duration.ofSeconds(2)).until(() -> calls.get() >= 2);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(rewakeService.listPending()).isEmpty();
        assertThat(secondInvocation.get()).isNotNull();
        // The rebuilt context preserves the originating tool name and the agent identity.
        assertThat(secondInvocation.get().getCurrentToolUse().getName()).isEqualTo("Bash");
        assertThat(secondInvocation.get().getInvokerName()).isEqualTo(AGENT_NAME);
        assertThat(secondInvocation.get().getHookRegistry()).isSameAs(hookRegistry);
    }

    @Test
    void cancelByOriginatingHookIdPreventsRedispatch() throws InterruptedException {
        final AtomicInteger calls = new AtomicInteger();
        final RewakeSpec spec = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofMillis(150)))
                .reason("await approval").build();

        final PreToolHook hook = new PreToolHook() {
            @Override
            public HookResult execute(PreToolContext context) {
                calls.incrementAndGet();
                return HookResult.asyncRewake(spec);
            }

            @Override
            public String getHookId() {
                return "approval-hook";
            }
        };
        hookRegistry.register(HookEventType.PRE_TOOL, hook);

        executionManager.executePreTool(buildPreToolContext("Bash", "echo hi"));
        assertThat(rewakeService.listPending()).hasSize(1);

        // Cancel before the delay elapses — mirrors the hot-reload removal path (WI-4A.9).
        final int cancelled = rewakeService.cancelByOriginatingHookId("approval-hook");
        assertThat(cancelled).isEqualTo(1);
        assertThat(rewakeService.listPending()).isEmpty();

        // Wait beyond the delay to confirm no second invocation arrives.
        TimeUnit.MILLISECONDS.sleep(300);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void eventTriggerFiresOnResolveAndRedispatchesHook() {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<PreToolContext> secondInvocation = new AtomicReference<>();
        final RewakeSpec spec = RewakeSpec.builder().trigger(new RewakeTriggerEvent("webhook", "ticket-T-1"))
                .reason("await callback").build();

        final PreToolHook hook = ctx -> {
            if (calls.incrementAndGet() == 1) {
                return HookResult.asyncRewake(spec);
            }
            secondInvocation.set(ctx);
            return HookResult.allow();
        };
        hookRegistry.register(HookEventType.PRE_TOOL, hook);

        executionManager.executePreTool(buildPreToolContext("Bash", "echo hi"));
        assertThat(rewakeService.listPending()).hasSize(1);

        // Deliver the matching external event — the envelope fires synchronously on the calling thread.
        final int fired = rewakeService.resolve("webhook", "ticket-T-1", java.util.Map.of("status", "approved"));

        assertThat(fired).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(rewakeService.listPending()).isEmpty();
        assertThat(secondInvocation.get()).isNotNull();
        assertThat(secondInvocation.get().getCurrentToolUse().getName()).isEqualTo("Bash");
    }

    private PreToolContext buildPreToolContext(String toolName, String command) {
        final ToolUse tu = ToolUse.of("e2e-tool-call-1", toolName, java.util.Map.of("command", command));
        return PreToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName(AGENT_NAME)
                .hookRegistry(hookRegistry).environment(Environment.createDefault()).toolUse(tu).iterationCount(0)
                .timestamp(Instant.now()).build();
    }

    /** Minimal {@link AgentRuntime} that opts into rewake re-dispatch. */
    private static final class CapableContext implements AgentRuntime, RewakeCapableRuntime {
        private final HookRegistry hookRegistry;
        private final Environment environment = Environment.createDefault();

        CapableContext(HookRegistry hookRegistry) {
            this.hookRegistry = hookRegistry;
        }

        @Override
        public AgentRuntimeId getId() {
            return CTX_ID;
        }

        @Override
        public Agent getAgent() {
            throw new UnsupportedOperationException("not used in rewake e2e");
        }

        @Override
        public List<Tool> getAvailableTools() {
            return List.of();
        }

        @Override
        public HookRegistry getHookRegistry() {
            return hookRegistry;
        }

        @Override
        public Environment getEnvironment() {
            return environment;
        }
    }
}
