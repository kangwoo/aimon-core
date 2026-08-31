package at.aimon.core.hook;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.hook.event.OnConfigReloadContext;
import at.aimon.core.hook.event.OnSessionEndContext;
import at.aimon.core.hook.event.OnSessionStartContext;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.event.OnStopContext;
import at.aimon.core.hook.event.PermissionDeniedContext;
import at.aimon.core.hook.event.PermissionRequestContext;
import at.aimon.core.hook.event.PostCompactContext;
import at.aimon.core.hook.event.PostToolContext;
import at.aimon.core.hook.event.PreCompactContext;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.event.SubagentStartContext;
import at.aimon.core.hook.event.SubagentStopContext;
import at.aimon.core.hook.execution.AskPromptHandler;
import at.aimon.core.hook.execution.Decision;
import at.aimon.core.hook.execution.DefaultHookExecutor;
import at.aimon.core.hook.execution.ExecutionHook;
import at.aimon.core.hook.execution.FlowControl;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.hook.execution.HookExecutionPolicy;
import at.aimon.core.hook.execution.HookExecutor;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.rewake.RewakeEnvelopes;
import at.aimon.core.hook.rewake.RewakeService;
import at.aimon.core.hook.rewake.RewakeSpec;

/**
 * Manages hook execution with configurable policies for different hook types.
 *
 * <p>
 * This class coordinates hook execution through a {@link HookExecutor} and applies different
 * {@link HookExecutionPolicy} strategies for each hook type (OnStart, PreTool, PostTool, OnStop). This allows
 * fine-grained control over error handling and execution flow for different lifecycle stages.
 *
 * <p>
 * <b>Default Policies:</b>
 * <ul>
 * <li><b>OnStart:</b> Continue on exception, never stop (monitoring only)</li>
 * <li><b>PreTool:</b> Continue on exception, stop on blocked (allows hook-based tool permission control)</li>
 * <li><b>PostTool:</b> Continue on exception, never stop (monitoring only)</li>
 * <li><b>OnStop:</b> Continue on exception, never stop (guaranteed execution for cleanup)</li>
 * </ul>
 *
 * <p>
 * <b>Thread Safety:</b> Thread-safe. All hook executions are delegated to the underlying {@link HookExecutor}.
 *
 * <p>
 * <b>Example usage:</b>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create manager with default policies
 *     HookExecutionManager manager = new DefaultHookExecutionManager();
 *
 *     // Execute PreTool hooks before tool execution
 *     PreToolContext context = PreToolContext.builder().invokerType(InvokerType.MAIN_AGENT)
 *             .invokerName("default-agent").hookRegistry(hookRegistry).toolName("ReadTool").build();
 *
 *     List<HookResult> results = manager.executePreTool(context);
 *
 *     // Check if any hook blocked the tool execution
 *     if (manager.hasBlockedResult(results)) {
 *         // Tool execution should be prevented
 *         List<String> feedbacks = manager.collectFeedback(results);
 *         // Send feedback to user/LLM
 *         return;
 *     }
 *
 *     // Proceed with tool execution
 * }
 * </pre>
 *
 * @see HookExecutor
 * @see HookExecutionPolicy
 * @see HookRegistry
 */
public class DefaultHookExecutionManager implements HookExecutionManager, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DefaultHookExecutionManager.class);

    private final HookExecutor executor;

    /**
     * The executor to release in {@link #close()}, or {@code null} when there is nothing of ours to release.
     *
     * <p>
     * Non-null only when the builder's own default created the executor <i>and</i> that executor holds resources. An
     * executor handed in via {@link Builder#executor(HookExecutor)} is borrowed and is never closed here — its creator
     * outlives this manager and closes it.
     */
    private final AutoCloseable ownedExecutor;

    private final HookExecutionPolicy onStartPolicy;
    private final HookExecutionPolicy preToolPolicy;
    private final HookExecutionPolicy postToolPolicy;
    private final HookExecutionPolicy onStopPolicy;
    private final HookExecutionPolicy preCompactAutoPolicy;
    private final HookExecutionPolicy preCompactManualPolicy;
    private final HookExecutionPolicy postCompactPolicy;
    private final HookExecutionPolicy permissionRequestPolicy;
    private final AskPromptHandler askPromptHandler;
    private final RewakeService rewakeService;

    /**
     * Creates a new HookExecutor with default executor and default policies for every hook stage. The PreTool
     * dispatcher resolves {@link Decision#ASK} via {@link AskPromptHandler#fromEnv(java.util.Map)} on
     * {@link System#getenv()} (default deny when {@code AIMON_HOOK_ASK_DEFAULT} is unset).
     */
    public DefaultHookExecutionManager() {
        this(builder());
    }

    private DefaultHookExecutionManager(Builder builder) {
        this.executor = Objects.requireNonNull(builder.executor, "Executor cannot be null");
        // Ownership, not type, decides what close() touches: only the executor this builder created itself, and only
        // if it has something to release. A supplied one is the caller's, whatever it is made of.
        this.ownedExecutor = !builder.executorSupplied && builder.executor instanceof AutoCloseable closeable
                ? closeable
                : null;
        this.onStartPolicy = Objects.requireNonNull(builder.onStartPolicy, "onStartPolicy cannot be null");
        this.preToolPolicy = Objects.requireNonNull(builder.preToolPolicy, "preToolPolicy cannot be null");
        this.postToolPolicy = Objects.requireNonNull(builder.postToolPolicy, "postToolPolicy cannot be null");
        this.onStopPolicy = Objects.requireNonNull(builder.onStopPolicy, "onStopPolicy cannot be null");
        this.preCompactAutoPolicy = Objects.requireNonNull(builder.preCompactAutoPolicy,
                "preCompactAutoPolicy cannot be null");
        this.preCompactManualPolicy = Objects.requireNonNull(builder.preCompactManualPolicy,
                "preCompactManualPolicy cannot be null");
        this.postCompactPolicy = Objects.requireNonNull(builder.postCompactPolicy, "postCompactPolicy cannot be null");
        // permissionRequestPolicy defaults to preToolPolicy for backward compatibility.
        this.permissionRequestPolicy = Objects.requireNonNull(
                builder.permissionRequestPolicy != null ? builder.permissionRequestPolicy : this.preToolPolicy,
                "permissionRequestPolicy cannot be null");
        // askPromptHandler defaults to the environment-derived handler (default deny).
        this.askPromptHandler = builder.askPromptHandler != null
                ? builder.askPromptHandler
                : AskPromptHandler.fromEnv(System.getenv());
        // NOOP keeps the bootstrap path working (specs are logged and dropped) until a real service is wired.
        this.rewakeService = builder.rewakeService != null ? builder.rewakeService : RewakeService.NOOP;
    }

    /**
     * Returns a new {@link Builder} pre-populated with the default executor and the default per-hook policies. The
     * permission-request policy defaults to {@code preToolPolicy} and the {@link AskPromptHandler} defaults to
     * {@link AskPromptHandler#fromEnv(java.util.Map)} on {@link System#getenv()} unless overridden.
     *
     * @return a fresh builder carrying today's defaults
     */
    public static Builder builder() {
        return new Builder();
    }

    private DefaultHookExecutionManager(DefaultHookExecutionManager src, RewakeService rewakeService) {
        this.executor = src.executor;
        // Ownership moves with the executor. withRewakeService is a replace, not a fan-out — the source is discarded
        // at the call site, so leaving the duty behind would strand the pool with no one left holding a handle to it.
        this.ownedExecutor = src.ownedExecutor;
        this.onStartPolicy = src.onStartPolicy;
        this.preToolPolicy = src.preToolPolicy;
        this.postToolPolicy = src.postToolPolicy;
        this.onStopPolicy = src.onStopPolicy;
        this.preCompactAutoPolicy = src.preCompactAutoPolicy;
        this.preCompactManualPolicy = src.preCompactManualPolicy;
        this.postCompactPolicy = src.postCompactPolicy;
        this.permissionRequestPolicy = src.permissionRequestPolicy;
        this.askPromptHandler = src.askPromptHandler;
        this.rewakeService = Objects.requireNonNull(rewakeService, "rewakeService cannot be null");
    }

    /**
     * Returns a copy of this manager with the given {@link RewakeService} wired in. When a hook
     * returns one or more {@link RewakeSpec rewake specs} on its {@link HookResult}, the manager builds an envelope per
     * spec and hands it to {@code rewakeService.schedule(...)} after the hook chain completes.
     *
     * <p>
     * The default constructors leave the service at {@link RewakeService#NOOP}, which logs a WARN and drops the
     * envelope — useful during the bootstrap migration but not a production wiring. Callers that want async-rewake to
     * actually fire must invoke this method with a real service (in-memory {@code DefaultRewakeService} or the
     * Quartz-backed impl).
     *
     * @param rewakeService
     *            service to wire (must not be null)
     * @return a fresh manager with the same policies/handler and the given service
     * @throws NullPointerException
     *             if {@code rewakeService} is null
     */
    public DefaultHookExecutionManager withRewakeService(RewakeService rewakeService) {
        return new DefaultHookExecutionManager(this, rewakeService);
    }

    /**
     * Releases the {@link HookExecutor} this manager created, and nothing else.
     *
     * <p>
     * Executing hooks is a stateless service, so {@link HookExecutionManager} does not carry a lifecycle and most
     * implementations have nothing to release. This one does, because {@link Builder} defaults the executor to a
     * {@link at.aimon.core.hook.execution.DefaultHookExecutor DefaultHookExecutor} that creates its own thread pool —
     * and the creator releases what it created (see {@code docs/overview/scope-model.md} §2). An executor supplied via
     * {@link Builder#executor(HookExecutor)} is left alone.
     *
     * <p>
     * Calling this does not put the manager into a dead state: a hook submitted afterwards is rejected by the stopped
     * pool and the resulting {@link java.util.concurrent.RejectedExecutionException} is mapped by the stage's
     * {@link HookExecutionPolicy}, the same way a saturated pool already behaves.
     *
     * @throws Exception
     *             if the owned executor's own {@code close()} fails
     */
    @Override
    public void close() throws Exception {
        if (ownedExecutor != null) {
            ownedExecutor.close();
        }
    }

    /**
     * Executes all OnStart hooks.
     *
     * @param context
     *            The context (must not be null)
     * @return List of results, one per hook (never null)
     * @throws NullPointerException
     *             if context is null
     */
    @Override
    public List<HookResult> executeOnStart(OnStartContext context) {
        return dispatch(HookEventType.ON_START, context, onStartPolicy);
    }

    /**
     * Executes all PreTool hooks.
     *
     * <p>
     * If any hook blocks, execution should be prevented.
     *
     * @param context
     *            The context (must not be null)
     * @return List of results, one per hook (never null)
     * @throws NullPointerException
     *             if context is null
     */
    @Override
    public List<HookResult> executePreTool(PreToolContext context) {
        return promoteAskResults(dispatch(HookEventType.PRE_TOOL, context, preToolPolicy));
    }

    /**
     * Replaces every {@link Decision#ASK} entry with a concrete {@link Decision#ALLOW} or {@link Decision#DENY} by
     * invoking {@link AskPromptHandler#resolve(String)} with the hook's feedback as the prompt.
     *
     * <p>
     * The returned list preserves order and length of the input; non-ASK entries pass through unchanged.
     * {@code feedback}
     * / {@code updatedInput} / {@code updatedOutput} from the original ASK result are preserved on the promoted result
     * so
     * the dispatcher can still surface why the hook had asked.
     */
    private List<HookResult> promoteAskResults(List<HookResult> raw) {
        if (raw.stream().noneMatch(r -> r.getDecision() == Decision.ASK)) {
            return raw;
        }
        final List<HookResult> resolved = new ArrayList<>(raw.size());
        for (HookResult result : raw) {
            if (result.getDecision() != Decision.ASK) {
                resolved.add(result);
                continue;
            }
            final String prompt = result.getFeedback().orElse("");
            final Decision answer = askPromptHandler.resolve(prompt);
            log.info("Ask hook resolved by handler: prompt='{}' decision={}", prompt, answer);
            final HookResult.Builder builder = HookResult.builder().decision(answer);
            if (answer == Decision.DENY) {
                builder.flowControl(FlowControl.BLOCK);
            }
            result.getFeedback().ifPresent(builder::feedback);
            result.getUpdatedInput().ifPresent(builder::updatedInput);
            result.getUpdatedOutput().ifPresent(builder::updatedOutput);
            for (RewakeSpec spec : result.getRewakeSpecs()) {
                builder.rewakeSpec(spec);
            }
            resolved.add(builder.build());
        }
        return resolved;
    }

    /**
     * Executes all PostTool hooks.
     *
     * @param context
     *            The context (must not be null)
     * @return List of results, one per hook (never null)
     * @throws NullPointerException
     *             if context is null
     */
    @Override
    public List<HookResult> executePostTool(PostToolContext context) {
        return dispatch(HookEventType.POST_TOOL, context, postToolPolicy);
    }

    /**
     * Executes all OnStop hooks.
     *
     * <p>
     * Guaranteed to execute even if execution failed.
     *
     * @param context
     *            The context (must not be null)
     * @return List of results, one per hook (never null)
     * @throws NullPointerException
     *             if context is null
     */
    @Override
    public List<HookResult> executeOnStop(OnStopContext context) {
        return dispatch(HookEventType.ON_STOP, context, onStopPolicy);
    }

    /**
     * Executes all PreCompact hooks.
     *
     * <p>
     * AUTO triggers use the blocking policy (a blocked result aborts compaction); MANUAL triggers use the never-stop
     * policy (the user explicitly requested it, so blocks become advisory warnings to be inspected by the caller via
     * {@link #collectBlockedReasons(List)}).
     */
    @Override
    public List<HookResult> executePreCompact(PreCompactContext context) {
        Objects.requireNonNull(context, "Context cannot be null");
        final HookExecutionPolicy policy = context.getTrigger() == CompactionTrigger.MANUAL
                ? preCompactManualPolicy
                : preCompactAutoPolicy;
        return dispatch(HookEventType.PRE_COMPACT, context, policy);
    }

    /** Executes all PostCompact hooks (non-blocking). */
    @Override
    public List<HookResult> executePostCompact(PostCompactContext context) {
        return dispatch(HookEventType.POST_COMPACT, context, postCompactPolicy);
    }

    /**
     * Executes all PermissionRequest hooks.
     *
     * <p>
     * Uses the dedicated {@link #permissionRequestPolicy} so callers can keep gate-keep semantics (sequential,
     * stop-on-blocked) even when {@code preToolPolicy} is tuned otherwise (e.g. PARALLEL). {@link Decision#ASK}
     * results are promoted via the configured {@link AskPromptHandler}, identical to
     * {@link #executePreTool(PreToolContext)}.
     */
    @Override
    public List<HookResult> executePermissionRequest(PermissionRequestContext context) {
        return promoteAskResults(dispatch(HookEventType.PERMISSION_REQUEST, context, permissionRequestPolicy));
    }

    /**
     * Executes all PermissionDenied hooks.
     *
     * <p>
     * Advisory chain — uses the never-stop policy. Hook results are typically routed to audit/logging sinks; the deny
     * decision itself is already final by the time this fires.
     */
    @Override
    public List<HookResult> executePermissionDenied(PermissionDeniedContext context) {
        return dispatch(HookEventType.PERMISSION_DENIED, context, postToolPolicy);
    }

    /**
     * Executes all SubagentStart hooks.
     *
     * <p>
     * Advisory chain — uses the never-stop policy so a failing hook never aborts the subagent dispatch.
     */
    @Override
    public List<HookResult> executeSubagentStart(SubagentStartContext context) {
        return dispatch(HookEventType.SUBAGENT_START, context, onStartPolicy);
    }

    /**
     * Executes all SubagentStop hooks.
     *
     * <p>
     * Advisory chain — uses the never-stop policy so cleanup/audit hooks always run on both success and failure paths.
     */
    @Override
    public List<HookResult> executeSubagentStop(SubagentStopContext context) {
        return dispatch(HookEventType.SUBAGENT_STOP, context, onStopPolicy);
    }

    /**
     * Executes all OnSessionStart hooks.
     *
     * <p>
     * Advisory chain — uses the never-stop policy so a failing hook never aborts session creation.
     */
    @Override
    public List<HookResult> executeOnSessionStart(OnSessionStartContext context) {
        return dispatch(HookEventType.ON_SESSION_START, context, onStartPolicy);
    }

    /**
     * Executes all OnSessionEnd hooks.
     *
     * <p>
     * Advisory chain — uses the never-stop policy so cleanup/audit hooks always run on both clean and abnormal
     * termination paths.
     */
    @Override
    public List<HookResult> executeOnSessionEnd(OnSessionEndContext context) {
        return dispatch(HookEventType.ON_SESSION_END, context, onStopPolicy);
    }

    /**
     * Executes all OnConfigReload hooks.
     *
     * <p>
     * Application-scoped, advisory chain — uses the never-stop policy so a failing hook never aborts a reload (the
     * reload itself is governed by the watcher's own rollback path).
     */
    @Override
    public List<HookResult> executeOnConfigReload(OnConfigReloadContext context) {
        return dispatch(HookEventType.ON_CONFIG_RELOAD, context, onStartPolicy);
    }

    /**
     * Common dispatch path for every hook stage. Resolves the hook list, runs the executor, and schedules any
     * {@link RewakeSpec rewake specs} the hooks emitted via
     * {@link RewakeService#schedule(at.aimon.core.hook.rewake.RewakeEnvelope)}.
     *
     * <p>
     * The returned list is parallel-by-index with the <b>post-dedup</b> hook list — i.e. the registered hook list after
     * {@link HookExecutionPolicy#dedupKeyExtractor()} has removed duplicate-keyed hooks, possibly truncated at the
     * first BLOCKED result when {@link HookExecutionPolicy#stopOnBlocked()} is set. It is <b>not</b> parallel-by-index
     * with {@code registry.getHooks(eventType)} whenever the policy carries a dedup extractor that drops a hook: dedup
     * can remove an element from the middle of the list, so index {@code i} of the results may belong to a later hook
     * than index {@code i} of the registry list. Attribution-sensitive consumers must pair against
     * {@link #effectiveHooks(List, HookExecutionPolicy)}, never against the registry list.
     */
    private <C extends HookContext, H extends ExecutionHook<C>> List<HookResult> dispatch(HookEventType<H> eventType,
            C context, HookExecutionPolicy policy) {
        Objects.requireNonNull(context, "Context cannot be null");
        final HookRegistry registry = context.getHookRegistry();
        final List<H> hooks = registry.getHooks(eventType);
        final List<HookResult> results = executor.execute(hooks, context, policy);
        scheduleRewakes(eventType, context, hooks, results, policy);
        return results;
    }

    /**
     * Re-derives the hook list the executor actually ran, so a result can be attributed back to the hook that produced
     * it.
     *
     * <p>
     * <b>Must stay in step with {@code DefaultHookExecutor#applyDedup}</b> — same semantics, deliberately duplicated
     * rather than exposed on the {@link HookExecutor} SPI: the first occurrence of each non-null, non-empty dedup key
     * is kept, and hooks whose key is {@code null} or empty always pass through. Dedup drops elements from anywhere in
     * the list (unlike the {@code stopOnBlocked} short-circuit, which only truncates a suffix), so pairing results
     * against the registry list would silently mis-attribute every result after the first drop.
     *
     * @param hooks
     *            the registered hooks, in registry order (must not be null)
     * @param policy
     *            the policy the executor was handed (must not be null)
     * @return the post-dedup hook list; the input list itself when nothing is dropped (never null)
     */
    private static <H extends ExecutionHook<?>> List<H> effectiveHooks(List<H> hooks, HookExecutionPolicy policy) {
        final HookExecutionPolicy.DedupKeyExtractor extractor = policy.dedupKeyExtractor();
        if (extractor == null || hooks.isEmpty()) {
            return hooks;
        }
        final Set<String> seen = new LinkedHashSet<>();
        final List<H> kept = new ArrayList<>(hooks.size());
        for (H hook : hooks) {
            final String key = extractor.extract(hook);
            if (key == null || key.isEmpty() || seen.add(key)) {
                kept.add(hook);
            }
        }
        return kept.size() == hooks.size() ? hooks : kept;
    }

    private <C extends HookContext, H extends ExecutionHook<C>> void scheduleRewakes(HookEventType<H> eventType,
            C context, List<H> hooks, List<HookResult> results, HookExecutionPolicy policy) {
        if (rewakeService == RewakeService.NOOP) {
            // Common bootstrap path — short-circuit before constructing envelopes only to drop them.
            for (HookResult r : results) {
                if (!r.getRewakeSpecs().isEmpty()) {
                    log.warn("Hook returned {} rewake spec(s) but no RewakeService is wired (eventType={}).",
                            r.getRewakeSpecs().size(), eventType.name());
                    break;
                }
            }
            return;
        }
        // Results are parallel-by-index with the *post-dedup* hook list, not the registry list — pairing against the
        // latter would stamp a spec with a different hook's id and DefaultRewakeFireListener would then re-dispatch
        // the wrong hook (or drop the envelope). A custom HookExecutor that ignores dedup returns more results than
        // the deduped list has entries; in that case the registry list is the correct pairing.
        final List<H> deduped = effectiveHooks(hooks, policy);
        final List<H> attributed = results.size() > deduped.size() ? hooks : deduped;
        final int common = Math.min(attributed.size(), results.size());
        for (int i = 0; i < common; i++) {
            final HookResult r = results.get(i);
            if (r.getRewakeSpecs().isEmpty()) {
                continue;
            }
            final ExecutionHook<?> hook = attributed.get(i);
            for (RewakeSpec spec : r.getRewakeSpecs()) {
                try {
                    rewakeService.schedule(RewakeEnvelopes.from(eventType, context, hook, spec));
                } catch (RuntimeException e) {
                    log.warn("Failed to schedule rewake (hookId={}, eventType={}, reason={}): {}", hook.getHookId(),
                            eventType.name(), spec.getReason(), e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Fluent builder for {@link DefaultHookExecutionManager}.
     *
     * <p>
     * All fields default to today's canonical defaults: the {@link DefaultHookExecutor}, the standard per-hook
     * {@link HookExecutionPolicy policies}, a permission-request policy that mirrors {@code preToolPolicy}, and an
     * {@link AskPromptHandler} derived from the environment ({@link AskPromptHandler#fromEnv(java.util.Map)} on
     * {@link System#getenv()}). Override only what you need.
     */
    public static final class Builder {
        private HookExecutor executor = new DefaultHookExecutor();
        // Distinguishes the default above from anything the caller passed to executor(...) — the built manager closes
        // only the former. A null check would not do: executor(null) is a caller's choice too, and it fails at build.
        private boolean executorSupplied;
        private HookExecutionPolicy onStartPolicy = HookExecutionPolicy.continueOnExceptionAndNeverStop();
        private HookExecutionPolicy preToolPolicy = HookExecutionPolicy.continueOnExceptionButStopOnBlocked();
        private HookExecutionPolicy postToolPolicy = HookExecutionPolicy.continueOnExceptionAndNeverStop();
        private HookExecutionPolicy onStopPolicy = HookExecutionPolicy.continueOnExceptionAndNeverStop();
        private HookExecutionPolicy preCompactAutoPolicy = HookExecutionPolicy.continueOnExceptionButStopOnBlocked();
        private HookExecutionPolicy preCompactManualPolicy = HookExecutionPolicy.continueOnExceptionAndNeverStop();
        private HookExecutionPolicy postCompactPolicy = HookExecutionPolicy.continueOnExceptionAndNeverStop();
        // null => defaults to preToolPolicy at build time.
        private HookExecutionPolicy permissionRequestPolicy;
        // null => defaults to AskPromptHandler.fromEnv(System.getenv()) at build time.
        private AskPromptHandler askPromptHandler;
        // null => defaults to RewakeService.NOOP at build time (rewake specs are logged and dropped).
        private RewakeService rewakeService;

        private Builder() {
        }

        /**
         * Supplies the executor to run hook bodies with.
         *
         * <p>
         * The supplied executor is <b>borrowed</b>: {@link DefaultHookExecutionManager#close()} will not close it,
         * because whoever created it may still be using it elsewhere.
         *
         * @param executor
         *            the executor to use
         * @return this builder
         */
        public Builder executor(HookExecutor executor) {
            this.executor = executor;
            this.executorSupplied = true;
            return this;
        }

        public Builder onStartPolicy(HookExecutionPolicy onStartPolicy) {
            this.onStartPolicy = onStartPolicy;
            return this;
        }

        public Builder preToolPolicy(HookExecutionPolicy preToolPolicy) {
            this.preToolPolicy = preToolPolicy;
            return this;
        }

        public Builder postToolPolicy(HookExecutionPolicy postToolPolicy) {
            this.postToolPolicy = postToolPolicy;
            return this;
        }

        public Builder onStopPolicy(HookExecutionPolicy onStopPolicy) {
            this.onStopPolicy = onStopPolicy;
            return this;
        }

        public Builder preCompactAutoPolicy(HookExecutionPolicy preCompactAutoPolicy) {
            this.preCompactAutoPolicy = preCompactAutoPolicy;
            return this;
        }

        public Builder preCompactManualPolicy(HookExecutionPolicy preCompactManualPolicy) {
            this.preCompactManualPolicy = preCompactManualPolicy;
            return this;
        }

        public Builder postCompactPolicy(HookExecutionPolicy postCompactPolicy) {
            this.postCompactPolicy = postCompactPolicy;
            return this;
        }

        public Builder permissionRequestPolicy(HookExecutionPolicy permissionRequestPolicy) {
            this.permissionRequestPolicy = permissionRequestPolicy;
            return this;
        }

        public Builder askPromptHandler(AskPromptHandler askPromptHandler) {
            this.askPromptHandler = askPromptHandler;
            return this;
        }

        /**
         * Wires the {@link RewakeService} that {@code asyncRewake}-emitting hooks schedule through.
         *
         * <p>
         * Without this the manager keeps {@link RewakeService#NOOP}, which logs a WARN and drops every envelope, so a
         * hook's {@code asyncRewake} silently never fires. This setter exists so a single builder chain can produce a
         * fully wired manager; {@link DefaultHookExecutionManager#withRewakeService(RewakeService)} remains available
         * for wiring a service onto an already-built manager.
         *
         * @param rewakeService
         *            the service to wire (may be null, which keeps the NOOP default)
         * @return this builder
         */
        public Builder rewakeService(RewakeService rewakeService) {
            this.rewakeService = rewakeService;
            return this;
        }

        public DefaultHookExecutionManager build() {
            return new DefaultHookExecutionManager(this);
        }
    }
}
