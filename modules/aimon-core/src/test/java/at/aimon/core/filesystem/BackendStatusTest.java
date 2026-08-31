package at.aimon.core.filesystem;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class BackendStatusTest {

    @Test
    void testConnectedStatus() {
        BackendStatus status = BackendStatus.connected(BackendType.LOCAL);

        assertThat(status.getType()).isEqualTo(BackendType.LOCAL);
        assertThat(status.getState()).isEqualTo(BackendStatus.ConnectionState.CONNECTED);
        assertThat(status.isAvailable()).isTrue();
        assertThat(status.getErrorMessage()).isEmpty();
        assertThat(status.getLastChecked()).isNotNull();
    }

    @Test
    void testDisconnectedStatus() {
        BackendStatus status = BackendStatus.disconnected(BackendType.GRIDFS, "Connection timeout");

        assertThat(status.getType()).isEqualTo(BackendType.GRIDFS);
        assertThat(status.getState()).isEqualTo(BackendStatus.ConnectionState.DISCONNECTED);
        assertThat(status.isAvailable()).isFalse();
        assertThat(status.getErrorMessage()).hasValue("Connection timeout");
    }

    @Test
    void testUnknownStatus() {
        BackendStatus status = BackendStatus.unknown(BackendType.S3);

        assertThat(status.getType()).isEqualTo(BackendType.S3);
        assertThat(status.getState()).isEqualTo(BackendStatus.ConnectionState.UNKNOWN);
        assertThat(status.isAvailable()).isFalse();
        assertThat(status.getErrorMessage()).isEmpty();
    }

    @Test
    void testConstructorWithAllParameters() {
        Instant now = Instant.now();
        BackendStatus status = new BackendStatus(BackendType.LOCAL, BackendStatus.ConnectionState.DEGRADED, now,
                "Slow response");

        assertThat(status.getType()).isEqualTo(BackendType.LOCAL);
        assertThat(status.getState()).isEqualTo(BackendStatus.ConnectionState.DEGRADED);
        assertThat(status.getLastChecked()).isEqualTo(now);
        assertThat(status.getErrorMessage()).hasValue("Slow response");
        assertThat(status.isAvailable()).isFalse();
    }

    @Test
    void testConstructorRejectsNullType() {
        assertThatThrownBy(() -> new BackendStatus(null, BackendStatus.ConnectionState.CONNECTED, Instant.now(), null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("BackendType cannot be null");
    }

    @Test
    void testConstructorRejectsNullState() {
        assertThatThrownBy(() -> new BackendStatus(BackendType.LOCAL, null, Instant.now(), null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("ConnectionState cannot be null");
    }

    @Test
    void testConstructorRejectsNullLastChecked() {
        assertThatThrownBy(
                () -> new BackendStatus(BackendType.LOCAL, BackendStatus.ConnectionState.CONNECTED, null, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("LastChecked cannot be null");
    }

    @Test
    void testIsAvailableOnlyForConnected() {
        assertThat(BackendStatus.connected(BackendType.LOCAL).isAvailable()).isTrue();
        assertThat(BackendStatus.disconnected(BackendType.LOCAL, "error").isAvailable()).isFalse();
        assertThat(BackendStatus.unknown(BackendType.LOCAL).isAvailable()).isFalse();

        BackendStatus degraded = new BackendStatus(BackendType.LOCAL, BackendStatus.ConnectionState.DEGRADED,
                Instant.now(), null);
        assertThat(degraded.isAvailable()).isFalse();
    }

    @Test
    void testEqualsAndHashCode() {
        Instant now = Instant.now();
        BackendStatus status1 = new BackendStatus(BackendType.LOCAL, BackendStatus.ConnectionState.CONNECTED, now,
                null);
        BackendStatus status2 = new BackendStatus(BackendType.LOCAL, BackendStatus.ConnectionState.CONNECTED, now,
                null);

        assertThat(status1).isEqualTo(status2);
        assertThat(status1.hashCode()).isEqualTo(status2.hashCode());
    }

    @Test
    void testToString() {
        BackendStatus status = BackendStatus.connected(BackendType.LOCAL);
        String toString = status.toString();

        assertThat(toString).contains("BackendStatus").contains("LOCAL").contains("CONNECTED");
    }
}
