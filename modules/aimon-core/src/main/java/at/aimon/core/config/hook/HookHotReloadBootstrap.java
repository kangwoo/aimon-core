package at.aimon.core.config.hook;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.rewake.RewakeService;
import at.aimon.core.skill.hook.declarative.HttpActionExecutor;
import at.aimon.core.skill.hook.declarative.McpActionExecutor;
import at.aimon.core.skill.hook.declarative.ShellActionExecutor;

/**
 * Reusable wiring for the hot-reload pipeline (CLI today; web bootstraps when they adopt it).
 *
 * <p>
 * Materialises a {@link HookConfigLoader} / {@link HookConfigMerger} / {@link HookRegistryApplier} /
 * {@link HookRegistryReloader} stack against {@code <userHome>/.aimon/} and {@code <projectRoot>/.aimon/}, runs the
 * initial bootstrap, then starts a {@link HookConfigWatcher} that watches:
 *
 * <ul>
 * <li>{@code <userHome>/.aimon/hooks.json} (USER layer)
 * <li>{@code <projectRoot>/.aimon/hooks.json} (PROJECT layer)
 * <li>{@code <projectRoot>/.aimon/hooks.local.json} (LOCAL layer)
 * </ul>
 *
 * <p>
 * The returned {@link Started} is application-scoped — close it at shutdown so the polling thread exits cleanly.
 *
 * <p>
 * <b>A broken {@code hooks.json} stops startup.</b> When the initial load fails &mdash; a file in any layer that does
 * not parse or cannot be read &mdash; {@link Builder#start()} throws {@link HookConfigParseException} and nothing is
 * registered or watched: letting the host run would run it with every file guard off. A missing file is not a
 * failure. A host that would rather start without its file hooks has to say so in code, by catching the exception.
 * Watcher-start failure stays non-fatal (the hooks are already applied): the call returns a {@code Started} whose
 * {@link Started#isWatcherActive()} reports {@code false}, and a WARN is logged.
 *
 * <p>
 * <b>Web bootstrap usage.</b> Web entry points (those that call
 * {@code OrcaAgentRuntimeManager.getOrCreateRuntime(bundle, ...)} once at startup) can enable hot reload by:
 *
 * <pre>{@code
 * Started reload = HookHotReloadBootstrap.builder()
 *         .userHome(Paths.get(System.getProperty("user.home")))
 *         .projectRoot(Paths.get(workingDirectory))
 *         .shellExecutor(shellExecutor)
 *         .processEnv(System.getenv())
 *         .registry(agentRuntime.getHookRegistry())
 *         .executionManager(agentExecutor.getHookExecutionManager())
 *         .invoker(new ReloadInvoker(InvokerType.MAIN_AGENT, agentName, UserLocale.createDefault()))
 *         .rewakeService(rewakeService)
 *         .start();
 * }</pre>
 *
 * and add {@code reload.close()} to the shutdown sequence.
 *
 * <p>
 * {@link Builder#rewakeService(RewakeService)} is optional but should be supplied by any deployment that has async
 * rewake wired: it is what lets a reload cancel the pending envelopes of hooks that just disappeared from the config.
 * Omitting it keeps the previous no-op behaviour ({@link RewakeService#NOOP}), i.e. no cancellation at all.
 */
public final class HookHotReloadBootstrap {

    private static final Logger log = LoggerFactory.getLogger(HookHotReloadBootstrap.class);

    private static final String AIMON_DIR = ".aimon";
    private static final String HOOKS_JSON = "hooks.json";
    private static final String HOOKS_LOCAL_JSON = "hooks.local.json";

    private HookHotReloadBootstrap() {
    }

    /** Creates a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Result of {@link Builder#start()}. Owns the started watcher (when present). Closing this object stops the polling
     * thread and is idempotent. The internal reloader has no resources to release and is intentionally not exposed —
     * external triggers should go through {@code hooks.json} edits, not a back-door reload call.
     */
    public static final class Started implements AutoCloseable {

        private final HookConfigWatcher watcher;

        private Started(HookConfigWatcher watcher) {
            this.watcher = watcher;
        }

        /**
         * Always true: a failed initial load makes {@link Builder#start()} throw, so no {@code Started} exists for
         * it.
         *
         * @return always {@code true}
         * @deprecated the result has been always {@code true} since a failed initial load started to throw (EE-71), so
         *             a branch on it is dead code. Having a {@code Started} at all is the success; to start without
         *             the file hooks, catch {@link HookConfigParseException} from {@link Builder#start()}.
         */
        @Deprecated
        public boolean isBootstrapSucceeded() {
            return true;
        }

        /** True iff the watcher started — i.e. subsequent {@code hooks.json} edits will be detected. */
        public boolean isWatcherActive() {
            return watcher != null;
        }

        @Override
        public void close() {
            if (watcher != null) {
                try {
                    watcher.close();
                } catch (RuntimeException e) {
                    log.warn("HookConfigWatcher close reported: {}", e.getMessage());
                }
            }
        }
    }

    /** Builder for {@link HookHotReloadBootstrap}. All required fields throw on missing in {@link #start()}. */
    public static final class Builder {

        private Path userHome;
        private Path projectRoot;
        private ShellActionExecutor shellExecutor;
        private HttpActionExecutor httpExecutor;
        private McpActionExecutor mcpExecutor;
        private Map<String, String> processEnv;
        private HookRegistry registry;
        private HookExecutionManager executionManager;
        private ReloadInvoker invoker;
        private RewakeService rewakeService = RewakeService.NOOP;

        private Builder() {
        }

        /** User home directory; the helper appends {@code .aimon/hooks.json}. Required. */
        public Builder userHome(Path userHome) {
            this.userHome = userHome;
            return this;
        }

        /**
         * Project / working-directory root; the helper appends {@code .aimon/hooks.json} and
         * {@code .aimon/hooks.local.json}. Required.
         */
        public Builder projectRoot(Path projectRoot) {
            this.projectRoot = projectRoot;
            return this;
        }

        /**
         * Shell executor used by {@link HookRegistryApplier}. Required. {@code hooks.json} can declare events that fire
         * outside any execution, so this is normally a {@code HostShellActionExecutor}; an executor that needs an
         * execution environment has its shell handlers on those events skipped at apply time.
         */
        public Builder shellExecutor(ShellActionExecutor shellExecutor) {
            this.shellExecutor = shellExecutor;
            return this;
        }

        /** Optional HTTP executor; absence makes HTTP entries fail-soft at hook time. */
        public Builder httpExecutor(HttpActionExecutor httpExecutor) {
            this.httpExecutor = httpExecutor;
            return this;
        }

        /** Optional MCP executor; absence makes MCP entries fail-soft at hook time. */
        public Builder mcpExecutor(McpActionExecutor mcpExecutor) {
            this.mcpExecutor = mcpExecutor;
            return this;
        }

        /** Process env snapshot used by {@code ${env.X}} whitelist evaluation. Required. */
        public Builder processEnv(Map<String, String> processEnv) {
            this.processEnv = processEnv;
            return this;
        }

        /** Live registry to swap against. Required. */
        public Builder registry(HookRegistry registry) {
            this.registry = registry;
            return this;
        }

        /**
         * Execution manager used to fire {@code OnConfigReload} after a swap; {@code null} suppresses event firing
         * (rarely useful outside tests).
         */
        public Builder executionManager(HookExecutionManager executionManager) {
            this.executionManager = executionManager;
            return this;
        }

        /** Invoker identity threaded into {@code OnConfigReloadContext}. Required. */
        public Builder invoker(ReloadInvoker invoker) {
            this.invoker = invoker;
            return this;
        }

        /**
         * Application-scoped rewake service used to cancel pending {@code RewakeEnvelope}s whose originating hook
         * disappeared from the merged config on reload.
         *
         * <p>
         * Optional: omitting it (or passing {@code null}) keeps the previous behaviour, {@link RewakeService#NOOP},
         * which never cancels anything. Deployments that have already built a real service (the CLI's
         * {@code DefaultRewakeService}, or the Quartz-backed implementation) must pass it here, otherwise a reload
         * that removes a hook leaves its deferred fires scheduled against a hook that no longer exists.
         *
         * @param rewakeService
         *            the live rewake service, or {@code null} for {@link RewakeService#NOOP}
         * @return this builder
         */
        public Builder rewakeService(RewakeService rewakeService) {
            this.rewakeService = rewakeService == null ? RewakeService.NOOP : rewakeService;
            return this;
        }

        /**
         * Materialises the pipeline, runs the initial bootstrap, then starts the watcher.
         *
         * <p>
         * Watcher-start failure is logged at WARN and surfaced via {@link Started#isWatcherActive()}.
         *
         * @throws HookConfigParseException
         *             when a {@code hooks.json} that is present fails to parse or cannot be read; the message names
         *             the file and its layer. Nothing has been registered and no watcher is running
         * @throws RuntimeException
         *             when merging or applying the loaded config fails; the registry is rolled back
         */
        public Started start() {
            Objects.requireNonNull(userHome, "userHome");
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(shellExecutor, "shellExecutor");
            Objects.requireNonNull(processEnv, "processEnv");
            Objects.requireNonNull(registry, "registry");
            Objects.requireNonNull(invoker, "invoker");

            final Path userHookDir = userHome.resolve(AIMON_DIR);
            final Path projectHookDir = projectRoot.resolve(AIMON_DIR);

            final HookConfigLoader loader = new HookConfigLoader(new JacksonHookConfigParser(), userHookDir,
                    projectHookDir);
            final HookConfigMerger merger = new HookConfigMerger();
            final HookRegistryApplier bootstrap = new HookRegistryApplier(shellExecutor, httpExecutor, mcpExecutor,
                    processEnv);
            final HookRegistryReloader reloader = new HookRegistryReloader(loader, merger, bootstrap, registry,
                    executionManager, invoker, rewakeService);

            reloader.loadInitial();

            final HookConfigWatcher watcher = new HookConfigWatcher(List.of(userHookDir.resolve(HOOKS_JSON),
                    projectHookDir.resolve(HOOKS_JSON), projectHookDir.resolve(HOOKS_LOCAL_JSON)), reloader::reload);
            try {
                watcher.start();
            } catch (RuntimeException e) {
                log.warn("HookConfigWatcher failed to start; hot reload disabled: {}", e.getMessage());
                try {
                    watcher.close();
                } catch (RuntimeException ignored) {
                    // best-effort cleanup
                }
                return new Started(null);
            }
            return new Started(watcher);
        }
    }
}
