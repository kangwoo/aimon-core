package at.aimon.core.skill.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.SkillRegistryResolver;
import at.aimon.core.skill.policy.approval.SkillApprovalChannel;
import at.aimon.core.skill.policy.pending.PendingSkillRequest;
import at.aimon.core.tools.skill.SkillTool;

/**
 * Inspects the {@link ToolUse} blocks of a single LLM response and decides whether the agent loop must suspend the
 * current turn pending out-of-band approval (SK-11.4).
 *
 * <p>
 * Scope:
 * <ul>
 * <li>Only tool_uses whose name equals {@link SkillTool#TOOL_NAME} ({@code "Skill"}) are inspected. All other
 * tool_uses (Bash, Read, Grep, etc.) are ignored — their permission gating happens in their own tool implementations
 * and is not the concern of the skill invocation policy.</li>
 * <li>Skill tool_uses with malformed input (missing or non-string {@code skill} parameter) are skipped — the per-tool
 * execution path will surface a clear error to the LLM. The pre-flight scan is not the right place to fail-loud on
 * those.</li>
 * <li>Skill tool_uses referencing an unknown skill are also skipped: the registry lookup in {@code SkillTool} produces
 * the canonical "Skill not found" error, and we don't want to suspend the turn for something that will fail
 * anyway.</li>
 * </ul>
 *
 * <p>
 * Decision handling:
 * <ul>
 * <li>{@link SkillInvocationDecision#ALLOW} — proceed, no suspension contribution.</li>
 * <li>{@link SkillInvocationDecision#DENY} — proceed, no suspension contribution. The per-tool execution path returns
 * the policy-rejected error from {@code SkillTool} so the LLM observes the denial without the turn being
 * suspended.</li>
 * <li>{@link SkillInvocationDecision#ASK} — record a {@link PendingSkillRequest} and (after all tool_uses are
 * inspected) return {@link SkillPreflightScanResult#suspend}.</li>
 * </ul>
 *
 * <p>
 * <b>The registry is resolved per runtime, not held.</b> One scanner is shared by the whole agent executor while
 * a {@link SkillRegistry} is agent-scoped, so a scanner holding a single registry would resolve every agent's
 * skill names through the first agent's bundle. See {@link SkillRegistryResolver} for what that silently does to
 * the second agent. Single-agent callers keep their existing constructor; it wraps the registry in
 * {@link SkillRegistryResolver#fixed}.
 *
 * <p>
 * The scanner is stateless and thread-safe so long as the wrapped policy and resolver are.
 */
public final class SkillPreflightScanner {

    private static final Logger log = LoggerFactory.getLogger(SkillPreflightScanner.class);

    private final SkillInvocationPolicy policy;
    private final SkillRegistryResolver registries;
    /**
     * Optional inline approval back-channel (SK-11.6). When non-null and the scan would otherwise suspend, the channel
     * is consulted synchronously; the channel persists per-skill ALLOW/DENY decisions to whatever store the active
     * policy chain reads, and the scan returns {@link SkillPreflightScanResult#proceed()} so the agent loop continues
     * into normal tool execution where the persisted decisions are surfaced (DENY → policy-rejected error from
     * {@code SkillTool}; ALLOW → normal invocation). When null, the legacy SK-11.4 suspend/resume path runs.
     */
    private final SkillApprovalChannel approvalChannel;

    /**
     * Creates a scanner over a single registry and without an inline approval channel — equivalent to
     * {@link Builder#build()} with no channel, preserving the SK-11.4 suspend-on-ASK behaviour for callers that have
     * not yet adopted SK-11.6.
     *
     * <p>
     * Correct for a process serving one agent. A stack that stands up several must use
     * {@link Builder#registries(SkillRegistryResolver)} instead.
     *
     * @param policy
     *            the policy consulted per Skill tool_use (must not be null)
     * @param registry
     *            the registry used to resolve skill names to {@link Skill} instances (must not be null)
     * @throws NullPointerException
     *             if any argument is null
     */
    public SkillPreflightScanner(SkillInvocationPolicy policy, SkillRegistry registry) {
        this(policy, SkillRegistryResolver.fixed(Objects.requireNonNull(registry, "registry cannot be null")), null);
    }

    private SkillPreflightScanner(SkillInvocationPolicy policy, SkillRegistryResolver registries,
            SkillApprovalChannel approvalChannel) {
        this.policy = Objects.requireNonNull(policy, "policy cannot be null");
        this.registries = Objects.requireNonNull(registries, "registries cannot be null");
        this.approvalChannel = approvalChannel;
    }

    /** Returns a builder for the multi-arg construction path (SK-11.6 onward). */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Scans the given tool_uses and returns the scan outcome.
     *
     * @param toolUses
     *            the tool_use blocks extracted from the latest LLM response (must not be null; may be empty)
     * @param agentRuntimeId
     *            the active agent runtime id (nullable for non-interactive callers)
     * @param sessionId
     *            the session the turn belongs to (nullable — scheduled tasks and other system-initiated runs
     *            have none; subagent forks do not reach this scanner, which runs only on the main-agent turn loop)
     * @param principal
     *            the principal on whose behalf the agent is running (nullable for system/scheduled paths)
     * @return the scan result (never null)
     */
    public SkillPreflightScanResult scan(List<ToolUse> toolUses, AgentRuntimeId agentRuntimeId, SessionId sessionId,
            Principal principal) {
        Objects.requireNonNull(toolUses, "toolUses cannot be null");

        if (toolUses.stream().noneMatch(toolUse -> SkillTool.TOOL_NAME.equals(toolUse.getName()))) {
            // The common case. Resolving the registry first would make every tool-free turn pay for it, and would
            // log the warning below on runtimes that were never going to invoke a skill.
            return SkillPreflightScanResult.proceed();
        }
        final SkillRegistry registry = registries.resolve(agentRuntimeId).orElse(null);
        if (registry == null) {
            // Not recoverable here, and not silent either. Every skill in this response now reaches SkillTool, which
            // re-checks the policy and refuses on ASK — so the user is never prompted and the skill never runs. That
            // is the exact outcome this scanner exists to prevent, so it is logged at WARN with the runtime that
            // could not be resolved rather than deferred like an unknown skill name.
            log.warn("No skill registry resolved for agent runtime {}; skipping the pre-flight scan. Skills requiring"
                    + " approval will be refused without being asked about.", agentRuntimeId);
            return SkillPreflightScanResult.proceed();
        }

        final List<PendingSkillRequest> pending = new ArrayList<>();
        for (ToolUse toolUse : toolUses) {
            if (!SkillTool.TOOL_NAME.equals(toolUse.getName())) {
                continue;
            }
            final Optional<String> skillName = extractStringInput(toolUse, "skill");
            if (skillName.isEmpty()) {
                log.debug("Skill tool_use missing 'skill' input; deferring to SkillTool error path: id={}",
                        toolUse.getId());
                continue;
            }
            final Optional<Skill> skill = registry.getSkill(skillName.get());
            if (skill.isEmpty()) {
                log.debug("Unknown skill in pre-flight scan; deferring to SkillTool error path: name={}",
                        skillName.get());
                continue;
            }
            final String args = extractStringInput(toolUse, "args").orElse("");
            final SkillInvocationDecision decision = policy.check(SkillInvocationRequest.builder().skill(skill.get())
                    .args(args).agentRuntimeId(agentRuntimeId).sessionId(sessionId).principal(principal).build());
            if (decision == SkillInvocationDecision.ASK) {
                pending.add(PendingSkillRequest.builder().toolUseId(toolUse.getId()).skillName(skill.get().getName())
                        .args(args).build());
            }
        }
        if (pending.isEmpty()) {
            return SkillPreflightScanResult.proceed();
        }
        // The channel-invocation guard requires a non-null agentRuntimeId because SkillApprovalChannel.requestApproval
        // is documented to receive a non-null agentRuntimeId (it scopes AgentApprovalStore writes). scan() itself
        // accepts a nullable agentRuntimeId for system/scheduled paths; in that case we skip the channel entirely and
        // let
        // the suspend path handle it — the SK-11.4 flow does not require a agentRuntimeId because the registry
        // generates
        // a fresh PendingTurnId on its own.
        if (approvalChannel != null && agentRuntimeId != null) {
            // SK-11.6: resolve inline. The channel writes ALLOW/DENY through the AgentApprovalStore (or whatever
            // backing the policy chain consults), so by the time SkillTool.execute() runs the per-call check it sees
            // the user's answer and either proceeds (ALLOW) or returns the policy-rejected error (DENY). We never
            // re-run the policy here — the contract on SkillApprovalChannel makes the channel responsible for
            // persisting before returning, and a re-scan would mask channel bugs (silent failure → infinite re-prompt
            // loop on a future turn).
            try {
                approvalChannel.requestApproval(pending, agentRuntimeId, sessionId);
                return SkillPreflightScanResult.proceed();
            } catch (IllegalStateException e) {
                // Documented escape hatch on the channel API — the implementation reports it cannot resolve inline
                // (e.g., the CLI channel has no terminal bound because we're running headless or under test). Logged
                // at DEBUG so the headless fallback does not flood logs with spurious WARNs every turn.
                log.debug("Approval channel cannot resolve inline; falling back to suspend: {}", e.getMessage());
            } catch (RuntimeException e) {
                // Channel violated its "never throw" contract. Defensive log + fall through so the in-flight turn
                // still reaches the user via the SK-11.4 suspend path instead of being lost.
                log.warn("Approval channel threw despite contract; falling back to suspend: {}", e.getMessage(), e);
            }
        }
        return SkillPreflightScanResult.suspend(pending);
    }

    private static Optional<String> extractStringInput(ToolUse toolUse, String key) {
        final Object value = toolUse.getInput().get(key);
        return value instanceof String s ? Optional.of(s) : Optional.empty();
    }

    /** Builder for {@link SkillPreflightScanner}. */
    public static final class Builder {

        private SkillInvocationPolicy policy;
        private SkillRegistryResolver registries;
        private SkillApprovalChannel approvalChannel;

        private Builder() {
        }

        public Builder policy(SkillInvocationPolicy policy) {
            this.policy = policy;
            return this;
        }

        /**
         * Sets a single registry for every runtime. Correct only while the process serves one agent — see
         * {@link #registries(SkillRegistryResolver)}.
         *
         * @param registry
         *            the registry (must not be null)
         * @return this builder
         */
        public Builder registry(SkillRegistry registry) {
            this.registries = registry == null ? null : SkillRegistryResolver.fixed(registry);
            return this;
        }

        /**
         * Sets the per-runtime registry resolver. Required when the process stands up more than one agent: the
         * scanner is shared by the executor while registries are agent-scoped.
         *
         * @param registries
         *            the resolver (must not be null)
         * @return this builder
         */
        public Builder registries(SkillRegistryResolver registries) {
            this.registries = registries;
            return this;
        }

        /**
         * Sets the optional inline approval back-channel. When omitted, the scanner uses the legacy SK-11.4
         * suspend-on-ASK path.
         */
        public Builder approvalChannel(SkillApprovalChannel approvalChannel) {
            this.approvalChannel = approvalChannel;
            return this;
        }

        public SkillPreflightScanner build() {
            // Validate at the builder boundary so the stack trace names the builder rather than the private
            // constructor — keeps the failure call site recognisable for readers wiring the scanner up at startup.
            Objects.requireNonNull(policy, "policy cannot be null");
            Objects.requireNonNull(registries, "registry cannot be null");
            return new SkillPreflightScanner(policy, registries, approvalChannel);
        }
    }
}
