package at.aimon.core.filesystem;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable status of a storage backend. Thread-safe representation of backend connectivity state.
 */
public final class BackendStatus {
    /** 연결된 상태의 BackendStatus를 생성한다. */
    public static BackendStatus connected(BackendType type) {
        return new BackendStatus(type, ConnectionState.CONNECTED, Instant.now(), null);
    }

    /** 연결 해제된 상태의 BackendStatus를 생성한다. */
    public static BackendStatus disconnected(BackendType type, String errorMessage) {
        return new BackendStatus(type, ConnectionState.DISCONNECTED, Instant.now(), errorMessage);
    }

    /** 알 수 없는 상태의 BackendStatus를 생성한다. */
    public static BackendStatus unknown(BackendType type) {
        return new BackendStatus(type, ConnectionState.UNKNOWN, Instant.now(), null);
    }

    private final BackendType type;
    private final ConnectionState state;
    private final Instant lastChecked;
    private final String errorMessage;

    /** BackendStatus를 생성한다. */
    public BackendStatus(BackendType type, ConnectionState state, Instant lastChecked, String errorMessage) {
        this.type = Objects.requireNonNull(type, "BackendType cannot be null");
        this.state = Objects.requireNonNull(state, "ConnectionState cannot be null");
        this.lastChecked = Objects.requireNonNull(lastChecked, "LastChecked cannot be null");
        this.errorMessage = errorMessage;
    }

    public BackendType getType() {
        return type;
    }

    public ConnectionState getState() {
        return state;
    }

    public Instant getLastChecked() {
        return lastChecked;
    }

    public Optional<String> getErrorMessage() {
        return Optional.ofNullable(errorMessage);
    }

    public boolean isAvailable() {
        return state == ConnectionState.CONNECTED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final BackendStatus that = (BackendStatus) o;
        return Objects.equals(type, that.type) && state == that.state && Objects.equals(lastChecked, that.lastChecked)
                && Objects.equals(errorMessage, that.errorMessage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, state, lastChecked, errorMessage);
    }

    @Override
    public String toString() {
        return "BackendStatus{" + "type=" + type + ", state=" + state + ", lastChecked=" + lastChecked
                + ", errorMessage='" + errorMessage + '\'' + '}';
    }

    public enum ConnectionState {
        CONNECTED, // Backend is available and operational
        DISCONNECTED, // Backend is not available
        DEGRADED, // Backend is available but experiencing issues
        UNKNOWN // Backend status not yet determined
    }
}
