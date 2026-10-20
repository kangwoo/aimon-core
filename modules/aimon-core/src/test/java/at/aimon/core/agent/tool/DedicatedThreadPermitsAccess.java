package at.aimon.core.agent.tool;

/** Test-only window onto {@link DedicatedThreadPermits}' package-private counters, for tests in other packages. */
public final class DedicatedThreadPermitsAccess {

    private DedicatedThreadPermitsAccess() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * @param permits
     *            the permit set
     * @return the number of permits currently free
     */
    public static int availablePermits(DedicatedThreadPermits permits) {
        return permits.availablePermits();
    }
}
