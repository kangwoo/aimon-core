package at.aimon.core.environment.impl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellExecutionException;

/**
 * A view of a shell whose commands default to another working directory — an isolated branch's root. A command that
 * sets its own working directory keeps it. The directory is created on first use, so a branch that has not written a
 * file yet can still run commands.
 *
 * <p>
 * This moves the default cwd and nothing else: an absolute path in a command still reaches the whole disk. Local
 * isolation is "file tools + default cwd" (execution-environment design §4.2). The delegate is borrowed and never
 * closed.
 */
final class WorkingDirectoryShell implements VirtualShell {

    private final VirtualShell delegate;
    private final Path workingDirectory;

    WorkingDirectoryShell(VirtualShell delegate, Path workingDirectory) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
    }

    @Override
    public ShellCommandResult execute(ShellCommand command) throws ShellExecutionException {
        return execute(command, ExecutionOptions.defaults());
    }

    @Override
    public ShellCommandResult execute(ShellCommand command, ExecutionOptions options) throws ShellExecutionException {
        if (options.getWorkingDirectory() != null) {
            return delegate.execute(command, options);
        }
        try {
            Files.createDirectories(workingDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create branch working directory " + workingDirectory, e);
        }
        return delegate.execute(command, options.toBuilder().workingDirectory(workingDirectory.toString()).build());
    }

    @Override
    public String getWorkingDirectory() {
        return workingDirectory.toString();
    }

    @Override
    public boolean supports(ShellFeature feature) {
        return delegate.supports(feature);
    }

    @Override
    public void close() {
        // Borrowed: the provider owns the underlying shell.
    }
}
