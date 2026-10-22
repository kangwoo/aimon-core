package at.aimon.session.routing.wake;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * What an {@link IdleSessionWaker} wakes a session for, and how often at most (design
 * {@code docs/design/session/idle-wake.md} §4).
 *
 * <p>
 * Immutable; thread-safe. The defaults wake for background monitor events only, at most {@value #DEFAULT_MAX_PER_HOUR}
 * times an hour and {@value #DEFAULT_MAX_CONSECUTIVE} times in a row without a turn of the user's in between.
 */
public final class WakePolicy {

    /** Queue entry kind of a background monitor's events ({@code MonitorManager.QUEUED_KIND}). */
    public static final String MONITOR_EVENTS = "monitor-event";

    /** Queue entry kind of a background subagent's completion notice ({@code SubagentTaskCompleted.QUEUED_KIND}). */
    public static final String SUBAGENT_COMPLETIONS = "subagent-task-completed";

    /** Default cap on wakes of one session within an hour. */
    public static final int DEFAULT_MAX_PER_HOUR = 6;

    /** Default cap on wakes of one session in a row without another turn. */
    public static final int DEFAULT_MAX_CONSECUTIVE = 3;

    private final Set<String> kinds;
    private final Duration debounce;
    private final int maxPerHour;
    private final int maxConsecutive;
    private final Duration retryDelay;

    private WakePolicy(Builder builder) {
        if (builder.kinds.isEmpty()) {
            throw new IllegalArgumentException("kinds must not be empty");
        }
        this.kinds = Set.copyOf(builder.kinds);
        this.debounce = builder.debounce;
        this.maxPerHour = builder.maxPerHour;
        this.maxConsecutive = builder.maxConsecutive;
        this.retryDelay = builder.retryDelay;
    }

    /**
     * Creates a builder with the defaults.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return the policy with every default
     */
    public static WakePolicy defaults() {
        return builder().build();
    }

    /**
     * @return the queue entry kinds ({@code metadata.kind}) that wake an idle session (never empty)
     */
    public Set<String> getKinds() {
        return kinds;
    }

    /**
     * @return how long entries are gathered before one turn is opened for them
     */
    public Duration getDebounce() {
        return debounce;
    }

    /**
     * @return the most wakes of one session within an hour
     */
    public int getMaxPerHour() {
        return maxPerHour;
    }

    /**
     * @return the most wakes of one session in a row without another turn in between
     */
    public int getMaxConsecutive() {
        return maxConsecutive;
    }

    /**
     * @return how long a session waits before it is tried again after a submit failed
     */
    public Duration getRetryDelay() {
        return retryDelay;
    }

    @Override
    public String toString() {
        return "WakePolicy{kinds=" + kinds + ", debounce=" + debounce + ", maxPerHour=" + maxPerHour
                + ", maxConsecutive=" + maxConsecutive + '}';
    }

    /** Builder for {@link WakePolicy}. */
    public static final class Builder {

        private final Set<String> kinds = new LinkedHashSet<>(Set.of(MONITOR_EVENTS));
        private Duration debounce = Duration.ofSeconds(2);
        private int maxPerHour = DEFAULT_MAX_PER_HOUR;
        private int maxConsecutive = DEFAULT_MAX_CONSECUTIVE;
        private Duration retryDelay = Duration.ofSeconds(30);

        private Builder() {
        }

        /**
         * Replaces the kinds that wake a session (default: {@link #MONITOR_EVENTS}).
         *
         * @param kinds
         *            the queue entry kinds (must not be null or empty)
         * @return this builder
         */
        public Builder kinds(Set<String> kinds) {
            Objects.requireNonNull(kinds, "kinds must not be null");
            this.kinds.clear();
            this.kinds.addAll(kinds);
            return this;
        }

        /**
         * Adds a kind that wakes a session — {@link #SUBAGENT_COMPLETIONS}, typically.
         *
         * @param kind
         *            the queue entry kind (must not be blank)
         * @return this builder
         */
        public Builder kind(String kind) {
            Objects.requireNonNull(kind, "kind must not be null");
            if (kind.isBlank()) {
                throw new IllegalArgumentException("kind must not be blank");
            }
            kinds.add(kind);
            return this;
        }

        /**
         * @param debounce
         *            how long entries are gathered into one turn (default 2s; must not be negative)
         * @return this builder
         */
        public Builder debounce(Duration debounce) {
            Objects.requireNonNull(debounce, "debounce must not be null");
            if (debounce.isNegative()) {
                throw new IllegalArgumentException("debounce must not be negative");
            }
            this.debounce = debounce;
            return this;
        }

        /**
         * @param maxPerHour
         *            the most wakes of one session within an hour (default 6; must be positive)
         * @return this builder
         */
        public Builder maxPerHour(int maxPerHour) {
            this.maxPerHour = positive(maxPerHour, "maxPerHour");
            return this;
        }

        /**
         * @param maxConsecutive
         *            the most wakes in a row without another turn (default 3; must be positive)
         * @return this builder
         */
        public Builder maxConsecutive(int maxConsecutive) {
            this.maxConsecutive = positive(maxConsecutive, "maxConsecutive");
            return this;
        }

        /**
         * @param retryDelay
         *            the wait before a session is tried again after a submit failed (default 30s; must be positive)
         * @return this builder
         */
        public Builder retryDelay(Duration retryDelay) {
            Objects.requireNonNull(retryDelay, "retryDelay must not be null");
            if (retryDelay.isZero() || retryDelay.isNegative()) {
                throw new IllegalArgumentException("retryDelay must be positive");
            }
            this.retryDelay = retryDelay;
            return this;
        }

        /**
         * @return the policy
         * @throws IllegalArgumentException
         *             if no kind is left
         */
        public WakePolicy build() {
            return new WakePolicy(this);
        }

        private static int positive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }
    }
}
