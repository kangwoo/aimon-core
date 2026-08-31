package at.aimon.core.agent.tool.permission;

import java.util.Optional;

import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;

/**
 * Capability for tools that can name the one value their permission patterns should be matched against.
 *
 * <p>
 * A tool implementing this interface answers a single question: <i>given this call, what should
 * {@code Read(/tmp/**)} be compared with?</i> The framework does the rest — picking the matcher from the
 * {@link PermissionSubject.Kind} and comparing it against every pattern configured for the tool's name. A tool that
 * implements this needs no {@link CustomToolPermissionRule} at all.
 *
 * <h2>Why a separate interface and not a {@code Tool} method</h2>
 *
 * <p>
 * {@link Tool} has four members today. A fifth would make all of its implementations carry a permission concept that
 * only a handful of them use. {@link CustomToolPermissionAware} already set the precedent in this package — permission
 * concerns are a capability interface — and this follows it.
 *
 * <h2>Relationship to {@link CustomToolPermissionAware}</h2>
 *
 * <p>
 * The two are not alternatives of equal weight. This interface covers the common shape (one input field, one pattern
 * list); the custom rule stays for judgements that a single value cannot express — a combination of several inputs, or
 * a lookup against an external policy. When a tool implements both, the subject is consulted first.
 *
 * <h2>Returning empty is a decision, not an abstention</h2>
 *
 * <p>
 * An empty result means <b>this call cannot be judged</b>: the input field is missing, or a relative path arrived with
 * no {@code Environment} in the context to resolve it against. If a pattern is configured for this tool, the validator
 * denies the call. That is deliberate — the alternative is guessing (resolving against the process CWD, say), which
 * makes the outcome depend on where the JVM happened to start and leaves the person who wrote the pattern unable to
 * predict it.
 *
 * <p>
 * The one exception is a tool that also carries a {@link CustomToolPermissionAware} rule: there, an empty subject means
 * the rule decides. Denial is what happens when nothing at all can judge the call, not what an empty subject means on
 * its own.
 *
 * <p>
 * Implementations must be stateless and thread-safe; the method may be called concurrently for a batch of tool calls.
 *
 * <p>
 * Example:
 *
 * <pre>
 * {
 *     &#64;code
 *     public class BashTool extends AbstractTool implements ToolPermissionSubjectAware {
 *         &#64;Override
 *         public Optional&lt;PermissionSubject&gt; permissionSubject(ToolInput input, ToolContext context) {
 *             // Read raw. This runs before execute(), so getStringOrNull's IllegalArgumentException would have
 *             // nowhere to land; a command that is not a string cannot be judged, which is the empty case.
 *             if (!(input.get("command") instanceof String command) || command.isBlank()) {
 *                 return Optional.empty();
 *             }
 *             return Optional.of(PermissionSubject.command(command));
 *         }
 *     }
 * }
 * </pre>
 *
 * @see PermissionSubject
 * @see CustomToolPermissionAware
 */
public interface ToolPermissionSubjectAware {

    /**
     * Returns the value this call should be judged on.
     *
     * @param input
     *            The tool call's arguments (must not be null)
     * @param context
     *            The runtime context — carries the {@code Environment} a path subject needs (must not be null)
     * @return The subject, or empty if this call cannot be judged (see the class javadoc: empty means deny when a
     *         pattern is configured)
     */
    Optional<PermissionSubject> permissionSubject(ToolInput input, ToolContext context);
}
