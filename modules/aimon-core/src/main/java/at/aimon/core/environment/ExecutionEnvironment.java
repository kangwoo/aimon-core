package at.aimon.core.environment;

import java.time.Duration;
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
     *             if the resource cannot be staged (over the size limit, unreadable, changed since it was scanned)
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
}
