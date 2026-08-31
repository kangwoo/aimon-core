package at.aimon.spring.boot.autoconfigure;

/**
 * How the stack answers "may this skill run?" when no prior approval covers it, selected by
 * {@code aimon.skill.approval.mode}.
 *
 * <p>
 * <b>Why the unsupported values are listed here anyway.</b> Only {@link #DENY} is honoured by this version. The
 * obvious alternative — declare the one value that works and leave the rest out — is worse, because Spring Boot
 * ignores properties nobody declared. A service that sets {@code mode: allow-list} would then start fail-closed
 * while its configuration says otherwise, and the symptom is a skill that never runs rather than an error
 * anyone can search for. Declaring the full set and rejecting what is not wired yet turns that into a startup
 * failure naming the property, which is the whole point of making these selectors enums.
 */
public enum ApprovalMode {

    /**
     * Every unapproved skill invocation is denied. Fail-closed, and the right default for a service: an
     * {@code ASK} that nothing can answer is a denial with extra steps, so this states the outcome instead of
     * arriving at it by accident.
     */
    DENY,

    /** Skills named in an allow list are approved automatically; everything else is denied. */
    ALLOW_LIST,

    /**
     * The turn is suspended and waits in the pending-turn registry for an out-of-band approval. Needs a way for
     * that approval to arrive, and a TTL for when it does not.
     */
    SUSPEND,

    /** The application supplies its own {@code SkillApprovalChannel} bean and answers the question itself. */
    CHANNEL
}
