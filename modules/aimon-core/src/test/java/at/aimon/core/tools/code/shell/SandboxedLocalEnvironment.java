package at.aimon.core.tools.code.shell;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.UnaryOperator;

import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentIsolation;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.RuntimeFileSystem;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.environment.impl.LocalRuntimeFileSystem;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.impl.local.LocalShell;

/**
 * A local shell and the local runtime file system over one directory, described as a sandbox that declares
 * {@code python3} — what the engine contract suite runs the shell engine on (shell doc §14). The engine sees only the
 * environment SPI, so this is the sandbox shape without a sandbox.
 */
final class SandboxedLocalEnvironment implements ExecutionEnvironment {

    private final Path root;
    private final VirtualShell shell;
    private final VirtualFileSystem fileSystem;
    private final RuntimeFileSystem runtimeFileSystem;
    private final EnvironmentDescriptor descriptor;

    SandboxedLocalEnvironment(Path root) {
        this(root, new LocalShell(root));
    }

    SandboxedLocalEnvironment(Path root, VirtualShell shell) {
        this(root, shell, UnaryOperator.identity());
    }

    /** With the runtime file system wrapped, e.g. to add latency to its writes. */
    SandboxedLocalEnvironment(Path root, UnaryOperator<RuntimeFileSystem> wrapRuntimeFiles) {
        this(root, new LocalShell(root), wrapRuntimeFiles);
    }

    private SandboxedLocalEnvironment(Path root, VirtualShell shell,
            UnaryOperator<RuntimeFileSystem> wrapRuntimeFiles) {
        this.root = root;
        this.shell = shell;
        this.fileSystem = new LocalFileSystem(new LocalFileSystemConfig(root.toString()));
        this.runtimeFileSystem = wrapRuntimeFiles.apply(new LocalRuntimeFileSystem(root));
        this.descriptor = EnvironmentDescriptor.builder().workingDirectory(root.toString())
                .isolation(EnvironmentIsolation.SANDBOXED).runtime(EnvironmentDescriptor.RUNTIME_PYTHON3)
                .shellName("bash").build();
    }

    Path root() {
        return root;
    }

    @Override
    public VirtualFileSystem fileSystem() {
        return fileSystem;
    }

    @Override
    public VirtualShell shell() {
        return shell;
    }

    @Override
    public EnvironmentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public Optional<RuntimeFileSystem> runtimeFileSystem() {
        return Optional.of(runtimeFileSystem);
    }

    @Override
    public String stage(StagedResource resource) {
        throw new UnsupportedOperationException("not staged in this test environment");
    }
}
