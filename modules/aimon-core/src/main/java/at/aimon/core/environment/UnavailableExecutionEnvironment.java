package at.aimon.core.environment;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Objects;

import at.aimon.core.environment.exception.ExecutionEnvironmentUnavailableException;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.shell.VirtualShell;

/**
 * The environment an executor publishes when its provider could not resolve one (design §5.1).
 *
 * <p>
 * Every filesystem and shell call throws {@link ExecutionEnvironmentUnavailableException} carrying the cause, and so
 * does {@link #stage}; tools turn it into a {@code ToolResult.error}. The {@link #descriptor()} does not throw: its
 * {@code notes} say "execution environment unavailable: {cause}", so the model learns why before its first tool
 * call. A turn that needs no files or shell is unaffected. There is no path back to the host environment.
 */
public final class UnavailableExecutionEnvironment implements ExecutionEnvironment {

    private final String cause;
    private final Throwable throwable;
    private final VirtualFileSystem fileSystem;
    private final VirtualShell shell;

    private UnavailableExecutionEnvironment(String cause, Throwable throwable) {
        this.cause = cause;
        this.throwable = throwable;
        this.fileSystem = throwingProxy(VirtualFileSystem.class);
        this.shell = throwingProxy(VirtualShell.class);
    }

    /**
     * Creates an unavailable environment for the given failure.
     *
     * @param cause
     *            why the environment could not be resolved (must not be null)
     * @return the environment
     */
    public static ExecutionEnvironment of(Throwable cause) {
        Objects.requireNonNull(cause, "cause must not be null");
        final String message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        return new UnavailableExecutionEnvironment(message, cause);
    }

    /**
     * Creates an unavailable environment for a failure that has no exception, such as a missing provider.
     *
     * @param cause
     *            why the environment could not be resolved (must not be null)
     * @return the environment
     */
    public static ExecutionEnvironment of(String cause) {
        Objects.requireNonNull(cause, "cause must not be null");
        return new UnavailableExecutionEnvironment(cause, null);
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
        return EnvironmentDescriptor.unavailable(cause);
    }

    @Override
    public String stage(StagedResource resource) {
        throw unavailable();
    }

    private ExecutionEnvironmentUnavailableException unavailable() {
        return new ExecutionEnvironmentUnavailableException("Execution environment unavailable: " + cause, throwable);
    }

    private <T> T throwingProxy(Class<T> type) {
        final InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "toString" -> "Unavailable" + type.getSimpleName() + "[" + cause + "]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw unavailable();
        };
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    @Override
    public String toString() {
        return "UnavailableExecutionEnvironment[" + cause + "]";
    }
}
