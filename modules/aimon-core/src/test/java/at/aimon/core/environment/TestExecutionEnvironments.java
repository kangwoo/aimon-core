package at.aimon.core.environment;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.file.ReadTool;

/**
 * Test helpers for giving tools an {@link ExecutionEnvironment} the way the executors do: a plain environment over a
 * given filesystem (and shell), published under {@link ToolContextKeys#EXECUTION_ENVIRONMENT}.
 */
public final class TestExecutionEnvironments {

    private TestExecutionEnvironments() {
    }

    /**
     * An environment over a filesystem and no usable shell. Its descriptor's working directory is the filesystem's.
     *
     * @param fileSystem
     *            the filesystem
     * @return the environment
     */
    public static ExecutionEnvironment of(VirtualFileSystem fileSystem) {
        return of(fileSystem, null);
    }

    /**
     * An environment over a filesystem and a shell.
     *
     * @param fileSystem
     *            the filesystem
     * @param shell
     *            the shell (null: calls fail)
     * @return the environment
     */
    public static ExecutionEnvironment of(VirtualFileSystem fileSystem, VirtualShell shell) {
        return builder().fileSystem(fileSystem).shell(shell).build();
    }

    /**
     * An environment over a shell and no usable filesystem.
     *
     * @param shell
     *            the shell
     * @return the environment
     */
    public static ExecutionEnvironment ofShell(VirtualShell shell) {
        return builder().shell(shell).build();
    }

    /**
     * A tool context carrying the environment and a fresh read-stamp map, as an executor builds one.
     *
     * @param fileSystem
     *            the filesystem
     * @return the context
     */
    public static ToolContext context(VirtualFileSystem fileSystem) {
        return contextBuilder(fileSystem).build();
    }

    /**
     * A tool context builder carrying the environment and a fresh read-stamp map.
     *
     * @param fileSystem
     *            the filesystem
     * @return the builder
     */
    public static ToolContext.Builder contextBuilder(VirtualFileSystem fileSystem) {
        return contextBuilder(of(fileSystem));
    }

    /**
     * A tool context builder carrying the environment and a fresh read-stamp map.
     *
     * @param environment
     *            the environment
     * @return the builder
     */
    public static ToolContext.Builder contextBuilder(ExecutionEnvironment environment) {
        return ToolContext.builder().put(ToolContextKeys.EXECUTION_ENVIRONMENT, environment)
                .put(ReadTool.FILE_STAMPS_KEY, new ConcurrentHashMap<String, FileStamp>());
    }

    /**
     * A tool context carrying only the environment — no read-stamp map, so {@code Write} performs no stale-write check.
     *
     * @param environment
     *            the environment
     * @return the context
     */
    public static ToolContext withoutStamps(ExecutionEnvironment environment) {
        return ToolContext.builder().put(ToolContextKeys.EXECUTION_ENVIRONMENT, environment).build();
    }

    /**
     * A provider that answers every request with {@link #of(VirtualFileSystem)} (and a fork's parent as-is), for
     * tests that build a runtime through the factory.
     *
     * @param fileSystem
     *            the workspace filesystem
     * @return the provider
     */
    public static ExecutionEnvironmentProvider provider(VirtualFileSystem fileSystem) {
        final ExecutionEnvironment environment = of(fileSystem);
        return request -> request.parent().orElse(environment);
    }

    /**
     * An environment that only describes itself; its filesystem and shell fail every call.
     *
     * @param descriptor
     *            the descriptor
     * @return the environment
     */
    public static ExecutionEnvironment withDescriptor(EnvironmentDescriptor descriptor) {
        final ExecutionEnvironment unavailable = UnavailableExecutionEnvironment.of("not configured in this test");
        return new ExecutionEnvironment() {
            @Override
            public VirtualFileSystem fileSystem() {
                return unavailable.fileSystem();
            }

            @Override
            public VirtualShell shell() {
                return unavailable.shell();
            }

            @Override
            public EnvironmentDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public String stage(StagedResource resource) {
                return resource.getSourceDir();
            }
        };
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for a test environment. */
    public static final class Builder {
        private VirtualFileSystem fileSystem;
        private VirtualShell shell;
        private String workingDirectory;
        private boolean durable = true;
        private ContentSearch contentSearch;

        private Builder() {
        }

        public Builder fileSystem(VirtualFileSystem fileSystem) {
            this.fileSystem = fileSystem;
            return this;
        }

        public Builder shell(VirtualShell shell) {
            this.shell = shell;
            return this;
        }

        public Builder workingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory.toString();
            return this;
        }

        public Builder durable(boolean durable) {
            this.durable = durable;
            return this;
        }

        public Builder contentSearch(ContentSearch contentSearch) {
            this.contentSearch = contentSearch;
            return this;
        }

        public ExecutionEnvironment build() {
            final ExecutionEnvironment unavailable = UnavailableExecutionEnvironment.of("not configured in this test");
            final VirtualFileSystem fs = fileSystem != null ? fileSystem : unavailable.fileSystem();
            final VirtualShell sh = shell != null ? shell : unavailable.shell();
            final String directory = workingDirectory != null
                    ? workingDirectory
                    : (fileSystem != null && fileSystem.getWorkingDirectory() != null
                            ? fileSystem.getWorkingDirectory()
                            : "");
            final EnvironmentDescriptor descriptor = EnvironmentDescriptor.builder().workingDirectory(directory)
                    .platform("linux").osVersion("test").shellName("bash").build();
            final boolean isDurable = durable;
            final ContentSearch search = contentSearch;
            return new ExecutionEnvironment() {
                @Override
                public VirtualFileSystem fileSystem() {
                    return fs;
                }

                @Override
                public VirtualShell shell() {
                    return sh;
                }

                @Override
                public EnvironmentDescriptor descriptor() {
                    return descriptor;
                }

                @Override
                public boolean durable() {
                    return isDurable;
                }

                @Override
                public String stage(StagedResource resource) {
                    Objects.requireNonNull(resource, "resource must not be null");
                    return resource.getSourceDir();
                }

                @Override
                public Optional<ContentSearch> contentSearch() {
                    return Optional.ofNullable(search);
                }

                @Override
                public String toString() {
                    return "TestExecutionEnvironment{" + directory + '}';
                }
            };
        }
    }
}
