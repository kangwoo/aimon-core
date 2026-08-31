package at.aimon.workflow.graaljs;

import java.util.List;

import at.aimon.core.subagent.Subagent;

/**
 * Resolves a JS agent descriptor's identity fields ({@code agentType}/{@code systemPrompt}/{@code model}/
 * {@code tools}/{@code maxIterations}) into a core {@link Subagent}.
 *
 * <p>
 * {@code AgentTask} carries an <b>inline</b> subagent only (no named-registry lookup — a documented non-goal), and
 * {@code Subagent.builder} requires {@code name} + {@code systemPrompt}. Implementations therefore synthesize a
 * <b>deterministic, cross-JVM-stable</b> name so shared/persistent resume caches replay without spurious misses.
 */
public interface SubagentResolver {

    /**
     * Builds an inline {@link Subagent} from descriptor fields. Any argument except a usable identity source may be
     * {@code null}.
     *
     * @param agentType
     *            optional logical type; basis for the synthesized name and a default system prompt
     * @param systemPrompt
     *            optional explicit system prompt; when absent, one is synthesized from {@code agentType}
     * @param model
     *            optional model override
     * @param tools
     *            optional flat tool-name allow-list
     * @param maxIterations
     *            optional iteration cap (core default applies when {@code null})
     * @return an inline subagent (never {@code null})
     */
    Subagent resolve(String agentType, String systemPrompt, String model, List<String> tools, Integer maxIterations);

    /** The default inline resolver with deterministic SHA-256-derived names. */
    static SubagentResolver inline() {
        return new InlineSubagentResolver();
    }
}
