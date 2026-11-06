package at.aimon.core.agent.tool;

import java.util.Objects;
import java.util.Optional;

/**
 * Where one tool call came from: its {@linkplain ToolCallPath path}, and what that path knows about the call's parent.
 *
 * <p>
 * Every call handed to a {@link ToolCallDispatcher} carries one, and the dispatcher puts it on the PermissionRequest,
 * PreTool and PostTool hook contexts, so a hook can tell a call the model made directly from one a program or a
 * slash-command skill made. It is per call rather than per binding because one binding serves several paths — a fork's
 * dispatcher serves both its own loop and the programs that loop runs.
 *
 * <p>
 * Immutable. Created through the static factories, one per path, so a path cannot be paired with a parent that does
 * not belong to it.
 */
public final class ToolCallOrigin {

    private static final ToolCallOrigin DIRECT = new ToolCallOrigin(ToolCallPath.DIRECT, null, null, null);
    private static final ToolCallOrigin REPLAY = new ToolCallOrigin(ToolCallPath.REPLAY, null, null, null);

    private final ToolCallPath path;
    private final String parentToolUseId;
    private final String language;
    private final String skillName;

    private ToolCallOrigin(ToolCallPath path, String parentToolUseId, String language, String skillName) {
        this.path = path;
        this.parentToolUseId = parentToolUseId;
        this.language = language;
        this.skillName = skillName;
    }

    /**
     * A call the model asked for in an assistant message.
     *
     * @return the direct origin (never null)
     */
    public static ToolCallOrigin direct() {
        return DIRECT;
    }

    /**
     * A call a program made.
     *
     * @param parentToolUseId
     *            the id of the tool call that ran the program (must not be null)
     * @param language
     *            the language the program was written in (must not be null)
     * @return the origin (never null)
     * @throws NullPointerException
     *             if either argument is null
     */
    public static ToolCallOrigin code(String parentToolUseId, String language) {
        return new ToolCallOrigin(ToolCallPath.CODE,
                Objects.requireNonNull(parentToolUseId, "parentToolUseId cannot be null"),
                Objects.requireNonNull(language, "language cannot be null"), null);
    }

    /**
     * A call made by the ReAct loop of a skill the user invoked as a slash command.
     *
     * @param skillName
     *            the skill's name (must not be null)
     * @return the origin (never null)
     * @throws NullPointerException
     *             if {@code skillName} is null
     */
    public static ToolCallOrigin skill(String skillName) {
        return new ToolCallOrigin(ToolCallPath.SKILL, null, null,
                Objects.requireNonNull(skillName, "skillName cannot be null"));
    }

    /**
     * A hook re-fired by a rewake replay for a call recorded earlier.
     *
     * @return the replay origin (never null)
     */
    public static ToolCallOrigin replay() {
        return REPLAY;
    }

    /**
     * Returns the path the call arrived by.
     *
     * @return the path (never null)
     */
    public ToolCallPath getPath() {
        return path;
    }

    /**
     * Returns the id of the tool call that ran the program, for a {@link ToolCallPath#CODE} call.
     *
     * @return the parent tool-use id, or empty on any other path
     */
    public Optional<String> getParentToolUseId() {
        return Optional.ofNullable(parentToolUseId);
    }

    /**
     * Returns the language of the program, for a {@link ToolCallPath#CODE} call.
     *
     * @return the language, or empty on any other path
     */
    public Optional<String> getLanguage() {
        return Optional.ofNullable(language);
    }

    /**
     * Returns the name of the skill whose loop made the call, for a {@link ToolCallPath#SKILL} call.
     *
     * @return the skill name, or empty on any other path
     */
    public Optional<String> getSkillName() {
        return Optional.ofNullable(skillName);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ToolCallOrigin other)) {
            return false;
        }
        return path == other.path && Objects.equals(parentToolUseId, other.parentToolUseId)
                && Objects.equals(language, other.language) && Objects.equals(skillName, other.skillName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, parentToolUseId, language, skillName);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("ToolCallOrigin{path=").append(path);
        if (parentToolUseId != null) {
            sb.append(", parentToolUseId='").append(parentToolUseId).append('\'');
        }
        if (language != null) {
            sb.append(", language='").append(language).append('\'');
        }
        if (skillName != null) {
            sb.append(", skillName='").append(skillName).append('\'');
        }
        return sb.append('}').toString();
    }
}
