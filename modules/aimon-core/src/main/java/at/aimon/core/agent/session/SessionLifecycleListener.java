package at.aimon.core.agent.session;

/**
 * Observes the lifecycle of durable sessions — today, the deletion of a session record.
 *
 * <p>
 * The hook an extension that keeps its own per-session state (a sandbox's per-session options, a tenant index) uses to
 * drop that state when the session goes away, without the host wiring a call into every delete. Registered once on the
 * router, through {@code SessionRouterBuilder.lifecycleListener} or the stack's {@code SessionSpec}; a Spring Boot
 * application declares it as a bean.
 *
 * <p>
 * Every callback is a {@code default} no-op, so a callback added later (an {@code onCreated}, say) breaks no
 * implementor.
 *
 * <h2>Delivery contract of {@link #onDeleted(SessionDeletedEvent)}</h2>
 *
 * <ul>
 * <li><b>Once per call that deletes the record</b>, on the node that deleted it, after the record is gone — even when a
 * later teardown step of that same call (segment deletion, the terminal interrupt, the cluster {@code EVICT} broadcast)
 * fails and the call throws. A delete that fails before the record goes fires nothing.
 * <li><b>Under the session's lease.</b> The listener runs before the deleting node hands the lease back, and the lease
 * keeps being renewed while it runs, so no new turn can provision the same {@code SessionId} again and race the
 * cleanup: "delete the options of session X" cannot delete the options of a new session X created a moment later.
 * <li><b>Synchronously.</b> A slow listener delays the delete and blocks submits to that one session id. Keep it short
 * and hand heavy work to an executor of your own.
 * <li><b>At most once.</b> A node that crashes between deleting the record and calling the listener loses the event.
 * Keep a reconciliation path — for example, periodically drop per-session state whose
 * {@code SessionRecordStore.exists(id)} is false.
 * <li><b>Idempotent, please.</b> Deleting an id that never existed provisions and deletes it, so the listener fires
 * for it with an empty owner.
 * <li><b>Failures are contained.</b> An exception is logged with the session id and the listener class; the other
 * listeners still run, and the delete is not failed because of it — the record is already irreversibly gone.
 * <li><b>Only the router fires it.</b> A raw {@code SessionRecordStore.delete} or {@code clear()} bypasses the router
 * and fires nothing.
 * <li><b>Only on the deleting node.</b> Peers are not called. Shared per-session state needs exactly one cleanup;
 * node-local copies on every node are a separate problem.
 * </ul>
 *
 * <p>
 * <b>Do not call back into the {@code SessionRouter} for the session being deleted</b> from inside a callback. The
 * deleting thread holds that session's turn gate: a submit lands in the inbox and is deliberately never re-rung, and a
 * nested delete re-enters the held gate.
 */
public interface SessionLifecycleListener {

    /**
     * Called once the record of a session has been deleted. See the class javadoc for the delivery contract.
     *
     * @param event
     *            what was deleted (never null)
     */
    default void onDeleted(SessionDeletedEvent event) {
    }
}
