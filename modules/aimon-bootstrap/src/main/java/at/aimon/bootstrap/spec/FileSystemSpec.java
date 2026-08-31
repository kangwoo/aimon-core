package at.aimon.bootstrap.spec;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * Declares the {@link VirtualFileSystem} the stack's agent runtimes work against.
 *
 * <p>
 * Three shapes, and the axis that separates them is <b>whether every runtime sees the same files</b>:
 *
 * <ul>
 * <li>{@link #localAt(String)} — one local file system <i>per runtime</i>, rooted at that runtime's directory
 * under the workspace root ({@link AgentWorkspaceLayout}). The stack constructs each one, calls
 * {@code initialize()}, owns it, and closes it at shutdown.
 * <li>{@link #factory(VirtualFileSystemFactory)} — the same shape for a non-local backend: invoked once per
 * runtime, each instance stack-owned. The factory decides what isolation means for GridFS, S3 or a test double.
 * <li>{@link #supplied(VirtualFileSystem)} — <b>one</b> file system the caller built, shared by every runtime.
 * The stack uses it and, by the "whoever creates it closes it" rule, does not close it.
 * </ul>
 *
 * <p>
 * The ownership distinction is the reason this type exists rather than a bare {@code VirtualFileSystem} field:
 * ownership is not recoverable from the instance, so it has to be declared. Getting it wrong in the closing
 * direction shuts down a file system a Spring context still hands to other beans; getting it wrong the other way
 * leaks whatever the backend holds open.
 *
 * <p>
 * <b>{@link #supplied(VirtualFileSystem)} does not isolate.</b> With two agents, or one agent and two tenants,
 * every runtime reads and writes the same paths, so an agent can read a file another agent wrote. That is the
 * right answer when the runtimes are meant to collaborate through a shared workspace and the wrong one when they
 * represent different customers — a distinction only the caller can make, which is why it is allowed and
 * recorded as a degradation rather than rejected.
 */
public final class FileSystemSpec {

    private final String workspaceRoot;
    private final VirtualFileSystem instance;
    private final VirtualFileSystemFactory factory;
    private final boolean stackOwned;

    private FileSystemSpec(String workspaceRoot, VirtualFileSystem instance, VirtualFileSystemFactory factory,
            boolean stackOwned) {
        this.workspaceRoot = workspaceRoot;
        this.instance = instance;
        this.factory = factory;
        this.stackOwned = stackOwned;
    }

    /**
     * A stack-owned local file system per runtime, under {@code workspaceRoot}.
     *
     * <p>
     * Each runtime is rooted at {@code <workspaceRoot>/<agentRef>/<discriminator>} — see
     * {@link AgentWorkspaceLayout} for why both axes are in the path.
     *
     * @param workspaceRoot
     *            the directory every agent's workspace sits under (must not be null or blank)
     * @return the spec
     */
    public static FileSystemSpec localAt(String workspaceRoot) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
        if (workspaceRoot.isBlank()) {
            throw new IllegalArgumentException("workspaceRoot must not be blank");
        }
        return new FileSystemSpec(workspaceRoot, null, null, true);
    }

    /**
     * A caller-owned file system shared by every runtime. The stack will not close it.
     *
     * <p>
     * Shared means shared: see the class javadoc before using this with more than one agent or tenant.
     *
     * @param fileSystem
     *            the file system, already initialized (must not be null)
     * @return the spec
     */
    public static FileSystemSpec supplied(VirtualFileSystem fileSystem) {
        Objects.requireNonNull(fileSystem, "fileSystem must not be null");
        return new FileSystemSpec(null, fileSystem, null, false);
    }

    /**
     * A per-runtime file system built by {@code factory}, one instance per agent runtime, each stack-owned.
     *
     * <p>
     * The factory must return an <b>initialized</b> file system — the stack calls no lifecycle method on an
     * instance it did not construct itself.
     *
     * @param factory
     *            the factory, invoked once per runtime (must not be null)
     * @return the spec
     */
    public static FileSystemSpec factory(VirtualFileSystemFactory factory) {
        Objects.requireNonNull(factory, "factory must not be null");
        return new FileSystemSpec(null, null, factory, true);
    }

    /**
     * Returns the workspace root for the {@link #localAt(String)} form.
     *
     * @return the root, or empty for the other forms
     */
    public Optional<String> getWorkspaceRoot() {
        return Optional.ofNullable(workspaceRoot);
    }

    /**
     * Returns the shared caller-supplied instance, when there is one.
     *
     * @return the instance, or empty
     */
    public Optional<VirtualFileSystem> getInstance() {
        return Optional.ofNullable(instance);
    }

    /**
     * Returns the per-runtime factory, when there is one.
     *
     * @return the factory, or empty
     */
    public Optional<VirtualFileSystemFactory> getFactory() {
        return Optional.ofNullable(factory);
    }

    /**
     * Returns whether every runtime shares a single file system instance.
     *
     * @return {@code true} for the {@link #supplied(VirtualFileSystem)} form
     */
    public boolean isShared() {
        return instance != null;
    }

    /**
     * Returns whether the stack closes the file systems it hands out.
     *
     * @return {@code true} when the stack owns them
     */
    public boolean isStackOwned() {
        return stackOwned;
    }

    @Override
    public String toString() {
        if (workspaceRoot != null) {
            return "FileSystemSpec[local:" + workspaceRoot + "]";
        }
        return "FileSystemSpec[" + (factory != null ? "factory" : "supplied") + ", stackOwned=" + stackOwned + "]";
    }
}
