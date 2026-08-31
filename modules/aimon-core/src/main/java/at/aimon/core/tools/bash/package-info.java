/**
 * Bash execution tools — running shell commands on the agent's behalf, in the foreground or in the background.
 *
 * <h2>The shell is injected, not chosen here</h2>
 *
 * <p>
 * {@link at.aimon.core.tools.bash.BashTool} takes a {@link at.aimon.core.shell.VirtualShell} and does nothing else to
 * decide how a command runs. There used to be a second abstraction in this package — a {@code BashExecutor} SPI with a
 * {@code ProcessBashExecutor} and a {@code VirtualShellBashExecutor} adapter — and it has been removed. It described
 * the same thing {@code VirtualShell} already describes ("run a command, give me the output"), so every capability the
 * shell gained had to be re-plumbed through a second interface, and the adapter existed only to bridge the two.
 *
 * <p>
 * Consequences worth knowing before adding to this package:
 *
 * <ul>
 * <li><b>Timeouts and process teardown belong to the shell.</b> The tool passes a deadline in
 * {@link at.aimon.core.shell.ExecutionOptions} and blocks; the shell kills the process — and its descendants — when the
 * deadline passes or the calling thread is interrupted. Do not wrap shell calls in a future to add a timeout: that
 * gives back the old behaviour where the waiter gave up and the command kept running.
 * <li><b>A non-zero exit is a result, not a failure.</b> {@code shell.execute} returns a
 * {@link at.aimon.core.shell.ShellCommandResult} carrying the code; only an inability to run or complete the command
 * throws. Code that treats "threw" as "the command failed" will misreport both directions.
 * <li><b>Sandboxing is a shell choice.</b> An assembly that wants commands to run in Docker or Kubernetes passes a
 * different {@code VirtualShell}; nothing in this package changes. The same applies to a non-bash platform: Windows
 * support would be another {@code VirtualShell} implementation, not a branch inside {@code BashTool}.
 * </ul>
 *
 * <h2>Background execution</h2>
 *
 * <p>
 * {@link at.aimon.core.tools.bash.BackgroundBashManager} tracks commands that outlive the turn that started them, and
 * {@link at.aimon.core.tools.bash.BashOutputTool} reads them back. Output is <em>not</em> streamed — a task's buffer is
 * filled in one batch when the command finishes — so polling a running task reports its status and nothing else. See
 * {@link at.aimon.core.tools.bash.BackgroundBashTask} for why, and for the two things that must be kept out of that
 * buffer: the truncation notice and the exit status.
 */
package at.aimon.core.tools.bash;
