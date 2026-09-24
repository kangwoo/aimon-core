package at.aimon.core.agent.compact;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUse;

/**
 * What a compaction gathers from the messages it summarizes, for the boundary marker and for {@code PostCompactHook}s:
 * the tool names seen, the files read, the skills invoked.
 *
 * <p>
 * Shared by the two ways {@link DefaultCompactionEngine} sees a summary installed —
 * {@link DefaultCompactionEngine#compact(CompactionRequest)}, which rewrites the transcript itself, and
 * {@link DefaultCompactionEngine#summaryInstalled}, which a context engine calls after recording the summary in the
 * view state — so the hooks see the same thing either way.
 *
 * <p>
 * Stateless.
 */
final class CompactionScans {

    /**
     * Tool name to scan for when collecting recently-read file paths. Hard-coded as a string to avoid the layering
     * violation that would occur if {@code at.aimon.core.agent.compact} imported {@code at.aimon.core.tools.file}.
     */
    private static final String READ_TOOL_NAME_FOR_FILE_TRACKING = "Read";

    /**
     * Tool name to scan for when collecting Skill invocations. Hard-coded for the same layering reason as
     * {@link #READ_TOOL_NAME_FOR_FILE_TRACKING} — {@code at.aimon.core.agent.compact} must not import
     * {@code at.aimon.core.tools.skill}.
     */
    private static final String SKILL_TOOL_NAME_FOR_INVOCATION_TRACKING = "Skill";

    /** Input keys on the {@code Skill} tool — duplicated as constants to avoid the layering import. */
    private static final String SKILL_TOOL_INPUT_KEY_NAME = "skill";
    private static final String SKILL_TOOL_INPUT_KEY_ARGS = "args";

    private CompactionScans() {
    }

    /**
     * Returns the distinct tool names used in {@code messages}, in first-seen order.
     *
     * @param messages
     *            the messages to scan (must not be null)
     * @return the tool names (never null)
     */
    static List<String> discoveredToolNames(List<Message> messages) {
        final Set<String> names = new LinkedHashSet<>();
        for (Message message : messages) {
            if (message.hasToolUses()) {
                for (ToolUse toolUse : message.getToolUses()) {
                    names.add(toolUse.getName());
                }
            }
        }
        return List.copyOf(names);
    }

    /**
     * Scans the conversation for {@code Read} tool invocations and returns the {@code file_path} arguments in
     * insertion order with duplicates collapsed to their most recent position. Taken from what is summarized, before
     * the summary replaces it, so {@code PostCompactHook}s can use it to re-attach files lost in the L3 summary.
     */
    static List<String> recentReadFilePaths(List<Message> messages) {
        final LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (Message message : messages) {
            if (!message.hasToolUses()) {
                continue;
            }
            for (ToolUse toolUse : message.getToolUses()) {
                if (!READ_TOOL_NAME_FOR_FILE_TRACKING.equals(toolUse.getName())) {
                    continue;
                }
                final Object filePathArg = toolUse.getInput().get("file_path");
                if (filePathArg instanceof String filePath && !filePath.isBlank()) {
                    paths.remove(filePath);
                    paths.add(filePath);
                }
            }
        }
        return List.copyOf(paths);
    }

    /**
     * Scans the conversation for {@code Skill} tool invocations and returns one {@link InvokedSkillRecord} per
     * (skill-name, args) pair in occurrence order, with duplicates collapsed to their most recent position. Mirrors
     * {@link #recentReadFilePaths(List)} so {@code PostCompactHook}s can use the snapshot to remind the agent
     * which skills it had activated before the L3 summary collapsed the {@code tool_use}/{@code tool_result} pairs.
     *
     * <p>
     * Invocations missing or having a blank {@code skill} input are skipped. {@code args} is normalised: missing or
     * non-string values become the empty string so equality and presentation are predictable.
     */
    static List<InvokedSkillRecord> invokedSkills(List<Message> messages) {
        final LinkedHashSet<InvokedSkillRecord> records = new LinkedHashSet<>();
        for (Message message : messages) {
            if (!message.hasToolUses()) {
                continue;
            }
            for (ToolUse toolUse : message.getToolUses()) {
                if (!SKILL_TOOL_NAME_FOR_INVOCATION_TRACKING.equals(toolUse.getName())) {
                    continue;
                }
                final Object nameArg = toolUse.getInput().get(SKILL_TOOL_INPUT_KEY_NAME);
                if (!(nameArg instanceof String name) || name.isBlank()) {
                    continue;
                }
                final Object argsArg = toolUse.getInput().get(SKILL_TOOL_INPUT_KEY_ARGS);
                final String args = (argsArg instanceof String s) ? s : "";
                final InvokedSkillRecord record = InvokedSkillRecord.of(name, args);
                records.remove(record);
                records.add(record);
            }
        }
        return List.copyOf(records);
    }
}
