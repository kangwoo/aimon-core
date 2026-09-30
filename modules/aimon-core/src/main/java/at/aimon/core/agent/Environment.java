package at.aimon.core.agent;

import java.time.ZoneId;
import java.util.Objects;

/**
 * The user- and application-level settings an agent runs under — today, the time zone.
 *
 * <p>
 * It used to describe the host as well (working directory, platform, OS version). Those moved to the execution's
 * {@code at.aimon.core.environment.EnvironmentDescriptor} (execution-environment design §10): they describe where the
 * execution's commands run, which is not necessarily the JVM host, and they are decided per execution rather than per
 * agent. Whether what remains keeps this name is an open question of that design (§14).
 *
 * <p>
 * Instances of this class are immutable and thread-safe.
 */
public final class Environment {

    private final ZoneId timeZone;

    private Environment(Builder builder) {
        timeZone = Objects.requireNonNull(builder.timeZone, "timeZone must not be null");
    }

    /**
     * Creates a new Environment with the system default time zone.
     *
     * @return Environment instance populated from the running system
     */
    public static Environment createDefault() {
        return builder().timeZone(ZoneId.systemDefault()).build();
    }

    /**
     * Gets the time zone.
     *
     * @return time zone
     */
    public ZoneId getTimeZone() {
        return timeZone;
    }

    /**
     * Creates a new builder instance.
     *
     * @return new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for Environment instances. */
    public static final class Builder {
        private ZoneId timeZone = ZoneId.systemDefault();

        private Builder() {
        }

        /**
         * Sets the time zone.
         *
         * @param timeZone
         *            time zone
         * @return this builder
         */
        public Builder timeZone(ZoneId timeZone) {
            this.timeZone = timeZone;
            return this;
        }

        /**
         * Builds the Environment instance.
         *
         * @return new Environment instance
         * @throws NullPointerException
         *             if the time zone is null
         */
        public Environment build() {
            return new Environment(this);
        }
    }

    @Override
    public String toString() {
        return "Environment{" + "timeZone=" + timeZone + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final Environment that = (Environment) o;
        return Objects.equals(timeZone, that.timeZone);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timeZone);
    }
}
