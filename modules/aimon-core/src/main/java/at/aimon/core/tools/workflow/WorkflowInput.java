package at.aimon.core.tools.workflow;

import at.aimon.core.agent.tool.generic.GenericTool;
import at.aimon.core.agent.tool.generic.ToolParam;

/**
 * The parameters of a {@link WorkflowTool} call, and the source of its schema.
 *
 * <p>
 * A record rather than the project's usual builder class — the narrow exception granted to {@link GenericTool} input
 * types, whose reasoning is in {@code at.aimon.core.agent.tool.generic}'s package documentation.
 *
 * <p>
 * {@code synthesize} is a {@code Boolean} rather than a {@code boolean} because a primitive would be required, and this
 * parameter has a default. The other three optional parameters are strings for the same reason.
 *
 * <p>
 * The two closed sets are declared here rather than checked in the tool. They used to be declared in the schema
 * <em>and</em> re-checked by hand, which is exactly the duplication this record removes; enforcement now comes from the
 * same declaration the model is shown.
 *
 * @param prompt
 *            the question, task, or claim to work on
 * @param strategy
 *            which strategy to run, or null for {@code perspectives}
 * @param perspectives
 *            comma-separated angle labels, or null for the defaults
 * @param synthesize
 *            whether to fold the angles into a single answer, or null for true
 * @param mode
 *            whether to block for the answer or return a run id, or null for {@code foreground}
 */
public record WorkflowInput(

        @ToolParam(required = true, description = "The question, task, or claim to work on.") String prompt,

        @ToolParam(description = "How to workflow the sub-agents (default 'perspectives'): "
                + "'perspectives' fans the prompt out to several angle sub-agents and synthesizes them; "
                + "'judge_panel' generates several candidate answers, has a panel score each, and returns "
                + "the best synthesized; 'adversarial_verify' treats the prompt as a claim and has several "
                + "skeptics try to refute it, returning a survive/refute verdict.", allowed = {
                        WorkflowTool.STRATEGY_PERSPECTIVES, WorkflowTool.STRATEGY_JUDGE_PANEL,
                        WorkflowTool.STRATEGY_ADVERSARIAL}) String strategy,

        @ToolParam(description = "Optional comma-separated angle labels (e.g. 'technical,risk,cost'), used by the "
                + "'perspectives' and 'judge_panel' strategies. Defaults to "
                + "technical,risk,user_impact.") String perspectives,

        @ToolParam(description = "For the 'perspectives' strategy: whether to synthesize the angles into one answer "
                + "(default true). If false, returns the labeled per-perspective analyses.") Boolean synthesize,

        @ToolParam(description = "Run mode: 'foreground' (default) blocks and returns the answer; "
                + "'background' returns immediately with a run id "
                + "you can track via the /runs command.", allowed = {WorkflowTool.MODE_FOREGROUND,
                        WorkflowTool.MODE_BACKGROUND}) String mode){
}
