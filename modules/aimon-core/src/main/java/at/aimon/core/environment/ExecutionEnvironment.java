package at.aimon.core.environment;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.shell.VirtualShell;

/**
 * The filesystem, shell and self-description one execution's tools run against.
 *
 * <p>
 * An environment is resolved <b>once per execution</b> by an {@link ExecutionEnvironmentProvider} and published in
 * the write-once {@code ToolContextKeys.EXECUTION_ENVIRONMENT} key. File tools, {@code Bash}, {@code WikiIngest} and
 * {@code Skill} read it on every call instead of holding a filesystem or shell from their constructors, and prompt
 * assembly renders the same environment's {@link #descriptor()}, so what the prompt describes and where the tools run
 * cannot differ within one execution.
 *
 * <p>
 * <b>Not {@code Closeable}, by design.</b> An environment is a per-execution <em>view</em>; the resources behind it
 * (a local shell, a remote connection) belong to the provider. Closing them when one execution ends would cut off
 * every other execution sharing them.
 *
 * @see ExecutionEnvironmentProvider
 * @see UnavailableExecutionEnvironment
 */
public interface ExecutionEnvironment {

    /**
     * Returns the filesystem the model's file tools read and write.
     *
     * @return the working filesystem (never null)
     */
    VirtualFileSystem fileSystem();

    /**
     * Returns the shell {@code Bash} runs commands in. It sees the same files as {@link #fileSystem()}.
     *
     * @return the shell (never null)
     */
    VirtualShell shell();

    /**
     * Describes this environment for the prompt: where commands run, on what platform.
     *
     * @return the descriptor (never null; never throws, even for an unavailable environment)
     */
    EnvironmentDescriptor descriptor();

    /**
     * Whether files written here outlive the execution. {@code false} for ephemeral environments (a sandbox
     * workspace, an isolated worktree branch); artifact-aware tools copy such files into the control store before
     * registering them.
     *
     * @return {@code true} if files written here are durable
     */
    default boolean durable() {
        return true;
    }

    /**
     * Makes a control-plane file set readable from this environment's shell and file tools, and returns the path to
     * use (design §4.4). The copy is content-addressed ({@code {stagingRoot}/{name}/{contentKey}/}) and skipped only
     * when the target's {@code .staged} marker exists — never from provider memory.
     *
     * @param resource
     *            the file set to stage (must not be null)
     * @return the absolute path of the staged directory as this environment's shell and file tools see it
     * @throws at.aimon.core.environment.exception.StagingException
     *             if the resource cannot be staged (over the size limit, unreadable, changed since it was scanned,
     *             or holding file names this environment's filesystem cannot keep apart)
     */
    String stage(StagedResource resource);

    /**
     * Returns a derived environment whose writes are isolated under {@code branchKey}. The two ways to say no mean
     * different things: <b>empty</b> means this kind of environment has no isolation at all; <b>throwing</b> means
     * isolation is refused here, and the exception message is the reason — the environment is unavailable, or it is
     * already a branch that cannot be isolated again. A workflow runner refuses to run an isolated branch unscoped in
     * either case, and reports the reason when there is one.
     *
     * @param branchKey
     *            the branch key ({@code [A-Za-z0-9_]+})
     * @return the isolated environment, or empty if this kind of environment does not support isolation
     * @throws RuntimeException
     *             if isolation is refused here; the message says why
     */
    default Optional<ExecutionEnvironment> isolate(String branchKey) {
        return Optional.empty();
    }

    /**
     * Returns the environment this one was {@linkplain #isolate(String) isolated} from, if it is a branch that
     * declares its lineage. {@code WorktreeMerge} uses it to refuse promoting a branch into an environment it does not
     * belong to; a branch that declares nothing is accepted on the weaker checks alone.
     *
     * @return the parent environment (the same instance {@code isolate} was called on), or empty if this is not a
     *         branch or it does not declare its lineage
     */
    default Optional<ExecutionEnvironment> isolatedFrom() {
        return Optional.empty();
    }

    /**
     * Returns an optional content search that answers {@code Grep} queries without reading files one by one.
     *
     * @return the content search, or empty to let {@code Grep} walk the filesystem itself
     */
    default Optional<ContentSearch> contentSearch() {
        return Optional.empty();
    }

    /**
     * Returns the longest a background command ({@code Bash(run_in_background=true)}) may run in this environment —
     * the backstop for a command the model never stops. {@code Bash} gives the command this timeout in place of its own
     * default of 24 hours, in either direction: an environment that cannot afford a day of an abandoned command (a
     * sandbox slot it keeps awake) says less, one whose commands legitimately run longer says more.
     *
     * <p>
     * A value that is zero or negative is not a ceiling — a shell reads such a timeout as "wait forever" — so
     * {@code Bash} ignores it and uses its default.
     *
     * @return the ceiling, or empty to leave it to the caller's default
     */
    default Optional<Duration> backgroundCommandTimeout() {
        return Optional.empty();
    }

    /**
     * Removes and returns the facts this environment has queued for the model — facts about the environment itself,
     * not about one command ("the sandbox was re-created and {@code /workspace} is empty", "this session now runs in
     * another workspace"). The core calls this once after each <b>model-facing</b> tool call, whichever tool it was,
     * and shows the notices at the head of that call's result as {@code [environment] ...} lines (issue #254). It
     * does so after the PostTool hooks, so no hook sees or rewrites them.
     *
     * <p>
     * A fact about one command's run belongs in {@code ShellCommandResult.notices()} instead, which also reaches
     * {@code BashOutput} for a background command. A provider must not put the same fact in both.
     *
     * <p>
     * Contract for implementations:
     * <ul>
     * <li><b>One-shot and atomic.</b> A notice returned once is never returned again, by this call or a concurrent
     * one. A parallel tool batch drains from several threads, and each notice must land on exactly one result.
     * Returning a notice is committing it: the core does not acknowledge delivery separately.
     * <li><b>Cheap and quiet.</b> No long remote I/O, and no exception; the core treats a throw as "nothing to
     * deliver" and logs it. A {@code null} return, and {@code null} or blank entries, are ignored.
     * <li><b>One line per notice.</b> Each entry becomes one {@code [environment]} line. A line break inside an entry
     * is not re-prefixed, so its continuation reads as the tool's own output; split a long fact into several entries.
     * <li><b>One queue per environment instance.</b> The core drains whichever instance sits in the dispatching tool
     * context. If a subagent fork is answered with an instance that shares the parent's queue, the fork's first call
     * consumes notices only the fork's model then hears, and the parent edits a reset tree unaware. Seed a fork's
     * queue with what it must also hear rather than share one.
     * </ul>
     *
     * <p>
     * Only the core's tool dispatch calls this. A tool or a hook must not: whatever it drains is lost to the model.
     * Calls made outside a model-facing dispatch (workflow scripts, scheduled routine steps) leave the queue alone, so
     * their notices wait for the next model-facing call. A turn whose model makes no tool call drains nothing.
     *
     * @return the queued notices, oldest first; empty if there are none (the default: an environment that never has
     *         anything to say)
     */
    default List<String> drainNotices() {
        return List.of();
    }
}
