package at.aimon.core.shell;

/** The inert {@link ShellCancellation}: never cancelled, keeps no listener. */
final class NoopShellCancellation implements ShellCancellation {

    static final NoopShellCancellation INSTANCE = new NoopShellCancellation();

    private NoopShellCancellation() {
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public Registration onCancel(Runnable listener) {
        return () -> {
        };
    }

    @Override
    public String toString() {
        return "ShellCancellation.none()";
    }
}
