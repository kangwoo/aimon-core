package at.aimon.core.tools.code;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import at.aimon.core.agent.tool.OfferKey;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolOfferContext;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.ToolScopedHook;
import at.aimon.core.programmatic.OfferFacts;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.tools.ExecutionEnvironmentAccess;
import at.aimon.core.tools.HookRegistryAccess;
import at.aimon.core.tools.bash.BashTool;

/**
 * The offer-context keys for the environment facts {@code ExecuteCode} reads at offer time
 * ({@code docs/design/tool/programmatic-tool-calling.md} §4 (a), §6.2).
 *
 * <p>
 * {@code at.aimon.core.agent.tool} may not depend on the environment, so the keys live here, with their one reader. A
 * loop that builds a {@link ToolOfferContext} calls {@link #putEnvironmentFacts} with its tool context; every fact is
 * static — the descriptor, the shell's declared features, whether a runtime file system exists, the agent's allow-list
 * and hooks — so reading them never provisions anything.
 */
public final class CodeOfferKeys {

    /** The descriptor of the iteration's execution environment. */
    public static final OfferKey<EnvironmentDescriptor> ENVIRONMENT_DESCRIPTOR = OfferKey.of("environmentDescriptor",
            EnvironmentDescriptor.class);

    /** The features the environment's shell declares, as a set of {@link ShellFeature}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static final OfferKey<Set<ShellFeature>> SHELL_FEATURES = OfferKey.of("shellFeatures", (Class) Set.class);

    /** Whether the environment offers a runtime file system. */
    public static final OfferKey<Boolean> RUNTIME_FILE_SYSTEM = OfferKey.of("runtimeFileSystem", Boolean.class);

    /**
     * Whether the agent's allow-list and hooks leave {@code Bash} unconstrained (shell doc §4) — the half of
     * {@link OfferFacts#isBashUnconstrained()} that does not need the offer itself; the other half, that {@code Bash}
     * is eligible for the offer, is asked of the offer when the facts are read.
     */
    public static final OfferKey<Boolean> BASH_SETTINGS_UNCONSTRAINED = OfferKey.of("bashSettingsUnconstrained",
            Boolean.class);

    private CodeOfferKeys() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Copies the static environment facts of {@code toolContext} into {@code builder}, for a loop that knows no
     * allow-list of its own: {@code Bash} then never counts as unconstrained.
     *
     * @param builder
     *            the offer context being built (must not be null)
     * @param toolContext
     *            the iteration's tool context (must not be null)
     * @return {@code builder}
     */
    public static ToolOfferContext.Builder putEnvironmentFacts(ToolOfferContext.Builder builder,
            ToolContext toolContext) {
        return putEnvironmentFacts(builder, toolContext, List.of());
    }

    /**
     * Copies the static environment facts of {@code toolContext} into {@code builder}: the environment's descriptor,
     * its shell's declared features and whether it offers a runtime file system, when the context carries an
     * environment; and whether {@code allowedTools} and the context's hook registry leave {@code Bash} unconstrained.
     *
     * @param builder
     *            the offer context being built (must not be null)
     * @param toolContext
     *            the iteration's tool context (must not be null)
     * @param allowedTools
     *            the agent's allow-list — the one its admission filter is built from (must not be null; empty means
     *            "unrestricted", which does not make {@code Bash} unconstrained)
     * @return {@code builder}
     */
    public static ToolOfferContext.Builder putEnvironmentFacts(ToolOfferContext.Builder builder,
            ToolContext toolContext, List<AllowedTool> allowedTools) {
        Objects.requireNonNull(builder, "builder cannot be null");
        Objects.requireNonNull(toolContext, "toolContext cannot be null");
        Objects.requireNonNull(allowedTools, "allowedTools cannot be null");
        final Optional<ExecutionEnvironment> environment = ExecutionEnvironmentAccess.of(toolContext);
        environment.ifPresent(env -> {
            builder.put(ENVIRONMENT_DESCRIPTOR, env.descriptor());
            builder.put(SHELL_FEATURES, shellFeatures(env));
            builder.put(RUNTIME_FILE_SYSTEM, hasRuntimeFileSystem(env));
        });
        builder.put(BASH_SETTINGS_UNCONSTRAINED, allowListLeavesBashUnconstrained(allowedTools)
                && HookRegistryAccess.of(toolContext).map(CodeOfferKeys::noHookAppliesToBash).orElse(true));
        return builder;
    }

    /**
     * Returns the static facts an engine judges its availability on, read from an offer context.
     *
     * @param offer
     *            the offer context (must not be null)
     * @return the facts (never null)
     */
    public static OfferFacts factsOf(ToolOfferContext offer) {
        Objects.requireNonNull(offer, "offer cannot be null");
        final boolean bash = offer.get(BASH_SETTINGS_UNCONSTRAINED).orElse(false)
                && !offer.eligible(List.of(BashTool.TOOL_NAME)).isEmpty();
        return OfferFacts.builder().descriptor(offer.get(ENVIRONMENT_DESCRIPTOR).orElse(null))
                .shellFeatures(offer.get(SHELL_FEATURES).orElse(Set.of()))
                .runtimeFileSystem(offer.get(RUNTIME_FILE_SYSTEM).orElse(false)).bashUnconstrained(bash).build();
    }

    /**
     * The shell's declared features. An unavailable environment's shell answers every call with an exception; its
     * features are then none, so no engine that needs them is offered, and the offer itself does not fail.
     */
    private static Set<ShellFeature> shellFeatures(ExecutionEnvironment environment) {
        final Set<ShellFeature> features = EnumSet.noneOf(ShellFeature.class);
        try {
            final VirtualShell shell = environment.shell();
            for (ShellFeature feature : ShellFeature.values()) {
                if (shell.supports(feature)) {
                    features.add(feature);
                }
            }
        } catch (RuntimeException e) {
            return Set.of();
        }
        return Set.copyOf(features);
    }

    private static boolean hasRuntimeFileSystem(ExecutionEnvironment environment) {
        try {
            return environment.runtimeFileSystem().isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The allow-list names {@code Bash} and every entry for it is bare. An empty list is "unrestricted", which is not
     * a decision to give the agent an arbitrary shell; a bare entry next to a patterned one is not unlimited either.
     */
    static boolean allowListLeavesBashUnconstrained(List<AllowedTool> allowedTools) {
        boolean named = false;
        for (AllowedTool entry : allowedTools) {
            if (BashTool.TOOL_NAME.equals(entry.getToolName())) {
                if (entry.hasPattern() || entry.getPathPattern().isPresent()) {
                    return false;
                }
                named = true;
            }
        }
        return named;
    }

    /**
     * No {@code PermissionRequest} or {@code PreTool} hook that may apply to {@code Bash}. A hook that does not say
     * which tools it acts on ({@link ToolScopedHook}) applies to all — the declarative {@code PermissionRequest} hook
     * has no matcher and fires for every tool.
     */
    static boolean noHookAppliesToBash(HookRegistry hooks) {
        return Stream
                .concat(hooks.getHooks(HookEventType.PERMISSION_REQUEST).stream(),
                        hooks.getHooks(HookEventType.PRE_TOOL).stream())
                .noneMatch(hook -> !(hook instanceof ToolScopedHook scoped) || scoped.mayApplyTo(BashTool.TOOL_NAME));
    }
}
