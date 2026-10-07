package at.aimon.core.config.hook;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
import at.aimon.core.skill.hook.declarative.HttpActionExecutor;
import at.aimon.core.skill.hook.declarative.McpActionExecutor;
import at.aimon.core.skill.hook.declarative.ShellActionExecutor;
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

    private final ShellActionExecutor shellExecutor;
    private final HttpActionExecutor httpExecutor;
    private final McpActionExecutor mcpExecutor;
    private final Map<String, String> processEnv;

    /**
     * Creates a bootstrap.
     *
     * @param shellExecutor
     *            shell executor (must not be null)
     * @param httpExecutor
     *            HTTP executor (may be null; an {@code http} handler then cannot be applied — fatal under a guard
     *            event, a WARN at hook time elsewhere)
     * @param mcpExecutor
     *            MCP executor (may be null; an {@code mcp} handler then cannot be applied — fatal under a guard event,
     *            a WARN at hook time elsewhere)
     * @param processEnv
     *            process env snapshot for HTTP / MCP env whitelist evaluation (must not be null)
     */
    public HookRegistryApplier(ShellActionExecutor shellExecutor, HttpActionExecutor httpExecutor,
            McpActionExecutor mcpExecutor, Map<String, String> processEnv) {
        this.shellExecutor = Objects.requireNonNull(shellExecutor, "shellExecutor cannot be null");
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
                action = toAction(spec, event);
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
            // failOpen is honoured for command, http and mcp alike: each can fail to produce a verdict.
            final DeclarativeHookOptions options = DeclarativeHookOptions.builder().hookIdDiscriminator(discriminator)
                    .rewakeSpec(toRewakeSpec(spec, mhe, event, action)).failOpen(failOpen)
                    .ignoreInterrupt(ignoreInterrupt(spec, mhe, event, action)).build();
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
                    register(registry, binding, pseudoSkillName, shell, options);
                }
            }
        }
    }

    /**
     * Reads the handler's {@code ignoreInterrupt}, dropping it with a WARN where it cannot have an effect.
     *
     * <p>
     * It is honoured for a {@code command} on a {@linkplain HookEventName#isReport(String) report event} and nowhere
     * else: a guard of a cancelled execution must stop and block, an event outside any execution has no interrupt to
     * ignore, and an {@code http} / {@code mcp} call is not tied to the execution's signal in the first place. Skill
     * frontmatter drops it at the same points ({@code SkillHookSetParser#parseIgnoreInterrupt}).
     */
    private static boolean ignoreInterrupt(HookHandlerSpec spec, MergedHookEntry mhe, String event, HookAction action) {
        if (!spec.isIgnoreInterrupt()) {
            return false;
        }
        if (HookEventName.isGuard(event)) {
            log.warn("hooks: 'ignoreInterrupt' has no effect on {} ({}): the event can block, and a guard of an"
                    + " interrupted execution is stopped; ignored", event, mhe.getSource());
            return false;
        }
        if (!HookEventName.isReport(event)) {
            log.warn("hooks: 'ignoreInterrupt' has no effect on {} ({}): the event fires outside any execution, so"
                    + " there is no interrupt to ignore; ignored", event, mhe.getSource());
            return false;
        }
        if (!(action instanceof ShellAction)) {
            log.warn("hooks: 'ignoreInterrupt' has no effect on a handler that is not a 'command' ({} on {}): only a"
                    + " command is tied to the execution's interrupt; ignored", mhe.getSource(), event);
            return false;
        }
        return true;
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
    private RewakeSpec toRewakeSpec(HookHandlerSpec spec, MergedHookEntry mhe, String event, HookAction action) {
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

    private HookAction toAction(HookHandlerSpec spec, String event) {
        return switch (spec.getType()) {
            case COMMAND -> {
                if (spec.getCommand() == null || spec.getCommand().isBlank()) {
                    throw new IllegalArgumentException("type=command requires non-blank 'command'");
                }
                yield new ShellAction(spec.getCommand(),
                        spec.getTimeoutMs() == null ? null : Duration.ofMillis(spec.getTimeoutMs()));
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
            String skillName, ShellAction action, DeclarativeHookOptions options) {
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
