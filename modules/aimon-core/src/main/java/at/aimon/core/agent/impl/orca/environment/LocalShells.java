package at.aimon.core.agent.impl.orca.environment;

import java.nio.file.Path;

import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.impl.local.LocalShell;

/**
 * Factory for local {@link VirtualShell} instances.
 *
 * <p>
 * Lets the runtime assembly obtain a shell without importing {@code at.aimon.core.shell.impl} directly. That boundary
 * is
 * enforced by ArchUnit ({@code PackageDependencyArchitectureTest.shellImplMustNotLeakOutsideShellTree}), which names
 * exactly two places allowed to reference the implementation package: the {@code at.aimon.core.shell} tree itself, and
 * this package — the in-core assembler that already pairs a concrete {@code LocalFileSystem} with a concrete
 * {@link LocalShell} in {@link LocalExecutionEnvironment}. This class must therefore live here and nowhere else;
 * {@code OrcaAgentRuntimeFactory} and the tool providers see only the SPI type it returns.
 *
 * <p>
 * The shell returned here is a <em>default</em>. An assembly that wants tools and skill hooks to share one shell — or
 * that wants a sandboxed (Docker/Kubernetes) shell instead — passes its own via
 * {@code OrcaAgentRuntimeFactory.withShell(...)} and keeps ownership of closing it.
 */
public final class LocalShells {

    private LocalShells() {
    }

    /**
     * Creates a shell that runs commands on the local machine with no default working directory.
     *
     * @return a new local shell
     */
    public static VirtualShell create() {
        return new LocalShell();
    }

    /**
     * Creates a shell that runs commands on the local machine, rooted at the given directory.
     *
     * @param defaultWorkingDirectory
     *            the working directory applied to commands that do not specify one, or null for the system default
     * @return a new local shell
     */
    public static VirtualShell create(Path defaultWorkingDirectory) {
        return new LocalShell(defaultWorkingDirectory);
    }
}
