package at.aimon.core.config.hook;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.config.hook.MergedHookConfig.MergedHookEntry;
import at.aimon.core.config.hook.rewake.RewakeSpecParser;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.execution.ExecutionHook;
import at.aimon.core.hook.rewake.RewakeSpec;
import at.aimon.core.skill.hook.action.DenyAction;
import at.aimon.core.skill.hook.action.HookAction;
import at.aimon.core.skill.hook.action.HookScriptDirectory;
import at.aimon.core.skill.hook.action.HttpAction;
import at.aimon.core.skill.hook.action.HttpMethod;
import at.aimon.core.skill.hook.action.McpToolAction;
import at.aimon.core.skill.hook.action.ShellAction;
import at.aimon.core.skill.hook.declarative.DeclarativeHookOptions;
import at.aimon.core.skill.hook.declarative.DeclarativeOnConfigReloadHook;
import at.aimon.core.skill.hook.declarative.DeclarativeOnSessionEndHook;
import at.aimon.core.skill.hook.declarative.DeclarativeOnSessionStartHook;
import at.aimon.core.skill.hook.declarative.DeclarativePostToolHook;
import at.aimon.core.skill.hook.declarative.DeclarativePreCompactHook;
import at.aimon.core.skill.hook.declarative.DeclarativePreToolHook;
import at.aimon.core.skill.hook.declarative.DeclarativeShellHookBinding;
import at.aimon.core.skill.hook.declarative.ExecutionOrHostShellActionExecutor;
import at.aimon.core.skill.hook.declarative.HttpActionExecutor;
import at.aimon.core.skill.hook.declarative.IsolatedShellActionExecutor;
import at.aimon.core.skill.hook.declarative.McpActionExecutor;
import at.aimon.core.skill.hook.declarative.ShellActionExecutor;
import at.aimon.core.skill.hook.declarative.SkillHookEnv;
import at.aimon.core.skill.hook.declarative.ToolInputPredicate;
import at.aimon.core.skill.hook.declarative.predicate.NameOnlyPredicate;
import at.aimon.core.skill.hook.declarative.predicate.PredicateParser;

/**
 * Converts a {@link MergedHookConfig} into concrete declarative hooks and registers them with a {@link HookRegistry}.
 *
 * <p>
 * The bootstrap ignores {@link HookConfigSource#SKILL} entries &mdash; those are owned by the {@code
 * SkillHookActivator} flow which registers / unregisters them per skill scope. Layered USER/PROJECT/LOCAL entries are
 * registered globally in dispatch order (USER first, LOCAL last) using the synthetic skill name {@code
 * "<source>#<indexWithinThatSource>"} so logs and unregister flows can disambiguate.
 *
 * <p>
 * <b>Hook-id stability contract.</b> Async-rewake routing and hot-reload cancellation key off
 * {@code ExecutionHook#getHookId()}, so the id of a hook whose own document did not change must survive a reload. For
 * the hooks registered here that id is
 * {@code <hookClass>@<source>#<entryIndexWithinSource>#<event>[<entryIndexWithinSource>][<handlerIndex>]} &mdash; the
 * synthetic skill name carries the layer identity and the discriminator carries the position.
 *
 * <p>
 * The load-bearing detail is that the entry index is counted <b>per {@link HookConfigSource} layer</b>, not across the
 * merged dispatch stream. A merged-stream index would renumber &mdash; and therefore re-id &mdash; every untouched
 * USER hook the moment a PROJECT or LOCAL file gained or lost an entry for the same event, orphaning the pending
 * rewake envelopes keyed on the old ids and making hot-reload cancellation miss them.
 *
 * <p>
 * Residual gap (accepted): inserting or removing an entry <em>inside</em> a document still renumbers the entries after
 * it in that same document. That document was edited, so its hooks are expected to be re-materialised; only unrelated
 * layers are protected.
 *
 * <p>
 * <b>An entry that cannot be applied.</b> An entry can parse and still be unusable: a {@code command} handler with no
 * command, a {@code deny} outside {@code preTool}, a URL that is not a URI, a matcher that does not parse, a handler
 * type the event does not accept, a handler whose executor this assembly did not wire. Under an event whose hooks
 * cannot block, such an entry is skipped with a WARN and the rest of the file applies. Under a guard event
 * ({@code preTool}, {@code onStart}, {@code preCompact}, {@code permissionRequest}) it throws
 * {@link HookConfigParseException} and nothing is registered &mdash; the same stop a file that does not parse gets,
 * because the result of skipping is the same: a guard its author wrote is off. A handler that declared
 * {@code failOpen} is not a guard, and one that merely cannot run in this assembly is still skipped.
 *
 * <p>
 * <b>Where a {@code command} handler runs</b> (aimon-core #273). The applier holds two executors &mdash; a host one and
 * an environment one &mdash; and builds each handler with the one its placement picks:
 * <ul>
 * <li>no {@code "shell"} key, on an event that fires inside an execution: the execution's environment, as an isolated
 * exec, and the host only for a firing that has no execution behind it ({@link ExecutionOrHostShellActionExecutor});
 * <li>no {@code "shell"} key, on {@code onSessionStart}, {@code onSessionEnd} or {@code onConfigReload}: the host;
 * <li>{@code "shell": "host"}: the host, on every event;
 * <li>{@code "shell": "environment"}: the environment executor on its own, so a firing without an environment is not
 * run; on the three events outside any execution it cannot run at all and is skipped with a WARN;
 * <li>one exception to the default: a handler with no {@code "shell"} that declares {@code asyncRewake} on a
 * rewakeable in-execution event stays on the host, with a WARN, because the rewake replay fires outside every
 * execution.
 * </ul>
 * A command that names {@code AIMON_HOOK_DIR} carries its layer's {@code hooks/} directory on its action; the executor
 * that runs it exports the path that belongs to its shell. A missing {@code hooks/} directory makes the handler
 * inapplicable.
 *
 * <p>
 * Stateless and thread-safe; the registry must itself be thread-safe.
 */
public final class HookRegistryApplier {

    private static final Logger log = LoggerFactory.getLogger(HookRegistryApplier.class);

    /**
     * AIMON event names the rewake fire listener is able to re-fire.
     *
     * <p>
     * Kept in sync with {@code DefaultRewakeFireListener#isSupported(HookEventType)}: an event is rewakeable only if
     * the listener can rebuild its context from a stored envelope. Every other event drops its {@code asyncRewake}
     * block with a WARN.
     */
    private static final Set<String> REWAKEABLE_EVENTS = Set.of(DeclarativePreToolHook.EVENT_NAME,
            DeclarativeOnSessionStartHook.EVENT_NAME, DeclarativeOnSessionEndHook.EVENT_NAME,
            DeclarativePreCompactHook.EVENT_NAME, DeclarativeOnConfigReloadHook.EVENT_NAME);

    /** The name {@code hooks.json} commands use for the layer's script directory. */
    private static final String HOOK_DIR_REFERENCE = SkillHookEnv.AIMON_HOOK_DIR;

    /**
     * Literal spellings of a path inside a layer's {@code .aimon} directory: the home forms, and a relative
     * {@code .aimon/} that is not the staging area. The absolute layer directories are checked separately.
     */
    private static final Pattern HOST_AIMON_PATH = Pattern
            .compile("(~/\\.aimon/|\\$HOME/\\.aimon/|\\$\\{HOME\\}/\\.aimon/|(?<![\\w./~}-])\\.aimon/)");

    private final ShellActionExecutor hostShellExecutor;
    private final ShellActionExecutor environmentShellExecutor;
    private final ShellActionExecutor defaultShellExecutor;
    private final HttpActionExecutor httpExecutor;
    private final McpActionExecutor mcpExecutor;
    private final Map<String, String> processEnv;

    /**
     * Creates a bootstrap whose environment-placed commands run through an {@link IsolatedShellActionExecutor}.
     *
     * @param shellExecutor
     *            the <b>host</b> shell executor (must not be null). Since aimon-core #273 it serves only the
     *            host-placed handlers: the events outside any execution, {@code "shell": "host"}, an
     *            {@code asyncRewake} handler with no {@code "shell"}, and a firing with no execution behind it. A
     *            handler with no {@code "shell"} on an in-execution event no longer runs through it
     * @param httpExecutor
     *            HTTP executor (may be null)
     * @param mcpExecutor
     *            MCP executor (may be null)
     * @param processEnv
     *            process env snapshot for HTTP / MCP env whitelist evaluation (must not be null)
     * @deprecated name the environment executor explicitly with the five-argument constructor; the executor passed
     *             here no longer serves default-placed in-execution handlers
     */
    @Deprecated
    public HookRegistryApplier(ShellActionExecutor shellExecutor, HttpActionExecutor httpExecutor,
            McpActionExecutor mcpExecutor, Map<String, String> processEnv) {
        this(shellExecutor, new IsolatedShellActionExecutor(), httpExecutor, mcpExecutor, processEnv);
    }

    /**
     * Creates a bootstrap.
     *
     * @param hostShellExecutor
     *            the executor for host-placed commands (must not be null); normally a {@code HostShellActionExecutor}
     * @param environmentShellExecutor
     *            the executor for commands placed in the firing execution's environment (must not be null); normally
     *            an {@link IsolatedShellActionExecutor}. A custom one must export {@code AIMON_HOOK_DIR} itself from
     *            {@code ShellAction.getScriptDirectory()}, or a command that names the variable sees it unset
     * @param httpExecutor
     *            HTTP executor (may be null; an {@code http} handler then cannot be applied — fatal under a guard
     *            event, a WARN at hook time elsewhere)
     * @param mcpExecutor
     *            MCP executor (may be null; an {@code mcp} handler then cannot be applied — fatal under a guard event,
     *            a WARN at hook time elsewhere)
     * @param processEnv
     *            process env snapshot for HTTP / MCP env whitelist evaluation (must not be null)
     */
    public HookRegistryApplier(ShellActionExecutor hostShellExecutor, ShellActionExecutor environmentShellExecutor,
            HttpActionExecutor httpExecutor, McpActionExecutor mcpExecutor, Map<String, String> processEnv) {
        this.hostShellExecutor = Objects.requireNonNull(hostShellExecutor, "hostShellExecutor cannot be null");
        this.environmentShellExecutor = Objects.requireNonNull(environmentShellExecutor,
                "environmentShellExecutor cannot be null");
        this.defaultShellExecutor = new ExecutionOrHostShellActionExecutor(environmentShellExecutor, hostShellExecutor);
        this.httpExecutor = httpExecutor;
        this.mcpExecutor = mcpExecutor;
        this.processEnv = Map.copyOf(Objects.requireNonNull(processEnv, "processEnv cannot be null"));
    }

    /**
     * Registers all non-SKILL entries of {@code merged} with {@code registry}.
     *
     * @param merged
     *            the merged config (must not be null)
     * @param registry
     *            the destination registry (must not be null). When this throws, hooks registered before the bad
     *            entry are already in it: pass a registry that can be discarded, as {@link HookRegistryReloader}
     *            does
     * @throws HookConfigParseException
     *             when an entry under a guard event cannot be applied; the message names the file, the event, the
     *             entry and the handler
     */
    public void apply(MergedHookConfig merged, HookRegistry registry) {
        Objects.requireNonNull(merged, "merged cannot be null");
        Objects.requireNonNull(registry, "registry cannot be null");
        for (Map.Entry<String, List<MergedHookEntry>> e : merged.entriesByAimonEvent().entrySet()) {
            final String event = e.getKey();
            // Entry index is counted PER SOURCE LAYER, never across the merged stream: an edit in one layer must not
            // renumber — and therefore re-id — the hooks another, untouched layer contributed. See the class javadoc.
            final Map<HookConfigSource, Integer> nextIndexBySource = new EnumMap<>(HookConfigSource.class);
            for (MergedHookEntry mhe : e.getValue()) {
                if (mhe.getSource() == HookConfigSource.SKILL) {
                    log.debug("hooks: skipping SKILL entry for skill '{}' on event '{}' (handled by"
                            + " SkillHookActivator)", mhe.getSkillName(), event);
                    continue;
                }
                final int idx = nextIndexBySource.merge(mhe.getSource(), 1, Integer::sum) - 1;
                applyEntry(merged, event, mhe, idx, registry);
            }
        }
    }

    private void applyEntry(MergedHookConfig merged, String event, MergedHookEntry mhe, int idx,
            HookRegistry registry) {
        final String sourceKey = mhe.getSource().name().toLowerCase(Locale.ROOT);
        final String pseudoSkillName = sourceKey + "#" + idx;
        final boolean guardEvent = HookEventName.isGuard(event);
        final ToolInputPredicate predicate = parseMatcher(merged, event, mhe, idx);
        final List<HookHandlerSpec> handlers = mhe.getEntry().getHandlers();
        if (handlers.isEmpty()) {
            if (guardEvent) {
                throw inapplicable(merged, mhe, event, idx, -1, "the entry has no handlers");
            }
            log.warn("hooks: empty handler list for {}/{} on event '{}', skipping", mhe.getSource(),
                    mhe.getEntry().getMatcher(), event);
            return;
        }
        for (int handlerIdx = 0; handlerIdx < handlers.size(); handlerIdx++) {
            final HookHandlerSpec spec = handlers.get(handlerIdx);
            final HookAction action;
            try {
                action = toAction(spec, event, scriptDirectoryFor(merged, mhe, spec));
            } catch (IllegalArgumentException ex) {
                if (guardEvent) {
                    throw inapplicable(merged, mhe, event, idx, handlerIdx, ex.getMessage());
                }
                log.warn("hooks: invalid handler in {} on event '{}': {}", mhe.getSource(), event, ex.getMessage());
                continue;
            }
            // failOpen opens "could not decide", and a deny handler always has its verdict: on one the flag would
            // only stop the hook declaring FAIL_CLOSED, so a matcher that threw or a pool that refused the hook
            // would read as allow. Skill frontmatter drops it at the same point (SkillHookSetParser#parseFailOpen).
            final boolean failOpen = spec.isFailOpen() && !(action instanceof DenyAction);
            if (spec.isFailOpen() && !failOpen) {
                log.warn("hooks: 'failOpen' has no effect on a 'deny' handler; ignored on {} ({})", event,
                        mhe.getSource());
            }
            // A handler that declared failOpen is not a guard: leaving it out leaves no guard off, so it keeps the
            // WARN-and-skip a non-guard event gets when it cannot run here.
            final boolean guard = guardEvent && !failOpen;
            // Reload-stable, unique per registered hook. The entry index is layer-scoped (see #apply) and the layer
            // itself is already part of the id through pseudoSkillName, so this pair is unique across layers without
            // repeating the source here. The handler index is required because the entry index alone repeats across
            // the handlers of one entry, which would collapse their ids and break rewake routing / reload
            // cancellation.
            final String discriminator = event + "[" + idx + "][" + handlerIdx + "]";
            // Where a command runs; every other action ignores the executor it is built with.
            final ShellActionExecutor shellExecutor = shellExecutorFor(spec, event, mhe);
            if (action instanceof ShellAction && !shellExecutor.isShellSupported()) {
                // Registering it anyway would not be harmless: the command can never run, and a guard event reads
                // "could not run" as a block, so every matching preTool / onStart would be refused. Dropping a guard
                // is worse still, so on a guard event this stops the load instead.
                if (guard) {
                    throw inapplicable(merged, mhe, event, idx, handlerIdx, "type=command cannot run: the configured"
                            + " shell executor does not support shell actions");
                }
                log.warn("hooks: 'command' on {} ({}) cannot run: the configured shell executor does not support"
                        + " shell actions; skipping", event, mhe.getSource());
                continue;
            }
            // Honoured for command, http and mcp alike: each can fail to produce a verdict.
            final DeclarativeHookOptions options = DeclarativeHookOptions.builder().hookIdDiscriminator(discriminator)
                    .rewakeSpec(toRewakeSpec(spec, mhe, event, action, shellExecutor)).failOpen(failOpen)
                    .hostPathHint(hostPathHint(merged, action, shellExecutor)).build();
            switch (event) {
                case DeclarativePreToolHook.EVENT_NAME -> {
                    // The shell question again, for the other two transports: a guard that can never be asked.
                    if (guard && action instanceof HttpAction && httpExecutor == null) {
                        throw inapplicable(merged, mhe, event, idx, handlerIdx,
                                "type=http cannot run: no HttpActionExecutor is wired in this assembly");
                    }
                    if (guard && action instanceof McpToolAction && mcpExecutor == null) {
                        throw inapplicable(merged, mhe, event, idx, handlerIdx,
                                "type=mcp cannot run: no McpActionExecutor is wired in this assembly");
                    }
                    registry.register(HookEventType.PRE_TOOL, new DeclarativePreToolHook(pseudoSkillName, predicate,
                            action, shellExecutor, httpExecutor, mcpExecutor, processEnv, options));
                }
                case DeclarativePostToolHook.EVENT_NAME -> {
                    if (action instanceof DenyAction) {
                        log.warn("hooks: 'deny' is not valid on postTool ({}); skipping", mhe.getSource());
                        continue;
                    }
                    registry.register(HookEventType.POST_TOOL, new DeclarativePostToolHook(pseudoSkillName, predicate,
                            action, shellExecutor, httpExecutor, mcpExecutor, processEnv, options));
                }
                default -> {
                    // Every remaining event is shell-only and shares one constructor shape, so it resolves through
                    // the same binding table skill frontmatter uses rather than through a switch arm each.
                    final DeclarativeShellHookBinding<?> binding = DeclarativeShellHookBinding.forEvent(event)
                            .orElse(null);
                    if (binding == null) {
                        log.warn("hooks: event '{}' is not recognised; skipping {} entry", event, mhe.getSource());
                        continue;
                    }
                    if (!(action instanceof ShellAction shell)) {
                        if (guardEvent) {
                            throw inapplicable(merged, mhe, event, idx, handlerIdx,
                                    "only 'command' handlers are valid on " + event);
                        }
                        log.warn("hooks: only 'command' actions are valid on {} ({}); skipping", event,
                                mhe.getSource());
                        continue;
                    }
                    if (!shellExecutor.canRunOn(binding.getEventType())) {
                        // Only reachable when an embedder wires an environment-bound executor into hooks.json: the
                        // event has no execution environment, so the command would be skipped on every firing.
                        if (guard) {
                            throw inapplicable(merged, mhe, event, idx, handlerIdx, "type=command cannot run: the"
                                    + " event fires outside any execution and the configured shell executor only runs"
                                    + " in an execution environment");
                        }
                        log.warn("hooks: 'command' on {} ({}) cannot run: the event fires outside any execution and"
                                + " the configured shell executor only runs in an execution environment; skipping",
                                event, mhe.getSource());
                        continue;
                    }
                    register(registry, binding, pseudoSkillName, shell, shellExecutor, options);
                }
            }
        }
    }

    /**
     * The failure for an entry under a guard event that parsed but cannot be applied.
     *
     * <p>
     * Skipping such an entry starts the host with the guard its author wrote missing, which is the failure a
     * {@code hooks.json} that does not parse already stops startup for. The message is worded the same way and names
     * the file, the event, the entry and the handler. It never quotes the handler's command or URL: either may carry
     * a secret, and this text reaches a console and {@code OnConfigReload} hooks.
     *
     * @param handlerIdx
     *            the handler's position in the entry, or -1 when the problem is the entry itself
     */
    private static HookConfigParseException inapplicable(MergedHookConfig merged, MergedHookEntry mhe, String event,
            int idx, int handlerIdx, String reason) {
        return new HookConfigParseException(merged.describe(mhe.getSource()) + " is invalid: " + event + " entry #"
                + idx + (handlerIdx < 0 ? "" : ", handler #" + handlerIdx) + ": " + reason + ". An entry under an"
                + " event whose hooks can block is not skipped when it cannot be applied: that would leave the guard"
                + " off");
    }

    /**
     * Parses the handler's {@code asyncRewake} block into a runtime spec, or returns {@code null} when the handler
     * declared none.
     *
     * <p>
     * Only events whose context the rewake fire listener can rebuild may carry a rewake — see
     * {@link #REWAKEABLE_EVENTS}. A spec on any other event is dropped with a WARN rather than registered: the hook
     * would emit it on every fire and the listener would discard every one of them, which is silently broken config
     * rather than a working feature. A malformed block is likewise dropped with a WARN, keeping {@code hooks.json}
     * fail-soft — one bad rewake block must not take down the whole hook.
     *
     * <p>
     * A rewake on a shell action is also dropped when the shell executor
     * {@linkplain ShellActionExecutor#requiresExecutionEnvironment() needs an execution environment}: the replay
     * rebuilds its context outside the execution that first fired the hook, so every replay would be skipped.
     */
    private RewakeSpec toRewakeSpec(HookHandlerSpec spec, MergedHookEntry mhe, String event, HookAction action,
            ShellActionExecutor shellExecutor) {
        if (spec.getAsyncRewake() == null) {
            return null;
        }
        if (action instanceof ShellAction && shellExecutor.requiresExecutionEnvironment()) {
            log.warn("hooks: 'asyncRewake' on a 'command' handler is ignored on event '{}' ({}): a rewake replay"
                    + " fires outside the execution, and the configured shell executor only runs in an execution"
                    + " environment", event, mhe.getSource());
            return null;
        }
        if (!REWAKEABLE_EVENTS.contains(event)) {
            log.warn("hooks: 'asyncRewake' is not supported on event '{}' ({}); the spec is ignored. Supported"
                    + " events: {}", event, mhe.getSource(), REWAKEABLE_EVENTS);
            return null;
        }
        try {
            return RewakeSpecParser.parse(spec.getAsyncRewake());
        } catch (HookConfigParseException ex) {
            log.warn("hooks: invalid 'asyncRewake' block on event '{}' ({}): {}; the spec is ignored", event,
                    mhe.getSource(), ex.getMessage());
            return null;
        }
    }

    /**
     * Parses the entry's matcher. A matcher that does not parse falls back to a name-only match on the raw string,
     * which matches no real tool &mdash; so on {@code preTool}, the one guard event that reads the matcher, it is not
     * a fallback but a guard that silently never fires, and stops the load instead.
     *
     * <p>
     * "Does not parse" includes a term that reads as a tool name no tool can have ({@code "^Edit$"},
     * {@code "tool=Bash"}, {@code "Bash & input.command~^npm"}, {@code "mcp__.*"}): {@link PredicateParser} refuses it
     * for the same reason, since parsing it produced exactly that never-firing guard without going through this
     * fallback at all.
     *
     * <p>
     * {@code postTool} cannot block, so there such a term is left out and the terms beside it are kept:
     * {@code "Bash|mcp__.*"} went on firing on {@code Bash} before the refusal existed and still does. The events that
     * never read a matcher do not have theirs parsed &mdash; a WARN about a matcher nothing consults says something
     * that is not so.
     */
    private ToolInputPredicate parseMatcher(MergedHookConfig merged, String event, MergedHookEntry mhe, int idx) {
        final String matcher = mhe.getEntry().getMatcher();
        if (matcher == null || matcher.isBlank() || "*".equals(matcher.strip())) {
            return NameOnlyPredicate.ANY;
        }
        final boolean preTool = DeclarativePreToolHook.EVENT_NAME.equals(event);
        if (!preTool && !DeclarativePostToolHook.EVENT_NAME.equals(event)) {
            return NameOnlyPredicate.ANY;
        }
        try {
            if (preTool) {
                return PredicateParser.parse(matcher);
            }
            return PredicateParser.parseKeepingMatchableTerms(matcher,
                    reason -> log.warn(
                            "hooks: a term of matcher '{}' on event '{}' ({}) is left out, and the hook"
                                    + " fires only on the terms beside it: {}",
                            matcher, event, mhe.getSource(), reason));
        } catch (IllegalArgumentException ex) {
            if (preTool) {
                throw inapplicable(merged, mhe, event, idx, -1,
                        "the matcher could not be parsed (" + ex.getMessage() + ")");
            }
            log.warn("hooks: matcher '{}' could not be parsed ({}); falling back to name-only", matcher,
                    ex.getMessage());
            return NameOnlyPredicate.of(matcher);
        }
    }

    /**
     * Picks the executor a handler's command runs through, by the placement table in the class javadoc.
     */
    private ShellActionExecutor shellExecutorFor(HookHandlerSpec spec, String event, MergedHookEntry mhe) {
        final Optional<HookHandlerSpec.Shell> declared = spec.getShell();
        if (declared.isPresent()) {
            return declared.get() == HookHandlerSpec.Shell.HOST ? hostShellExecutor : environmentShellExecutor;
        }
        final Optional<HookEventType<?>> eventType = eventTypeOf(event);
        if (eventType.isEmpty() || !eventType.get().firesInsideExecution()) {
            return hostShellExecutor;
        }
        if (spec.getType() == HookHandlerSpec.Type.COMMAND && spec.getAsyncRewake() != null
                && REWAKEABLE_EVENTS.contains(event)) {
            // The rewake replay rebuilds its context outside every execution, so a command placed in the
            // environment would have its rewake dropped. Kept where it ran before, and said so.
            log.warn("hooks: a 'command' handler with 'asyncRewake' on event '{}' ({}) runs on the host, not in the"
                    + " execution's environment: the rewake replay fires outside the execution. Declare \"shell\":"
                    + " \"host\" on the handler to silence this", event, mhe.getSource());
            return hostShellExecutor;
        }
        return defaultShellExecutor;
    }

    /** The event constant behind an AIMON event name, or empty for a name this applier does not register. */
    private static Optional<HookEventType<?>> eventTypeOf(String event) {
        if (DeclarativePreToolHook.EVENT_NAME.equals(event)) {
            return Optional.of(HookEventType.PRE_TOOL);
        }
        if (DeclarativePostToolHook.EVENT_NAME.equals(event)) {
            return Optional.of(HookEventType.POST_TOOL);
        }
        return DeclarativeShellHookBinding.forEvent(event).map(DeclarativeShellHookBinding::getEventType);
    }

    /**
     * The {@code hooks/} directory a command that names {@code AIMON_HOOK_DIR} carries, or null for a command that
     * does not name it.
     *
     * @throws IllegalArgumentException
     *             when the command names the variable and the directory is missing, is not a directory, or the
     *             layer's file is not known; the caller turns that into a skip or, on a guard event, a stop
     */
    private static HookScriptDirectory scriptDirectoryFor(MergedHookConfig merged, MergedHookEntry mhe,
            HookHandlerSpec spec) {
        if (spec.getType() != HookHandlerSpec.Type.COMMAND || spec.getCommand() == null
                || !spec.getCommand().contains(HOOK_DIR_REFERENCE)) {
            return null;
        }
        final Path origin = merged.origin(mhe.getSource()).orElseThrow(() -> new IllegalArgumentException(
                "the command names " + HOOK_DIR_REFERENCE + ", but the layer's file is not known"));
        final Path layerDirectory = origin.toAbsolutePath().getParent();
        final Path hooksDirectory = layerDirectory.resolve(HookScriptDirectory.DIRECTORY_NAME);
        if (!Files.isDirectory(hooksDirectory)) {
            throw new IllegalArgumentException("the command names " + HOOK_DIR_REFERENCE + ", but " + hooksDirectory
                    + " is missing or not a directory");
        }
        return HookScriptDirectory.of(layerDirectory,
                HookScriptDirectory.DIRECTORY_NAME + "-" + mhe.getSource().name().toLowerCase(Locale.ROOT));
    }

    /**
     * The hint for a command placed in an execution's environment whose text names a path inside a layer's
     * {@code .aimon} directory, or null. Such a path exists on the host, and may not in the environment: when the shell
     * there cannot start the command, the reason says so. Nothing is refused here &mdash; whether the environment is
     * the host cannot be known when the file is applied. The hint names the spelling it found, never the command.
     */
    private String hostPathHint(MergedHookConfig merged, HookAction action, ShellActionExecutor shellExecutor) {
        if (!(action instanceof ShellAction shell) || shellExecutor == hostShellExecutor) {
            return null;
        }
        final String command = shell.getCommand();
        String found = null;
        final Matcher matcher = HOST_AIMON_PATH.matcher(command);
        if (matcher.find()) {
            found = matcher.group(1);
        } else {
            for (HookConfigSource source : HookConfigSource.values()) {
                final Optional<Path> origin = merged.origin(source);
                if (origin.isPresent() && command.contains(origin.get().toAbsolutePath().getParent().toString())) {
                    found = origin.get().toAbsolutePath().getParent().toString();
                    break;
                }
            }
        }
        if (found == null) {
            return null;
        }
        return "the command names host path " + found + ", which this execution environment may not have — use $"
                + HOOK_DIR_REFERENCE + "/… or \"shell\": \"host\"";
    }

    private HookAction toAction(HookHandlerSpec spec, String event, HookScriptDirectory scriptDirectory) {
        return switch (spec.getType()) {
            case COMMAND -> {
                if (spec.getCommand() == null || spec.getCommand().isBlank()) {
                    throw new IllegalArgumentException("type=command requires non-blank 'command'");
                }
                yield new ShellAction(spec.getCommand(),
                        spec.getTimeoutMs() == null ? null : Duration.ofMillis(spec.getTimeoutMs()), scriptDirectory);
            }
            case HTTP -> toHttp(spec);
            case MCP -> toMcp(spec);
            case DENY -> {
                if (!DeclarativePreToolHook.EVENT_NAME.equals(event)) {
                    throw new IllegalArgumentException("type=deny is only valid on preTool");
                }
                if (spec.getReason() == null || spec.getReason().isBlank()) {
                    throw new IllegalArgumentException("type=deny requires non-blank 'reason'");
                }
                yield new DenyAction(spec.getReason());
            }
        };
    }

    private static HttpAction toHttp(HookHandlerSpec spec) {
        if (spec.getUrl() == null || spec.getUrl().isBlank()) {
            throw new IllegalArgumentException("type=http requires non-blank 'url'");
        }
        final URI uri;
        try {
            uri = new URI(spec.getUrl());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("type=http has invalid 'url': " + e.getMessage(), e);
        }
        final HttpAction.Builder b = HttpAction.builder().url(uri);
        if (spec.getMethod() != null) {
            try {
                b.method(HttpMethod.valueOf(spec.getMethod().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("type=http unknown 'method': " + spec.getMethod(), e);
            }
        }
        b.headers(spec.getHeaders());
        if (spec.getBodyTemplate() != null) {
            b.bodyTemplate(spec.getBodyTemplate());
        }
        if (!spec.getAllowedEnvVars().isEmpty()) {
            b.allowedEnvVars(spec.getAllowedEnvVars());
        }
        if (spec.getTimeoutMs() != null) {
            b.timeout(Duration.ofMillis(spec.getTimeoutMs()));
        }
        return b.build();
    }

    /**
     * Registers one shell-only hook, recovering the binding's hook type through capture conversion so
     * {@link HookRegistry#register} stays type-safe without a cast.
     */
    private <H extends ExecutionHook<?>> void register(HookRegistry registry, DeclarativeShellHookBinding<H> binding,
            String skillName, ShellAction action, ShellActionExecutor shellExecutor, DeclarativeHookOptions options) {
        registry.register(binding.getEventType(), binding.create(skillName, action, shellExecutor, options));
    }

    private static McpToolAction toMcp(HookHandlerSpec spec) {
        if (spec.getServerName() == null || spec.getServerName().isBlank()) {
            throw new IllegalArgumentException("type=mcp requires non-blank 'server'");
        }
        if (spec.getToolName() == null || spec.getToolName().isBlank()) {
            throw new IllegalArgumentException("type=mcp requires non-blank 'tool'");
        }
        final McpToolAction.Builder b = McpToolAction.builder().serverName(spec.getServerName())
                .toolName(spec.getToolName());
        if (!spec.getArgs().isEmpty()) {
            b.argsTemplate(spec.getArgs());
        }
        if (spec.getTimeoutMs() != null) {
            b.timeout(Duration.ofMillis(spec.getTimeoutMs()));
        }
        return b.build();
    }
}
