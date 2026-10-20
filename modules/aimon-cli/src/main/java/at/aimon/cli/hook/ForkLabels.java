package at.aimon.cli.hook;

/**
 * The short form of a fork's task id that the CLI shows to tell concurrent forks apart — the same eight characters the
 * approval gate puts in its {@code [task ...]} prompt label.
 */
final class ForkLabels {

    /** Number of task id characters shown. */
    static final int SHORT_TASK_ID_CHARS = 8;

    private ForkLabels() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * @param taskId
     *            the fork's task id (must not be null)
     * @return its first {@value #SHORT_TASK_ID_CHARS} characters, or the whole id when shorter
     */
    static String shortTaskId(String taskId) {
        return taskId.length() <= SHORT_TASK_ID_CHARS ? taskId : taskId.substring(0, SHORT_TASK_ID_CHARS);
    }
}
