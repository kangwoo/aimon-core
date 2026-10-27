/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.tool.ToolContext;

/**
 * The host's say over what the scheduling tools ({@code schedule_task}, {@code list_scheduled_tasks},
 * {@code cancel_scheduled_task}) may do, without replacing them.
 *
 * <p>
 * <b>Scheduling time is the only place a routine can be vetted.</b> A scheduled step calls its tool directly: no
 * PreTool hook, permission rule or approval gate sees it ({@link RoutineExecutor}). A host whose guards are hooks
 * therefore has nothing standing between a model and an unattended run of any tool the bound runtime carries — except
 * what it checks here, while the model is still in a turn its hooks do cover.
 *
 * <p>
 * Set it once on {@link SchedulingEngineBuilder#taskPolicy(ScheduledTaskPolicy)}; the tools read it from the
 * {@link ScheduledTaskManager} they are built over, so every runtime's tools apply the same policy. Every method has a
 * default that keeps the tools' own behaviour, so an implementation overrides only what it cares about. A method that
 * throws is treated as a refusal, never as an approval.
 */
public interface ScheduledTaskPolicy {

    /** The default policy: every request is allowed and the bound runtime is the calling runtime. */
    ScheduledTaskPolicy PERMIT_ALL = new ScheduledTaskPolicy() {
    };

    /**
     * Chooses the agent runtime a new task will be bound to, and fired on.
     *
     * <p>
     * The default keeps {@code callerRuntimeId}, the {@code AGENT_RUNTIME_ID} of the turn calling
     * {@code schedule_task}. Override it where scheduled work should run on a different runtime than the conversation
     * that asked for it — one carrying a narrower tool set, say. Whatever this returns is what
     * {@link SchedulingEngineBuilder#validateBoundRuntime(boolean) registration checks} is resolvable.
     *
     * @param callerRuntimeId
     *            the calling turn's runtime id (never null)
     * @param context
     *            the calling turn's tool context
     * @return the runtime id to bind the task to (must not be null)
     */
    default AgentRuntimeId resolveBoundRuntimeId(AgentRuntimeId callerRuntimeId, ToolContext context) {
        return callerRuntimeId;
    }

    /**
     * Decides whether a task may be scheduled. Called with the task exactly as it would be stored — name, cron
     * expression, routine steps and their tools, owner, bound runtime — before it is registered.
     *
     * @param candidate
     *            the task about to be registered
     * @param context
     *            the calling turn's tool context
     * @return the decision (must not be null)
     */
    default Decision checkSchedule(ScheduledTask candidate, ToolContext context) {
        return Decision.allow();
    }

    /**
     * Decides whether the caller may see or cancel a task. Only consulted for a task the caller already owns: this
     * narrows the tools' ownership check, it cannot widen it.
     *
     * @param task
     *            the task in question
     * @param access
     *            what the caller wants to do with it
     * @param context
     *            the calling turn's tool context
     * @return the decision (must not be null); for {@link Access#LIST} a refusal leaves the task out of the listing
     */
    default Decision checkAccess(ScheduledTask task, Access access, ToolContext context) {
        return Decision.allow();
    }

    /** What a caller wants to do with an existing task. */
    enum Access {
        /** See it in {@code list_scheduled_tasks}. */
        LIST,
        /** Cancel it with {@code cancel_scheduled_task}. */
        CANCEL
    }

    /**
     * A policy's answer: allowed, or refused with a reason the model is shown.
     */
    final class Decision {

        private static final Decision ALLOW = new Decision(null);

        private final String refusalReason;

        private Decision(String refusalReason) {
            this.refusalReason = refusalReason;
        }

        /**
         * Returns the decision that allows the request.
         *
         * @return the allowing decision
         */
        public static Decision allow() {
            return ALLOW;
        }

        /**
         * Returns a decision that refuses the request.
         *
         * @param reason
         *            why, phrased for the model that made the request (must not be blank)
         * @return a refusing decision
         */
        public static Decision deny(String reason) {
            Objects.requireNonNull(reason, "reason cannot be null");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("Refusal reason must not be blank");
            }
            return new Decision(reason);
        }

        /**
         * Reports whether the request is allowed.
         *
         * @return whether the request is allowed
         */
        public boolean isAllowed() {
            return refusalReason == null;
        }

        /**
         * Returns why the request is refused.
         *
         * @return the refusal reason, or empty for an allowing decision
         */
        public Optional<String> getRefusalReason() {
            return Optional.ofNullable(refusalReason);
        }

        @Override
        public String toString() {
            return isAllowed() ? "Decision{allow}" : "Decision{deny: " + refusalReason + '}';
        }
    }
}
