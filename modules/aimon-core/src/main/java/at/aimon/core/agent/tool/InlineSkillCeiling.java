package at.aimon.core.agent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.agent.tool.permission.AllowedTools;

/**
 * The tool ceiling of one execution — a turn, a fork or a slash skill — as the model-invoked inline skills it activated
 * have narrowed it ({@code docs/design/tool/programmatic-tool-calling.md} §7.2, register TC-26).
 *
 * <p>
 * An inline skill adds its instructions to the conversation and runs no loop of its own, so its {@code allowed-tools}
 * and programmatic settings cannot bound a loop the way a slash skill's do. Instead {@code SkillTool} narrows this
 * ceiling when it activates one, and the loop that bound it reads a {@link #snapshot()} at its next iteration
 * boundary: the offer, the environment facts and the dispatcher of that iteration are then built from the snapshot,
 * so what the model is offered, what its calls are held to and what a fork it spawns inherits all follow the skill.
 * Calls in the same response as the {@code Skill} call were chosen before the model read the skill, and run under the
 * ceiling that response was offered.
 *
 * <p>
 * The ceiling only narrows. The allow-list becomes the intersection of the current one and the skill's
 * ({@link AllowedTools#intersect}); the programmatic settings become the skill's narrowed by the current ones
 * ({@link ProgrammaticToolSettings#narrowedBy}), so a skill without programmatic keys leaves {@code none()} — the same
 * as on the slash path (B-DV1). A skill whose allow-list shares no tool with the current one is refused, and the
 * ceiling is left as it was. Several inline skills in one execution narrow it cumulatively.
 *
 * <p>
 * Execution-scoped and never persisted, like {@link ToolCallQuota}: the next turn starts from the agent's values. Bound
 * once per execution under {@code ToolContextKeys.INLINE_SKILL_CEILING}. Thread-safe: a {@code Skill} call may run on
 * a dispatcher thread while the loop reads snapshots.
 */
public final class InlineSkillCeiling {

    private Snapshot current;
    private final boolean approvalInNewTurn;

    private InlineSkillCeiling(Snapshot current, boolean approvalInNewTurn) {
        this.current = current;
        this.approvalInNewTurn = approvalInNewTurn;
    }

    /**
     * Returns a ceiling that starts at the loop's own values and has not been narrowed ({@code version == 0}).
     *
     * @param allowedTools
     *            the loop's effective allow-list; empty means unrestricted (must not be null)
     * @param programmatic
     *            the loop's effective programmatic settings (must not be null)
     * @return a new ceiling (never null)
     */
    public static InlineSkillCeiling startingAt(List<AllowedTool> allowedTools, ProgrammaticToolSettings programmatic) {
        return startingAt(allowedTools, programmatic, false);
    }

    /**
     * Returns a ceiling that starts at the loop's own values and has not been narrowed, saying whether a skill call
     * that needs approval could be approved in a later turn of the same session.
     *
     * <p>
     * Only a main-loop turn with an approval channel (the SK-11.4 pre-flight scanner) can say yes: once an inline skill
     * has narrowed that turn it no longer suspends for approval, but the user can approve the skill and the model can
     * call it again in the next turn. A fork, a slash skill's loop or a headless turn has no approval channel in any
     * turn, so telling its model to ask again would send it after an approval that cannot come.
     *
     * <p>
     * "Main-loop turn with an approval channel" means a turn of an executor that has a pre-flight scanner: the flag is
     * {@code skillPreflightScanner != null}. An executor with a scanner that also runs scheduled or rewake executions
     * would say yes for those too — the same line the pre-flight path draws for "headless".
     *
     * @param allowedTools
     *            the loop's effective allow-list; empty means unrestricted (must not be null)
     * @param programmatic
     *            the loop's effective programmatic settings (must not be null)
     * @param approvalInNewTurn
     *            whether an approval this execution cannot ask for can be asked for in a new turn
     * @return a new ceiling (never null)
     */
    public static InlineSkillCeiling startingAt(List<AllowedTool> allowedTools, ProgrammaticToolSettings programmatic,
            boolean approvalInNewTurn) {
        Objects.requireNonNull(allowedTools, "allowedTools cannot be null");
        Objects.requireNonNull(programmatic, "programmatic cannot be null");
        return new InlineSkillCeiling(new Snapshot(List.copyOf(allowedTools), programmatic, List.of(), 0, null),
                approvalInNewTurn);
    }

    /**
     * Returns whether a skill call this execution cannot ask approval for could be approved in a new turn — set by the
     * loop that bound the ceiling ({@link #startingAt(List, ProgrammaticToolSettings, boolean)}).
     *
     * @return {@code true} only for a main-loop turn with an approval channel
     */
    public boolean isApprovalInNewTurn() {
        return approvalInNewTurn;
    }

    /**
     * Narrows the ceiling by an inline skill's declared values.
     *
     * <p>
     * When the narrowed values equal the current ones — a skill with no allow-list whose programmatic settings change
     * nothing — the version is not bumped, so the loops rebuild nothing.
     *
     * @param skill
     *            the activated skill's name, named in a refusal and recorded in the snapshot (must not be null)
     * @param declared
     *            the skill's {@code allowed-tools}; empty means it restricts nothing (must not be null)
     * @param declaredProgrammatic
     *            the skill's programmatic settings, {@link ProgrammaticToolSettings#none()} when it has none (must not
     *            be null)
     * @return the refusal message when the skill's allow-list and the current one share no tool, or empty when the
     *         ceiling was narrowed (or left as it was because nothing changed)
     */
    public synchronized Optional<String> narrow(String skill, List<AllowedTool> declared,
            ProgrammaticToolSettings declaredProgrammatic) {
        Objects.requireNonNull(skill, "skill cannot be null");
        Objects.requireNonNull(declared, "declared cannot be null");
        Objects.requireNonNull(declaredProgrammatic, "declaredProgrammatic cannot be null");
        final Optional<List<AllowedTool>> bounded = AllowedTools.intersect(current.allowedTools, declared);
        if (bounded.isEmpty()) {
            return Optional.of(
                    String.format("Skill '%s' cannot run: its allowed-tools and the caller's have nothing in common "
                            + "(caller allows %s, skill allows %s)", skill, current.allowedTools, declared));
        }
        final ProgrammaticToolSettings programmatic = declaredProgrammatic.narrowedBy(current.programmatic);
        if (bounded.get().equals(current.allowedTools) && programmatic.equals(current.programmatic)) {
            return Optional.empty();
        }
        final List<String> skills = new ArrayList<>(current.skills);
        skills.add(skill);
        final String reason = current.programmatic.isEnabled() && !programmatic.isEnabled()
                ? emptiedReason(skill, declaredProgrammatic, current.programmatic)
                : current.programmaticReason;
        current = new Snapshot(bounded.get(), programmatic, List.copyOf(skills), current.version + 1, reason);
        return Optional.empty();
    }

    /**
     * Says why {@code skill}'s settings leave the execution no {@code ExecuteCode} although it had some — worded like
     * the caller-ceiling reason of a fork (TC-30), with the skill in the caller's place.
     */
    private static String emptiedReason(String skill, ProgrammaticToolSettings declared,
            ProgrammaticToolSettings before) {
        if (!declared.isEnabled()) {
            return "inline skill '" + skill + "' sets no programmatic-tools, and an activated skill's programmatic "
                    + "settings narrow the execution's, so no tool is open to code";
        }
        if (declared.hasEngineConflict(before) && declared.getTools().stream().anyMatch(before.getTools()::contains)) {
            return "inline skill '" + skill + "' sets programmatic-engine " + declared.getEngine().wireName()
                    + ", but the execution uses " + before.getEngine().wireName();
        }
        // As in the manager's caller-ceiling reason: the engine conflict is narrowedBy's only other way to give none().
        return "inline skill '" + skill + "' sets programmatic-tools " + declared.getTools()
                + ", but the execution allows none of them (it allows " + before.getTools() + ")";
    }

    /**
     * Returns the ceiling as it stands now.
     *
     * @return an immutable view (never null)
     */
    public synchronized Snapshot snapshot() {
        return current;
    }

    /** An immutable view of an {@link InlineSkillCeiling} at one moment. */
    public static final class Snapshot {
        private final List<AllowedTool> allowedTools;
        private final ProgrammaticToolSettings programmatic;
        private final List<String> skills;
        private final int version;
        private final String programmaticReason;

        private Snapshot(List<AllowedTool> allowedTools, ProgrammaticToolSettings programmatic, List<String> skills,
                int version, String programmaticReason) {
            this.allowedTools = allowedTools;
            this.programmatic = programmatic;
            this.skills = skills;
            this.version = version;
            this.programmaticReason = programmaticReason;
        }

        /**
         * Returns the effective allow-list; empty means unrestricted.
         *
         * @return the allow-list (never null)
         */
        public List<AllowedTool> getAllowedTools() {
            return allowedTools;
        }

        /**
         * Returns the effective programmatic settings.
         *
         * @return the settings (never null)
         */
        public ProgrammaticToolSettings getProgrammatic() {
            return programmatic;
        }

        /**
         * Returns the inline skills that narrowed the ceiling, in activation order.
         *
         * @return the skill names (never null; empty when untouched)
         */
        public List<String> getSkills() {
            return skills;
        }

        /**
         * Returns how many times the ceiling has been narrowed; {@code 0} means untouched.
         *
         * @return the version
         */
        public int getVersion() {
            return version;
        }

        /**
         * Returns why an inline skill left this execution no {@code ExecuteCode}: present once a skill's settings
         * turned enabled programmatic settings into {@link ProgrammaticToolSettings#none()}, and kept by later skills
         * (nothing narrows {@code none()} back). What the iteration spans carry as {@code ptc.unavailable_reason}.
         *
         * @return the reason, or empty when the execution started without programmatic settings or still has some
         */
        public Optional<String> getProgrammaticReason() {
            return Optional.ofNullable(programmaticReason);
        }

        /**
         * Returns whether an inline skill has narrowed the ceiling.
         *
         * @return {@code true} when {@link #getVersion()} is above zero
         */
        public boolean isNarrowed() {
            return version > 0;
        }
    }
}
