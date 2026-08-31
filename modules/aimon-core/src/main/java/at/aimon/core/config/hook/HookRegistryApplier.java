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
     *            HTTP executor (may be null; absence makes HTTP entries fail-soft at hook time)
     * @param mcpExecutor
     *            MCP executor (may be null; absence makes MCP entries fail-soft at hook time)
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
     *            the destination registry (must not be null)
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
                applyEntry(event, mhe, idx, registry);
            }
        }
    }

    private void applyEntry(String event, MergedHookEntry mhe, int idx, HookRegistry registry) {
        final String sourceKey = mhe.getSource().name().toLowerCase(Locale.ROOT);
        final String pseudoSkillName = sourceKey + "#" + idx;
        final ToolInputPredicate predicate = parseMatcher(mhe.getEntry().getMatcher());
        final List<HookHandlerSpec> handlers = mhe.getEntry().getHandlers();
        if (handlers.isEmpty()) {
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
                log.warn("hooks: invalid handler in {} on event '{}': {}", mhe.getSource(), event, ex.getMessage());
                continue;
            }
            // Reload-stable, unique per registered hook. The entry index is layer-scoped (see #apply) and the layer
            // itself is already part of the id through pseudoSkillName, so this pair is unique across layers without
            // repeating the source here. The handler index is required because the entry index alone repeats across
            // the handlers of one entry, which would collapse their ids and break rewake routing / reload
            // cancellation.
            final String discriminator = event + "[" + idx + "][" + handlerIdx + "]";
            final DeclarativeHookOptions options = DeclarativeHookOptions.builder().hookIdDiscriminator(discriminator)
                    .rewakeSpec(toRewakeSpec(spec, mhe, event)).build();
            switch (event) {
                case DeclarativePreToolHook.EVENT_NAME ->
                    registry.register(HookEventType.PRE_TOOL, new DeclarativePreToolHook(pseudoSkillName, predicate,
                            action, shellExecutor, httpExecutor, mcpExecutor, processEnv, options));
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
                        log.warn("hooks: only 'command' actions are valid on {} ({}); skipping", event,
                                mhe.getSource());
                        continue;
                    }
                    register(registry, binding, pseudoSkillName, shell, options);
                }
            }
        }
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
     */
    private RewakeSpec toRewakeSpec(HookHandlerSpec spec, MergedHookEntry mhe, String event) {
        if (spec.getAsyncRewake() == null) {
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

    private ToolInputPredicate parseMatcher(String matcher) {
        if (matcher == null || matcher.isBlank() || "*".equals(matcher.strip())) {
            return NameOnlyPredicate.ANY;
        }
        try {
            return PredicateParser.parse(matcher);
        } catch (IllegalArgumentException ex) {
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
