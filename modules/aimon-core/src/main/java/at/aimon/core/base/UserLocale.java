package at.aimon.core.base;

import java.time.ZoneId;
import java.util.Objects;

/**
 * The user- and application-side settings an agent runs under — today, the time zone.
 *
 * <p>
 * This is not where commands run. The working directory, platform and OS version belong to the execution's
 * {@code at.aimon.core.environment.EnvironmentDescriptor}, and the place itself is the
 * {@code at.aimon.core.environment.ExecutionEnvironment}. This type replaced {@code at.aimon.core.agent.Environment},
 * which carried both until only the time zone was left (execution-environment design §10, §14).
 *
 * <p>
 * <b>Not {@link java.util.Locale}.</b> A {@code Locale} is language and regional formatting and holds no time zone.
 * This type is the broader "how the user reads time and text", of which only the time zone is carried so far.
 *
 * <p>
 * <b>The name says whose property it is, not how many there are.</b> It is a value object, so its lifetime is that of
 * whatever holds it: today the agent runtime builds one when it is assembled, from the JVM's default time zone. On a
 * multi-user server that is the application's time zone rather than each user's — nothing supplies a per-user value
 * yet.
 *
 * <p>
 * Instances of this class are immutable and thread-safe.
 */
public final class UserLocale {

    private final ZoneId timeZone;

    private UserLocale(Builder builder) {
        timeZone = Objects.requireNonNull(builder.timeZone, "timeZone must not be null");
    }

    /**
     * Creates a new UserLocale with the system default time zone.
     *
     * @return UserLocale instance populated from the running system
     */
    public static UserLocale createDefault() {
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

    /** Builder for UserLocale instances. */
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
         * Builds the UserLocale instance.
         *
         * @return new UserLocale instance
         * @throws NullPointerException
         *             if the time zone is null
         */
        public UserLocale build() {
            return new UserLocale(this);
        }
    }

    @Override
    public String toString() {
        return "UserLocale{" + "timeZone=" + timeZone + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final UserLocale that = (UserLocale) o;
        return Objects.equals(timeZone, that.timeZone);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timeZone);
    }
}
