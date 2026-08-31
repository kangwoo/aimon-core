package at.aimon.core.memory;

import java.util.Objects;

import at.aimon.core.base.Principal;

/**
 * Default {@link WorkspaceAccessPolicy}.
 *
 * <p>
 * Rules, in order:
 * <ol>
 * <li>Infrastructure principals ({@link Principal.Type#SYSTEM},
 * {@link Principal.Type#SERVICE}) may access every workspace — schedulers,
 * relays and maintenance jobs need to enumerate all tenants.</li>
 * <li>A workspace with no ACL metadata is treated as <em>unowned / public</em>
 * and is visible to everyone (backward compatible with single-tenant setups
 * that never set ownership).</li>
 * <li>Otherwise a {@code USER}/{@code GROUP} requester sees the workspace only
 * if it is the recorded {@value #META_OWNER}, or appears in the comma-separated
 * {@value #META_MEMBERS} list. Principal identity is keyed as
 * {@code TYPE:id}.</li>
 * </ol>
 *
 * <p>
 * To restrict a workspace, set its metadata, e.g.
 * {@code metadata.put("acl.owner", "USER:alice")} and/or
 * {@code metadata.put("acl.members", "USER:bob,GROUP:ops")}.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class DefaultWorkspaceAccessPolicy implements WorkspaceAccessPolicy {

    /** Metadata key holding the owning principal key ({@code TYPE:id}). */
    public static final String META_OWNER = "acl.owner";
    /** Metadata key holding a comma-separated list of member principal keys ({@code TYPE:id}). */
    public static final String META_MEMBERS = "acl.members";

    @Override
    public boolean canAccess(Principal requester, Workspace workspace) {
        Objects.requireNonNull(requester, "requester cannot be null");
        Objects.requireNonNull(workspace, "workspace cannot be null");

        if (requester.getType() == Principal.Type.SYSTEM || requester.getType() == Principal.Type.SERVICE) {
            return true;
        }

        String owner = workspace.getMetadata().get(META_OWNER);
        String members = workspace.getMetadata().get(META_MEMBERS);
        boolean unowned = isBlank(owner) && isBlank(members);
        if (unowned) {
            return true;
        }

        String requesterKey = requester.getType().name() + ":" + requester.getId();
        if (requesterKey.equals(owner)) {
            return true;
        }
        if (!isBlank(members)) {
            for (String member : members.split(",")) {
                if (requesterKey.equals(member.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
