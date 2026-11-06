package at.aimon.core.tools.code;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.ProgrammaticEngineChoice;
import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.ShellEngineToolScope;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolDescriptor;
import at.aimon.core.agent.tool.ToolOfferContext;
import at.aimon.core.environment.EnvironmentIsolation;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.EngineAvailability;
import at.aimon.core.programmatic.OfferFacts;

/**
 * What {@code ExecuteCode} can do in one offer context: the engine it would run code in and the tools that code could
 * call, or why it cannot be offered ({@code docs/design/tool/programmatic-tool-calling.md} §6.2, §7.3).
 *
 * <p>
 * {@code describe} computes it to grade and describe the tool, and {@code execute} computes it again from the same
 * offer context when a program starts — that second computation is the freeze (decision 13): the program gets the set
 * of that moment, and nothing it does can widen it.
 *
 * <p>
 * Immutable.
 */
final class ExecuteCodePlan {

    private static final Logger log = LoggerFactory.getLogger(ExecuteCodePlan.class);

    private final CodeExecutionEngine engine;
    private final List<ToolDescriptor> callable;
    private final Set<String> directlyOffered;
    private final String unavailableReason;

    private ExecuteCodePlan(CodeExecutionEngine engine, List<ToolDescriptor> callable, Set<String> directlyOffered,
            String unavailableReason) {
        this.engine = engine;
        this.callable = callable;
        this.directlyOffered = directlyOffered;
        this.unavailableReason = unavailableReason;
    }

    /**
     * Computes the plan.
     *
     * @param offer
     *            the offer context (must not be null)
     * @param engines
     *            the assembled engines (must not be null)
     * @param selfName
     *            {@code ExecuteCode}'s own name, left out of its callable set
     * @param toolScope
     *            the application's {@code shell-engine-tool-scope}, for an engine bound by it (must not be null)
     * @param warned
     *            the (agent, reason) and (agent, tool, reason) keys already warned about, owned by the provider or the
     *            tool (must not be null)
     */
    static ExecuteCodePlan of(ToolOfferContext offer, List<CodeExecutionEngine> engines, String selfName,
            ShellEngineToolScope toolScope, WarnOnce warned) {
        Objects.requireNonNull(offer, "offer cannot be null");
        Objects.requireNonNull(toolScope, "toolScope cannot be null");
        Objects.requireNonNull(warned, "warned cannot be null");
        final ExecuteCodePlan plan = compute(offer, engines, selfName, toolScope, warned);
        // An agent that opens tools to code and does not get ExecuteCode is told why, once per agent and reason
        // (§12.2, §16) — the span attribute ptc.unavailable_reason is not implemented (design §21).
        if (!plan.isAvailable() && offer.isOffering() && offer.getProgrammatic().isEnabled()) {
            final String agent = offer.getAgentName().orElse("?");
            if (warned.first(agent + '\0' + plan.unavailableReason)) {
                log.warn("Agent '{}' sets programmatic-tools, but ExecuteCode is not offered: {}", agent,
                        plan.unavailableReason);
            }
        }
        return plan;
    }

    private static ExecuteCodePlan compute(ToolOfferContext offer, List<CodeExecutionEngine> engines, String selfName,
            ShellEngineToolScope applicationScope, WarnOnce warned) {
        if (!offer.isOffering()) {
            return unavailable("there is no offer to describe it in");
        }
        final ProgrammaticToolSettings settings = offer.getProgrammatic();
        if (!settings.isEnabled()) {
            return unavailable("the agent opens no tool to code (programmatic-tools is not set)");
        }

        final OfferFacts facts = CodeOfferKeys.factsOf(offer);
        final String language;
        if (settings.getEngine() == ProgrammaticEngineChoice.JAVASCRIPT) {
            language = CodeExecutionEngine.JAVASCRIPT;
        } else if (settings.getEngine() == ProgrammaticEngineChoice.PYTHON) {
            language = CodeExecutionEngine.PYTHON;
        } else {
            final Optional<EnvironmentIsolation> isolation = facts.getIsolation();
            if (isolation.isEmpty()) {
                return unavailable("programmatic-engine is auto and the execution environment does not declare "
                        + "whether it is host or sandboxed; set programmatic-engine explicitly");
            }
            language = isolation.get() == EnvironmentIsolation.HOST
                    ? CodeExecutionEngine.JAVASCRIPT
                    : CodeExecutionEngine.PYTHON;
        }
        final Optional<CodeExecutionEngine> engine = engines.stream().filter(e -> language.equals(e.language()))
                .findFirst();
        if (engine.isEmpty()) {
            return unavailable("no " + language + " engine is assembled");
        }
        final EngineAvailability availability = engine.get().staticAvailability(facts);
        if (!availability.isAvailable()) {
            return unavailable(
                    "the " + language + " engine cannot run here: " + availability.getReason().orElse("unavailable"));
        }

        final List<String> names = new ArrayList<>(settings.getTools());
        names.remove(selfName);
        final ShellEngineToolScope scope = engine.get().isToolScopeBound()
                ? effectiveScope(offer, settings, applicationScope, warned)
                : null;
        final List<ToolDescriptor> eligible = offer.eligible(names);
        final List<ToolDescriptor> callable = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        for (ToolDescriptor descriptor : eligible) {
            seen.add(descriptor.getName());
            if (!descriptor.isCodeCallable()) {
                warnOnce(warned, offer, descriptor.getName(), "it declares that it cannot be called from code");
            } else if (descriptor.getSideEffectLevel() == SideEffectLevel.MUTATING
                    && descriptor.getEffectiveDestructiveBehavior() == DestructiveBehavior.DESTRUCTIVE) {
                warnOnce(warned, offer, descriptor.getName(), "it is MUTATING and DESTRUCTIVE (code-path rule 1)");
            } else if (scope != null && !scope.admits(descriptor.getDataReach())) {
                // Step 5 (§7.3, shell doc §12.2): a co-resident process could call it while the script runs.
                warnOnce(warned, offer, descriptor.getName(), "its data reach " + descriptor.getDataReach()
                        + " is outside shell-engine-tool-scope " + scope.wireName());
            } else {
                callable.add(descriptor);
            }
        }
        for (String name : names) {
            if (!seen.contains(name)) {
                warnOnce(warned, offer, name,
                        "it is not offered here (unknown, outside the allow-list, or above the ceiling)");
            }
        }
        if (callable.isEmpty()) {
            return unavailable("none of the tools in programmatic-tools can be called from code here");
        }
        callable.sort(Comparator.comparing(ToolDescriptor::getName));
        final Set<String> direct = new LinkedHashSet<>();
        for (ToolDescriptor descriptor : callable) {
            // Not isCodeOnly: that asks which offered tool hosts the tool, which is this plan's own answer.
            if (!offer.isNamedCodeOnly(descriptor)) {
                direct.add(descriptor.getName());
            }
        }
        return new ExecuteCodePlan(engine.get(), List.copyOf(callable), Set.copyOf(direct), null);
    }

    /**
     * The application's scope narrowed by the agent's: an agent can only narrow it, and a wider agent value is ignored
     * with a warning once per agent (shell doc §12.2).
     */
    private static ShellEngineToolScope effectiveScope(ToolOfferContext offer, ProgrammaticToolSettings settings,
            ShellEngineToolScope applicationScope, WarnOnce warned) {
        final Optional<ShellEngineToolScope> agentScope = settings.getShellEngineToolScope();
        if (agentScope.isEmpty()) {
            return applicationScope;
        }
        if (agentScope.get().min(applicationScope) != agentScope.get()) {
            final String agent = offer.getAgentName().orElse("?");
            if (warned.first(agent + "\0scope-widening")) {
                log.warn(
                        "Agent '{}' sets shell-engine-tool-scope {}, wider than the application's {}; the "
                                + "application's applies",
                        agent, agentScope.get().wireName(), applicationScope.wireName());
            }
            return applicationScope;
        }
        return agentScope.get();
    }

    private static ExecuteCodePlan unavailable(String reason) {
        return new ExecuteCodePlan(null, List.of(), Set.of(), reason);
    }

    private static void warnOnce(WarnOnce warned, ToolOfferContext offer, String toolName, String reason) {
        final String agent = offer.getAgentName().orElse("?");
        if (warned.first(agent + '\0' + toolName + ": " + reason)) {
            log.warn("Agent '{}': tool '{}' is left out of ExecuteCode's callable set: {}", agent, toolName, reason);
        }
    }

    /** Whether {@code ExecuteCode} can be offered. */
    boolean isAvailable() {
        return unavailableReason == null;
    }

    /** Why it cannot, when it cannot. */
    Optional<String> unavailableReason() {
        return Optional.ofNullable(unavailableReason);
    }

    /** The engine (present when available). */
    Optional<CodeExecutionEngine> engine() {
        return Optional.ofNullable(engine);
    }

    /** The callable set, sorted by name. */
    List<ToolDescriptor> callable() {
        return callable;
    }

    /** The names of callable tools that are also in the direct offer. */
    Set<String> directlyOffered() {
        return directlyOffered;
    }
}
