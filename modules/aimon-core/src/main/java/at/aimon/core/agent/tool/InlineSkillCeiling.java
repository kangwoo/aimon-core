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

    private InlineSkillCeiling(Snapshot current) {
        this.current = current;
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
        Objects.requireNonNull(allowedTools, "allowedTools cannot be null");
        Objects.requireNonNull(programmatic, "programmatic cannot be null");
        return new InlineSkillCeiling(new Snapshot(List.copyOf(allowedTools), programmatic, List.of(), 0));
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
        current = new Snapshot(bounded.get(), programmatic, List.copyOf(skills), current.version + 1);
        return Optional.empty();
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

        private Snapshot(List<AllowedTool> allowedTools, ProgrammaticToolSettings programmatic, List<String> skills,
                int version) {
            this.allowedTools = allowedTools;
            this.programmatic = programmatic;
            this.skills = skills;
            this.version = version;
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
         * Returns whether an inline skill has narrowed the ceiling.
         *
         * @return {@code true} when {@link #getVersion()} is above zero
         */
        public boolean isNarrowed() {
            return version > 0;
        }
    }
}
