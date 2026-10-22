package at.aimon.core.agent.tool.permission;

import java.util.Optional;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;

/**
 * Capability for a tool whose call can do what another tool does, and must therefore also be allowed by that tool's
 * allowed-tools entries.
 *
 * <p>
 * Permissions are configured per tool name, so without this a list of {@code Bash(git:*)} and a bare {@code Monitor}
 * would let {@code Monitor} run any command the {@code Bash} entry was written to refuse. A tool implementing this
 * names the other tool and the subject to judge, and {@link DefaultToolPermissionValidator} applies, after the tool's
 * own entries have admitted the call, the same rules to the named tool's entries:
 *
 * <ul>
 * <li>no entry for the named tool → <b>deny</b> — the agent may not use that capability at all;
 * <li>only bare entries → permit;
 * <li>otherwise the subject must match one of their patterns.
 * </ul>
 *
 * <p>
 * An empty allowed-tools list restricts nothing, here as everywhere. Like {@link ToolPermissionSubjectAware}, this runs
 * before {@code execute()}: read the input defensively, never throw, and be stateless and thread-safe.
 */
public interface ToolPermissionDelegationAware {

    /**
     * Names the other tool this call must also be allowed by, if any.
     *
     * @param input
     *            the call's input (never null)
     * @param context
     *            the call's context (never null)
     * @return the delegation, or empty when this call does nothing another tool's entries should judge
     */
    Optional<PermissionDelegation> permissionDelegation(ToolInput input, ToolContext context);
}
