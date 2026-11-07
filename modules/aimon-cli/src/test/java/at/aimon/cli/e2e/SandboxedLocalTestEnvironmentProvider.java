package at.aimon.cli.e2e;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentIsolation;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.ExecutionEnvironmentProvider;
import at.aimon.core.environment.RuntimeFileSystem;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.environment.impl.LocalRuntimeFileSystem;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.impl.local.LocalShell;

/**
 * A provider handing every execution one local-host environment that <em>declares</em> itself sandboxed, so the Python
 * shell engine of {@code ExecuteCode} can run on a developer's machine — the test-only equivalent of core's
 * {@code SandboxedLocalEnvironment}. It is not a sandbox: the model's Python runs on this host, which is acceptable
 * only for an opt-in local test, and the CONTRIBUTING section says so.
 *
 * <p>
 * Closed by the stack (it is passed through {@code ExecutionEnvironmentSpec.provider}), which closes the shell.
 */
final class SandboxedLocalTestEnvironmentProvider implements ExecutionEnvironmentProvider, AutoCloseable {

    private final Environment environment;

    SandboxedLocalTestEnvironmentProvider(Path root) {
        this.environment = new Environment(Objects.requireNonNull(root, "root cannot be null"));
    }

    @Override
    public ExecutionEnvironment resolve(EnvironmentRequest request) {
        return environment;
    }

    @Override
    public void close() {
        environment.shell.close();
    }

    /** The one environment: a {@link LocalShell} over {@code root}, declared sandboxed with a Python 3 runtime. */
    private static final class Environment implements ExecutionEnvironment {

        private final LocalShell shell;
        private final VirtualFileSystem fileSystem;
        private final RuntimeFileSystem runtimeFileSystem;
        private final EnvironmentDescriptor descriptor;

        Environment(Path root) {
            this.shell = new LocalShell(root);
            final LocalFileSystem files = new LocalFileSystem(new LocalFileSystemConfig(root.toString()));
            files.initialize();
            this.fileSystem = files;
            this.runtimeFileSystem = new LocalRuntimeFileSystem(root);
            this.descriptor = EnvironmentDescriptor.builder().workingDirectory(root.toString())
                    .isolation(EnvironmentIsolation.SANDBOXED).runtime(EnvironmentDescriptor.RUNTIME_PYTHON3)
                    .shellName("bash").build();
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
            throw new UnsupportedOperationException("not staged in the E2E test environment");
        }
    }
}
