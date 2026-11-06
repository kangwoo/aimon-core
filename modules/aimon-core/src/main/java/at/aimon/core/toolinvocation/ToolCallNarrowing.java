package at.aimon.core.toolinvocation;

import java.util.List;
import java.util.Optional;

import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.agent.tool.permission.AllowedTools;

/**
 * The two checks behind {@code ToolCallDispatcher#narrow}, shared by the gated and the ungated dispatcher so the two
 * cannot disagree about what "can only do less" means.
 */
final class ToolCallNarrowing {

    private ToolCallNarrowing() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Refuses a registry holding a tool the original does not. A tool counts as held when the original resolves the
     * same name to an equal tool — a different implementation under a known name is a tool the original never had.
     *
     * @throws IllegalArgumentException
     *             naming the first tool the original does not hold
     */
    static void requireSubset(ToolRegistry original, ToolRegistry narrowed) {
        if (narrowed == original) {
            return;
        }
        for (Tool tool : narrowed.findAll()) {
            final String name = tool.getDefinition().getName();
            final Optional<Tool> held = original.findByName(name);
            if (held.isEmpty() || !held.get().equals(tool)) {
                throw new IllegalArgumentException("Cannot narrow a tool call dispatcher to a registry holding '" + name
                        + "', which the dispatcher's own registry does not hold");
            }
        }
    }

    /**
     * Intersects the two allow-lists ({@link AllowedTools#intersect}).
     *
     * @throws IllegalArgumentException
     *             when the two have nothing in common — an empty list would read as unrestricted
     */
    static List<AllowedTool> intersect(List<AllowedTool> original, List<AllowedTool> narrowed) {
        return AllowedTools.intersect(original, narrowed)
                .orElseThrow(() -> new IllegalArgumentException("Cannot narrow a tool call dispatcher allowing "
                        + original + " to " + narrowed + ": the two allow-lists have nothing in common"));
    }
}
