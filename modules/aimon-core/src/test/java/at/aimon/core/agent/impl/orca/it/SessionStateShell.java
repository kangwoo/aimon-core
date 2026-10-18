package at.aimon.core.agent.impl.orca.it;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

import at.aimon.core.environment.ContentSearch;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.ExecutionEnvironmentProvider;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellExecutionException;

/**
 * A {@link VirtualShell} over a real one that <b>models the sandbox contract</b> for per-session state (aimon-sandbox
 * {@code ShellWrapper}): the model's {@code export}s and {@code cd} persist between commands, which a
 * {@code LocalShell} alone never does.
 *
 * <ul>
 * <li>A <b>foreground</b> command (neither {@code isHook} nor {@code isIsolated}) runs after sourcing the state file,
 * and saves {@code export -p} and {@code pwd} back into it.
 * <li>An {@code isHook()} command sources the state file and saves nothing &mdash; what a skill hook gets.
 * <li>An {@code isIsolated()} command neither sources nor saves, and starts in the shell's root.
 * </ul>
 *
 * It declares {@link ShellFeature#ISOLATED_EXECUTION} unless built not to, for the "shell cannot isolate" case.
 */
final class SessionStateShell implements VirtualShell {

    private final VirtualShell delegate;
    private final Path stateFile;
    private final boolean declaresIsolation;

    SessionStateShell(VirtualShell delegate, Path stateFile, boolean declaresIsolation) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.stateFile = Objects.requireNonNull(stateFile, "stateFile");
        this.declaresIsolation = declaresIsolation;
    }

    @Override
    public ShellCommandResult execute(ShellCommand command) throws ShellExecutionException {
        return execute(command, ExecutionOptions.defaults());
    }

    @Override
    public ShellCommandResult execute(ShellCommand command, ExecutionOptions options) throws ShellExecutionException {
        if (options.isIsolated() && declaresIsolation) {
            return delegate.execute(command, options);
        }
        final String state = quoted(stateFile.toString());
        final StringBuilder script = new StringBuilder();
        script.append("[ -f ").append(state).append(" ] && . ").append(state).append('\n');
        if (options.isHook()) {
            script.append(command.asString()).append('\n');
        } else {
            script.append("{ ").append(command.asString()).append("\n}\n__rc=$?\n");
            script.append("{ export -p; printf 'cd %q\\n' \"$PWD\"; } > ").append(state).append('\n');
            script.append("exit $__rc\n");
        }
        final String text = script.toString();
        return delegate.execute(() -> text, options);
    }

    @Override
    public String getWorkingDirectory() {
        return delegate.getWorkingDirectory();
    }

    @Override
    public boolean supports(ShellFeature feature) {
        if (feature == ShellFeature.ISOLATED_EXECUTION) {
            return declaresIsolation;
        }
        return delegate.supports(feature);
    }

    @Override
    public void close() {
        // The delegate belongs to the environment it was taken from.
    }

    private static String quoted(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * A provider decorator that gives every environment the wrapped provider resolves a {@link SessionStateShell},
     * one per environment instance, so its state persists across turns as a sandbox session's does.
     */
    static UnaryOperator<ExecutionEnvironmentProvider> wrapping(Path stateDir, boolean declaresIsolation) {
        final Map<ExecutionEnvironment, ExecutionEnvironment> wrapped = new IdentityHashMap<>();
        return local -> request -> {
            final ExecutionEnvironment environment = local.resolve(request);
            synchronized (wrapped) {
                if (wrapped.containsValue(environment)) {
                    // The local provider answers a fork with its parent, which is already a wrapper.
                    return environment;
                }
                return wrapped.computeIfAbsent(environment, env -> {
                    try {
                        final Path file = Files.createTempFile(Files.createDirectories(stateDir), "state", ".sh");
                        Files.delete(file);
                        return new Wrapped(env, new SessionStateShell(env.shell(), file, declaresIsolation));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
        };
    }

    /** An environment with a {@link SessionStateShell}; everything else is the wrapped environment's. */
    private static final class Wrapped implements ExecutionEnvironment {
        private final ExecutionEnvironment delegate;
        private final VirtualShell shell;

        Wrapped(ExecutionEnvironment delegate, VirtualShell shell) {
            this.delegate = delegate;
            this.shell = shell;
        }

        @Override
        public VirtualFileSystem fileSystem() {
            return delegate.fileSystem();
        }

        @Override
        public VirtualShell shell() {
            return shell;
        }

        @Override
        public EnvironmentDescriptor descriptor() {
            return delegate.descriptor();
        }

        @Override
        public boolean durable() {
            return delegate.durable();
        }

        @Override
        public String stage(StagedResource resource) {
            return delegate.stage(resource);
        }

        @Override
        public Optional<ExecutionEnvironment> isolate(String branchKey) {
            return delegate.isolate(branchKey);
        }

        @Override
        public Optional<ExecutionEnvironment> isolatedFrom() {
            return delegate.isolatedFrom();
        }

        @Override
        public Optional<ContentSearch> contentSearch() {
            return delegate.contentSearch();
        }

        @Override
        public Optional<Duration> backgroundCommandTimeout() {
            return delegate.backgroundCommandTimeout();
        }

        @Override
        public List<String> drainNotices() {
            return delegate.drainNotices();
        }
    }
}
