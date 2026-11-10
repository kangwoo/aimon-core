package at.aimon.core.toolinvocation;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import at.aimon.core.agent.tool.InlineSkillCeiling;
import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolCallDispatcher;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.agent.tool.permission.AllowedTools;
import at.aimon.core.tools.ToolContextKeys;

/**
 * How a loop applies its execution's {@link InlineSkillCeiling} at an iteration boundary — the one rule the main loop,
 * a fork and a slash skill's loop share ({@code docs/design/tool/programmatic-tool-calling.md} §7.2, register TC-26).
 *
 * <p>
 * Each loop makes one instance per execution, over the dispatcher it would otherwise use for every iteration. While
 * the ceiling is untouched every method answers with the loop's own values, so nothing is rebuilt. Once an inline
 * skill has narrowed it, an iteration is built from the {@link InlineSkillCeiling.Snapshot snapshot} read at its start:
 * <ul>
 * <li>the offer admits only what the loop's own filter and the snapshot's allow-list both admit
 * ({@link #admission});
 * <li>{@code ExecuteCode} describes itself from the snapshot's programmatic settings ({@link #programmatic});
 * <li>the environment facts read the snapshot's allow-list ({@link #factsAllowList}), so a skill that narrows
 * {@code Bash} to a pattern turns the shell engine off — otherwise a shell-engine program, an unrestricted
 * {@code python3} process, would walk past the skill's pattern;
 * <li>the calls run through the loop's dispatcher narrowed to the snapshot ({@link #dispatcherFor}), which publishes
 * the narrowed {@code CALLER_*} values to every call, so a fork or a background run started later inherits them; it is
 * swapped into the iteration's tool context by {@link #withDispatcher}, because the key is write-once.
 * </ul>
 *
 * <p>
 * Not thread-safe: an instance belongs to one loop, and only that loop's thread calls {@link #dispatcherFor}.
 */
public final class InlineSkillNarrowing {

    private final ToolCallDispatcher loopDispatcher;
    private final ToolRegistry registry;
    private int cachedVersion;
    private ToolCallDispatcher cachedDispatcher;

    /**
     * Creates the narrowing for one execution's loop.
     *
     * @param loopDispatcher
     *            the dispatcher the loop uses while the ceiling is untouched (must not be null)
     * @param registry
     *            the registry {@code loopDispatcher} was built with, so a narrowed dispatcher executes against exactly
     *            the loop's registries (must not be null)
     */
    public InlineSkillNarrowing(ToolCallDispatcher loopDispatcher, ToolRegistry registry) {
        this.loopDispatcher = Objects.requireNonNull(loopDispatcher, "loopDispatcher cannot be null");
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.cachedDispatcher = loopDispatcher;
    }

    /**
     * Returns the dispatcher for an iteration built from {@code snapshot}: the loop's own while the ceiling is
     * untouched, otherwise the loop's narrowed to the snapshot, built once per version.
     *
     * @param snapshot
     *            the ceiling read at the iteration's start (must not be null)
     * @return the dispatcher (never null)
     */
    public ToolCallDispatcher dispatcherFor(InlineSkillCeiling.Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        if (!snapshot.isNarrowed()) {
            return loopDispatcher;
        }
        if (snapshot.getVersion() != cachedVersion) {
            cachedDispatcher = loopDispatcher.narrow(registry, snapshot.getAllowedTools(), snapshot.getProgrammatic());
            cachedVersion = snapshot.getVersion();
        }
        return cachedDispatcher;
    }

    /**
     * Returns the loop's admission filter, further limited to the snapshot's allow-list once the ceiling is narrowed.
     *
     * @param loopAdmits
     *            the loop's own filter (must not be null)
     * @param snapshot
     *            the ceiling read at the iteration's start (must not be null)
     * @return the filter (never null)
     */
    public static Predicate<Tool> admission(Predicate<Tool> loopAdmits, InlineSkillCeiling.Snapshot snapshot) {
        Objects.requireNonNull(loopAdmits, "loopAdmits cannot be null");
        if (!snapshot.isNarrowed()) {
            return loopAdmits;
        }
        return loopAdmits.and(AllowedTools.admissionFilter(snapshot.getAllowedTools()));
    }

    /**
     * Returns the programmatic settings the offer describes {@code ExecuteCode} from.
     *
     * @param loopProgrammatic
     *            the loop's own settings (must not be null)
     * @param snapshot
     *            the ceiling read at the iteration's start (must not be null)
     * @return the snapshot's settings once narrowed, else {@code loopProgrammatic}
     */
    public static ProgrammaticToolSettings programmatic(ProgrammaticToolSettings loopProgrammatic,
            InlineSkillCeiling.Snapshot snapshot) {
        Objects.requireNonNull(loopProgrammatic, "loopProgrammatic cannot be null");
        return snapshot.isNarrowed() ? snapshot.getProgrammatic() : loopProgrammatic;
    }

    /**
     * Returns why the offer has no {@code ExecuteCode} although the execution opted in, for the iteration's span
     * ({@code ptc.unavailable_reason}): the snapshot's reason once an inline skill emptied the programmatic settings,
     * else {@code loopReason} (TC-30, the caller's ceiling of a fork).
     *
     * @param loopReason
     *            the loop's own reason; {@code null} when it has none
     * @param snapshot
     *            the ceiling read at the iteration's start (must not be null)
     * @return the reason, or {@code null} when there is none
     */
    public static String programmaticCeilingReason(String loopReason, InlineSkillCeiling.Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        return snapshot.getProgrammaticReason().orElse(loopReason);
    }

    /**
     * Returns the allow-list the environment facts are computed from. It alone decides whether {@code Bash} is
     * unconstrained, which is the shell engine's gate.
     *
     * @param loopAllowedTools
     *            the loop's own allow-list (must not be null)
     * @param snapshot
     *            the ceiling read at the iteration's start (must not be null)
     * @return the snapshot's allow-list once narrowed, else {@code loopAllowedTools}
     */
    public static List<AllowedTool> factsAllowList(List<AllowedTool> loopAllowedTools,
            InlineSkillCeiling.Snapshot snapshot) {
        Objects.requireNonNull(loopAllowedTools, "loopAllowedTools cannot be null");
        return snapshot.isNarrowed() ? snapshot.getAllowedTools() : loopAllowedTools;
    }

    /**
     * Returns {@code base} with its {@link ToolContextKeys#TOOL_CALL_DISPATCHER} replaced by {@code dispatcher}, or
     * {@code base} itself when it already carries that dispatcher. The key is write-once, so the copy is made without
     * it and the dispatcher put afresh.
     *
     * @param base
     *            the loop's base tool context (must not be null)
     * @param dispatcher
     *            the iteration's dispatcher (must not be null)
     * @return the context to build the iteration's tool context from (never null)
     */
    public static ToolContext withDispatcher(ToolContext base, ToolCallDispatcher dispatcher) {
        Objects.requireNonNull(base, "base cannot be null");
        Objects.requireNonNull(dispatcher, "dispatcher cannot be null");
        if (base.get(ToolContextKeys.TOOL_CALL_DISPATCHER).filter(bound -> bound == dispatcher).isPresent()) {
            return base;
        }
        final ToolContext.Builder builder = ToolContext.builder();
        base.getContext().forEach((name, value) -> {
            if (!ToolContextKeys.TOOL_CALL_DISPATCHER.name().equals(name)) {
                builder.put(name, value);
            }
        });
        return builder.put(ToolContextKeys.TOOL_CALL_DISPATCHER, dispatcher).build();
    }

    /**
     * Returns the ceiling's snapshot from {@code context}, or an untouched one starting at the given values when the
     * context carries no ceiling (a context built by hand).
     *
     * @param context
     *            the loop's base tool context (must not be null)
     * @param loopAllowedTools
     *            the loop's own allow-list (must not be null)
     * @param loopProgrammatic
     *            the loop's own programmatic settings (must not be null)
     * @return the snapshot (never null)
     */
    public static InlineSkillCeiling.Snapshot snapshotOf(ToolContext context, List<AllowedTool> loopAllowedTools,
            ProgrammaticToolSettings loopProgrammatic) {
        Objects.requireNonNull(context, "context cannot be null");
        return context.get(ToolContextKeys.INLINE_SKILL_CEILING)
                .orElseGet(() -> InlineSkillCeiling.startingAt(loopAllowedTools, loopProgrammatic)).snapshot();
    }
}
