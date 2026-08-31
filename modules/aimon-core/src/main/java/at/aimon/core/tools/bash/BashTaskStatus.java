package at.aimon.core.tools.bash;

/** Status of a background bash task. */
public enum BashTaskStatus {
    /** Task is currently running. */
    RUNNING,

    /** Task has completed successfully. */
    COMPLETED,

    /** Task failed with an error. */
    FAILED,

    /** Task was not found. */
    NOT_FOUND
}
