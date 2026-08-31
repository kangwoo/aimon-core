package at.aimon.bootstrap.spec;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * Builds the {@link VirtualFileSystem} for one agent runtime.
 *
 * <p>
 * The parameter is an {@link AgentRuntimeId} rather than nothing at all because that id carries <b>both</b> axes
 * a deployment isolates on — {@code agent:<name>[:<discriminator>]}. A factory that ignored it would hand every
 * agent and every tenant the same storage, which is the one mistake in this area that produces no error: the
 * agent reads a file, the file is there, and it belongs to another customer.
 *
 * <p>
 * Implementations must be thread-safe. Runtimes for the tenant axis are created lazily on first use, so two
 * requests for two tenants can be inside {@code create} at the same time.
 *
 * <h2>Ownership</h2>
 *
 * <p>
 * Every instance returned here is <b>stack-owned</b>: the stack registers it for close at shutdown, and — once
 * runtimes are evicted rather than living for the whole process — at eviction of the runtime it was built for.
 * That is deliberate; a factory called once per tenant has no other close point, so leaving them to the caller
 * would leak one backend connection per tenant that has ever been seen. Use {@link FileSystemSpec#supplied} for
 * an instance whose lifetime the caller manages.
 *
 * <p>
 * The returned file system must be <b>initialized</b>. The stack calls no lifecycle method on an instance it did
 * not construct itself.
 */
@FunctionalInterface
public interface VirtualFileSystemFactory {

    /**
     * Creates the file system for the given runtime.
     *
     * @param agentRuntimeId
     *            the runtime the file system will serve (never null)
     * @return an initialized file system, never null
     */
    VirtualFileSystem create(AgentRuntimeId agentRuntimeId);
}
