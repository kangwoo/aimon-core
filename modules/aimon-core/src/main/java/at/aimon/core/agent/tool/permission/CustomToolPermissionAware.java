package at.aimon.core.agent.tool.permission;

import at.aimon.core.agent.tool.Tool;

/**
 * Implemented by a tool whose permission decision needs its own grammar.
 *
 * <p>
 * This is the escape hatch, not the usual answer. A tool that can name one value it is about to act on — a command, a
 * path — should implement {@link ToolPermissionSubjectAware} instead and let the framework match it. Reach for a rule
 * only when the decision genuinely cannot be reduced to one value of one
 * {@link PermissionSubject.Kind}.
 *
 * <p>
 * The one in-tree case is {@code Browser}: it is judged on an action and a URL together, spelled {@code open:*} in a
 * spec, where the {@code :} separates two fields rather than marking a wildcard. Promoting that to a third
 * {@code Kind} would make one tool's notation part of the framework's vocabulary.
 *
 * <h2>Design Principles</h2>
 *
 * <ul>
 * <li><b>ISP:</b> stays off the {@link Tool} interface, so tools that need no custom rule carry no permission method
 * <li><b>SRP:</b> the rule holds permission logic; the tool holds execution logic
 * <li><b>OCP:</b> a new grammar arrives as a new rule, not as an edit to the validator
 * </ul>
 *
 * <h2>Where this sits in the decision</h2>
 *
 * <p>
 * {@link DefaultToolPermissionValidator} consults a rule only after the cheaper answers are exhausted — the allowed
 * list is non-empty, the tool's name is listed, at least one entry for it carries a pattern, and the tool offered no
 * {@link PermissionSubject}. {@code "Browser"} on its own never reaches the rule; it was already allowed. But
 * {@code "Browser"} listed <i>alongside</i> {@code "Browser(open:*)"} does, and the unqualified entry arrives in the
 * rule's list without granting anything — which is why a rule filters on {@link AllowedTool#hasPattern()} before
 * matching, as {@code BrowserToolPermissionRule} does.
 *
 * <p>
 * A tool may implement both interfaces. The subject is tried first, and the rule decides only when the subject is
 * empty. If neither can judge the call, it is denied.
 *
 * <h2>Usage Example</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     public class BrowserTool extends GenericTool&lt;BrowserInput, String&gt; implements CustomToolPermissionAware {
 *
 *         private final CustomToolPermissionRule permissionRule = new BrowserToolPermissionRule();
 *
 *         &#64;Override
 *         public CustomToolPermissionRule getCustomPermissionRule() {
 *             return permissionRule;
 *         }
 *
 *         &#64;Override
 *         protected String doExecute(BrowserInput input, ToolContext context) {
 *             // drive the browser
 *         }
 *     }
 * }
 * </pre>
 *
 * @see ToolPermissionSubjectAware
 * @see CustomToolPermissionRule
 * @see DefaultToolPermissionValidator
 * @see Tool
 */
public interface CustomToolPermissionAware {
    /**
     * Returns the custom permission rule for this tool.
     *
     * <p>
     * The rule is consulted during permission checking to decide whether this call's parameters match the configured
     * patterns. It is called before the tool runs, possibly on a different thread than a later execution, and possibly
     * for several calls at once.
     *
     * <p>
     * <b>Implementation guidelines:</b>
     *
     * <ul>
     * <li>Return a stateless, thread-safe instance — hold it in a final field rather than allocating per call
     * <li>Read parameters defensively: the rule runs ahead of the tool, so an argument of the wrong type must become a
     * denial, not an exception (see {@link CustomToolPermissionRule})
     * <li>Never return null
     * </ul>
     *
     * @return the permission rule for this tool (never null)
     */
    CustomToolPermissionRule getCustomPermissionRule();
}
