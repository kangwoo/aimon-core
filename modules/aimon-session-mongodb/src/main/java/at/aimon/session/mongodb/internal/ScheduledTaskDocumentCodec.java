package at.aimon.session.mongodb.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.bson.Document;

import at.aimon.core.agent.AgentDefinitionVersion;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.RoutineStep;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.ScheduledTaskId;

/**
 * Document codec for {@link ScheduledTask} records in the {@code scheduled_tasks} collection.
 *
 * <p>
 * Document shape: <pre>
 * {
 *   _id: "0b9f…",                        // ScheduledTaskId (a UUID when generated)
 *   name: "...",
 *   description: "...",                  // omitted when absent
 *   cronExpression: "0 2 * * *",
 *   timezone: "Asia/Seoul",              // omitted when absent
 *   routine: [ { id, tool, toolParams, maxRetries, retryDelay: "PT5S", timeout: "PT5M" }, ... ],
 *   owner: { type: "USER", id: "...", displayName: "..." },
 *   boundRuntimeId: "agent:ops",
 *   agentDefinitionVersion: "...",       // omitted when absent
 *   enabled: true,
 *   createdAt: "2026-10-06T01:02:03.123456789Z",   // always nine fraction digits
 *   lastExecutedAt: "..."                // omitted until the first run
 * }
 * </pre>
 *
 * <h2>Why the times are strings</h2>
 *
 * <p>
 * A BSON date keeps milliseconds and an {@link Instant} keeps nanoseconds. Stored as a date, {@code getCreatedAt()}
 * and {@code getLastExecutedAt()} of a task read back would differ from the task that was saved, and from the same
 * task in the in-memory repository — the shared contract suite compares field by field for that reason. ISO-8601
 * text round-trips exactly. The cost is real and accepted: no date range queries and no TTL index on these fields;
 * nothing uses either today.
 *
 * <p>
 * Instants are written with a fixed nine-digit fraction ({@link #encodeInstant}), so the strings sort in time order.
 * {@code Instant.toString()} would not do: it drops a zero fraction, and {@code "…:00Z"} sorts after
 * {@code "…:00.500Z"}. Durations are ISO-8601 as well.
 *
 * <p>
 * The owner is nested as {@code {type, id, displayName}}, the shape {@code BackgroundTaskDocumentCodec} uses, and the
 * owner queries filter on {@code owner.type} and {@code owner.id} — the two fields {@link Principal#equals} compares.
 */
public final class ScheduledTaskDocumentCodec {

    /** Field holding the task's display name. */
    public static final String F_NAME = "name";

    /** Field holding the five-field cron expression. */
    public static final String F_CRON_EXPRESSION = "cronExpression";

    /** Field holding the optional time zone id. */
    public static final String F_TIMEZONE = "timezone";

    /** Field holding the routine, an array of step documents. */
    public static final String F_ROUTINE = "routine";

    /** Field holding the id of the agent runtime the task is bound to. */
    public static final String F_BOUND_RUNTIME_ID = "boundRuntimeId";

    /** Field holding the optional agent definition version the task was registered against. */
    public static final String F_AGENT_DEFINITION_VERSION = "agentDefinitionVersion";

    /** Field holding whether the task is enabled. */
    public static final String F_ENABLED = "enabled";

    /** Field holding when the task last ran. */
    public static final String F_LAST_EXECUTED_AT = "lastExecutedAt";

    /** Path of the owner's type inside the nested owner document. */
    public static final String F_OWNER_TYPE = DocumentKeys.F_OWNER + ".type";

    /** Path of the owner's id inside the nested owner document. */
    public static final String F_OWNER_ID = DocumentKeys.F_OWNER + ".id";

    private static final DateTimeFormatter FIXED_WIDTH_INSTANT = new DateTimeFormatterBuilder().appendInstant(9)
            .toFormatter();

    private static final String OWNER_TYPE = "type";
    private static final String OWNER_ID = "id";
    private static final String OWNER_DISPLAY_NAME = "displayName";

    private static final String STEP_ID = "id";
    private static final String STEP_TOOL = "tool";
    private static final String STEP_TOOL_PARAMS = "toolParams";
    private static final String STEP_MAX_RETRIES = "maxRetries";
    private static final String STEP_RETRY_DELAY = "retryDelay";
    private static final String STEP_TIMEOUT = "timeout";

    /**
     * Encodes a task as the document stored under its id.
     *
     * @param task
     *            the task to encode (must not be null)
     * @return the document, {@code _id} included
     */
    public Document encode(ScheduledTask task) {
        Objects.requireNonNull(task, "task must not be null");
        final Document doc = new Document();
        doc.append(DocumentKeys.F_ID, task.getId().value());
        doc.append(F_NAME, task.getName());
        task.getDescription().ifPresent(description -> doc.append(DocumentKeys.F_DESCRIPTION, description));
        doc.append(F_CRON_EXPRESSION, task.getCronExpression());
        if (task.getTimezone() != null) {
            doc.append(F_TIMEZONE, task.getTimezone());
        }
        final List<Document> routine = new ArrayList<>();
        for (RoutineStep step : task.getRoutine()) {
            routine.add(encodeStep(step));
        }
        doc.append(F_ROUTINE, routine);
        final Principal owner = task.getOwner();
        doc.append(DocumentKeys.F_OWNER, new Document().append(OWNER_TYPE, owner.getType().name())
                .append(OWNER_ID, owner.getId()).append(OWNER_DISPLAY_NAME, owner.getDisplayName()));
        doc.append(F_BOUND_RUNTIME_ID, task.getBoundRuntimeId().value());
        task.getAgentDefinitionVersion().ifPresent(version -> doc.append(F_AGENT_DEFINITION_VERSION, version.value()));
        doc.append(F_ENABLED, task.isEnabled());
        doc.append(DocumentKeys.F_CREATED_AT, encodeInstant(task.getCreatedAt()));
        task.getLastExecutedAt().ifPresent(at -> doc.append(F_LAST_EXECUTED_AT, encodeInstant(at)));
        return doc;
    }

    /**
     * Decodes a stored document.
     *
     * @param doc
     *            a document read from the collection (must not be null)
     * @return the task
     * @throws RuntimeException
     *             if the document is not a task this build can read — a missing required field, an owner type or a
     *             time it cannot parse
     */
    public ScheduledTask decode(Document doc) {
        Objects.requireNonNull(doc, "doc must not be null");
        final ScheduledTask.Builder builder = ScheduledTask.builder()
                .id(ScheduledTaskId.of(doc.getString(DocumentKeys.F_ID))).name(doc.getString(F_NAME))
                .description(doc.getString(DocumentKeys.F_DESCRIPTION)).cronExpression(doc.getString(F_CRON_EXPRESSION))
                .timezone(doc.getString(F_TIMEZONE)).owner(decodeOwner(doc.get(DocumentKeys.F_OWNER, Document.class)))
                .boundRuntimeId(AgentRuntimeId.of(doc.getString(F_BOUND_RUNTIME_ID)))
                .enabled(Boolean.TRUE.equals(doc.getBoolean(F_ENABLED)))
                .createdAt(Instant.parse(doc.getString(DocumentKeys.F_CREATED_AT)));
        final String version = doc.getString(F_AGENT_DEFINITION_VERSION);
        if (version != null) {
            builder.agentDefinitionVersion(AgentDefinitionVersion.of(version));
        }
        final String lastExecutedAt = doc.getString(F_LAST_EXECUTED_AT);
        if (lastExecutedAt != null) {
            builder.lastExecutedAt(Instant.parse(lastExecutedAt));
        }
        final List<RoutineStep> routine = new ArrayList<>();
        for (Document step : doc.getList(F_ROUTINE, Document.class, List.of())) {
            routine.add(decodeStep(step));
        }
        return builder.routine(routine).build();
    }

    /**
     * Writes an instant as ISO-8601 with a fixed nine-digit fraction, so stored values sort in time order.
     *
     * @param instant
     *            the instant to write (must not be null)
     * @return text that {@link Instant#parse} reads back to the same instant
     */
    public static String encodeInstant(Instant instant) {
        return FIXED_WIDTH_INSTANT.format(Objects.requireNonNull(instant, "instant must not be null"));
    }

    private static Document encodeStep(RoutineStep step) {
        final Document doc = new Document();
        if (step.getId() != null) {
            doc.append(STEP_ID, step.getId());
        }
        return doc.append(STEP_TOOL, step.getTool()).append(STEP_TOOL_PARAMS, step.getToolParams())
                .append(STEP_MAX_RETRIES, step.getMaxRetries())
                .append(STEP_RETRY_DELAY, step.getRetryDelay().toString())
                .append(STEP_TIMEOUT, step.getTimeout().toString());
    }

    private static RoutineStep decodeStep(Document doc) {
        return RoutineStep.builder().id(doc.getString(STEP_ID)).tool(doc.getString(STEP_TOOL))
                .toolParams(doc.getString(STEP_TOOL_PARAMS)).maxRetries(doc.getInteger(STEP_MAX_RETRIES))
                .retryDelay(Duration.parse(doc.getString(STEP_RETRY_DELAY)))
                .timeout(Duration.parse(doc.getString(STEP_TIMEOUT))).build();
    }

    private static Principal decodeOwner(Document owner) {
        Objects.requireNonNull(owner, "task document has no owner");
        return Principal.builder().type(Principal.Type.valueOf(owner.getString(OWNER_TYPE)))
                .id(owner.getString(OWNER_ID)).displayName(owner.getString(OWNER_DISPLAY_NAME)).build();
    }
}
