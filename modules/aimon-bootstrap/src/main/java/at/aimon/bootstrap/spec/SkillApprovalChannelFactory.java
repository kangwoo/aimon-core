package at.aimon.bootstrap.spec;

import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.approval.SkillApprovalChannel;
import at.aimon.core.skill.policy.session.SessionApprovalStore;

/**
 * Builds a {@link SkillApprovalChannel} over the approval stores the stack created for itself.
 *
 * <p>
 * This exists because of an asymmetry that {@link SkillApprovalSpec#channel(SkillApprovalChannel)} cannot
 * express. A channel is not only how an {@code ASK} is asked — it is also what <i>records the answer</i>, into
 * the very stores the policy chain consults on the next call. The stack owns those stores: it constructs them
 * inside its assembly so that the chain that reads and the channel that writes are demonstrably the same pair.
 * A caller who wants a custom channel therefore cannot construct it beforehand without also supplying the
 * stores — and a caller-supplied store is precisely the split this design refuses, because it fails silently:
 * every approval granted would land somewhere the check never looks, and the user would be asked the same
 * question forever.
 *
 * <p>
 * Inverting the call solves it. The caller hands over a factory instead of an instance, and the stack calls it
 * at the one moment when both stores exist and neither has been used yet.
 *
 * <p>
 * The returned channel is not owned by the stack — if it holds resources, the caller closes them. It is
 * invoked exactly once per {@code AimonStackBuilder.build(...)} call, before any turn runs, so an
 * implementation may capture the instance it returns.
 */
@FunctionalInterface
public interface SkillApprovalChannelFactory {

    /**
     * Creates the channel.
     *
     * @param sessionApprovalStore
     *            the session-scoped approval store the policy chain reads (never null)
     * @param agentApprovalStore
     *            the agent-scoped approval store the policy chain reads (never null)
     * @return the channel; must not be null
     */
    SkillApprovalChannel create(SessionApprovalStore sessionApprovalStore, AgentApprovalStore agentApprovalStore);
}
