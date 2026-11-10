package at.aimon.session.mongodb.internal;

import java.util.Date;
import java.util.Objects;
import java.util.Optional;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.scheduling.ScheduledTaskId;

/**
 * Document codec for the stop requests {@code MongoScheduledTaskInterruptBus} sends through the capped
 * {@code scheduled_task_interrupts} collection.
 *
 * <p>
 * Document shape: <pre>
 * {
 *   _id: ObjectId,
 *   taskId: "task-…",
 *   reason: "TASK_CANCELLED",
 *   originNodeId: "node-A",
 *   occurrenceKey: "task-…@1767225600000",   // optional; absent = every run of the task
 *   createdAt: Date        // for an operator reading the collection; nothing in the code reads it back
 * }
 * </pre>
 *
 * <h2>An unknown reason is still a stop request</h2>
 *
 * <p>
 * Every node in the fleet reads this collection, including nodes one release behind the publisher, so a reason name
 * this build does not know is an expected input during a rolling upgrade rather than corruption. Decoding it strictly
 * would throw the request away, and the request is the part that matters: the run would carry on for a task somebody
 * asked to stop. The reason is only a label on the way out — it picks the wording of the recorded outcome — so an
 * unknown name decodes to {@link #UNKNOWN_REASON_FALLBACK} and the stop goes ahead.
 *
 * <h2>The occurrence key is optional, in both directions</h2>
 *
 * <p>
 * {@code occurrenceKey} arrived after the other fields. A document without it — every request an older node publishes —
 * decodes as task-wide, which is what it always meant. An older node reading a document <em>with</em> it ignores the
 * field and stops every run of the task: wider than asked, but the stop is not lost, by the same reasoning as an
 * unknown reason.
 */
public final class ScheduledTaskInterruptCodec {

    /** What a reason name from a newer node is read as: the bus's own subject, a task being stopped. */
    public static final InterruptReason UNKNOWN_REASON_FALLBACK = InterruptReason.TASK_CANCELLED;

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskInterruptCodec.class);

    /**
     * Encodes one stop request.
     *
     * @param taskId
     *            the task whose runs should stop
     * @param reason
     *            why
     * @param originNodeId
     *            the publishing node, so it can recognise its own request coming back
     * @return the document to insert
     */
    public Document encode(ScheduledTaskId taskId, InterruptReason reason, String originNodeId) {
        return encode(taskId, null, reason, originNodeId);
    }

    /**
     * Encodes one stop request, addressed to one occurrence when {@code occurrenceKey} is given.
     *
     * @param taskId
     *            the task whose run should stop
     * @param occurrenceKey
     *            the occurrence to stop, or {@code null} for every run of the task (the field is then omitted)
     * @param reason
     *            why
     * @param originNodeId
     *            the publishing node
     * @return the document to insert
     */
    public Document encode(ScheduledTaskId taskId, String occurrenceKey, InterruptReason reason, String originNodeId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(originNodeId, "originNodeId must not be null");
        final Document doc = new Document();
        doc.append(DocumentKeys.F_TASK_ID, taskId.value());
        doc.append(DocumentKeys.F_REASON, reason.name());
        doc.append(DocumentKeys.F_ORIGIN_NODE_ID, originNodeId);
        if (occurrenceKey != null) {
            doc.append(DocumentKeys.F_OCCURRENCE_KEY, occurrenceKey);
        }
        doc.append(DocumentKeys.F_CREATED_AT, new Date());
        return doc;
    }

    /**
     * Decodes one stop request.
     *
     * @param doc
     *            a document read from the collection
     * @return the request
     * @throws IllegalArgumentException
     *             if the document names no task — the one field without which there is nothing to stop
     */
    public Decoded decode(Document doc) {
        Objects.requireNonNull(doc, "doc must not be null");
        final String taskId = doc.getString(DocumentKeys.F_TASK_ID);
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("stop request without a " + DocumentKeys.F_TASK_ID);
        }
        return new Decoded(ScheduledTaskId.of(taskId), decodeReason(doc.getString(DocumentKeys.F_REASON), taskId),
                doc.getString(DocumentKeys.F_ORIGIN_NODE_ID), decodeOccurrenceKey(doc));
    }

    private static String decodeOccurrenceKey(Document doc) {
        final Object value = doc.get(DocumentKeys.F_OCCURRENCE_KEY);
        // Anything but a non-blank string is read as absent: widening to the task loses nothing a stop was meant to do.
        return value instanceof String key && !key.isBlank() ? key : null;
    }

    private static InterruptReason decodeReason(String name, String taskId) {
        if (name != null) {
            for (InterruptReason reason : InterruptReason.values()) {
                if (reason.name().equals(name)) {
                    return reason;
                }
            }
        }
        log.warn("Stop request for task '{}' carries a reason this node does not know ('{}'); honouring it as {}",
                taskId, name, UNKNOWN_REASON_FALLBACK);
        return UNKNOWN_REASON_FALLBACK;
    }

    /** One decoded stop request. */
    public static final class Decoded {

        private final ScheduledTaskId taskId;
        private final InterruptReason reason;
        private final String originNodeId;
        private final String occurrenceKey;

        Decoded(ScheduledTaskId taskId, InterruptReason reason, String originNodeId, String occurrenceKey) {
            this.taskId = taskId;
            this.reason = reason;
            this.originNodeId = originNodeId;
            this.occurrenceKey = occurrenceKey;
        }

        /** @return the occurrence the request is addressed to, or empty for a task-wide request */
        public Optional<String> getOccurrenceKey() {
            return Optional.ofNullable(occurrenceKey);
        }

        public ScheduledTaskId getTaskId() {
            return taskId;
        }

        public InterruptReason getReason() {
            return reason;
        }

        /** @return the publishing node's id, or {@code null} if the document did not carry one */
        public String getOriginNodeId() {
            return originNodeId;
        }
    }
}
