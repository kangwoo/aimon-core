package at.aimon.core.scheduling.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.exception.QuotaExceededException;

class DefaultTaskQuotaManagerTest {

    private DefaultTaskQuotaManager manager;
    private Principal alice;
    private Principal bob;

    @BeforeEach
    void setUp() {
        manager = new DefaultTaskQuotaManager(3);
        alice = Principal.user("alice", "Alice");
        bob = Principal.user("bob", "Bob");
    }

    @Test
    void rejectsNonPositiveDefaultQuota() {
        assertThatThrownBy(() -> new DefaultTaskQuotaManager(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefaultTaskQuotaManager(-5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void initialUsageIsZero() {
        assertThat(manager.getCurrentUsage(alice)).isZero();
    }

    @Test
    void incrementUsageRaisesCounter() {
        manager.incrementUsage(alice);
        manager.incrementUsage(alice);

        assertThat(manager.getCurrentUsage(alice)).isEqualTo(2);
        assertThat(manager.getCurrentUsage(bob)).isZero();
    }

    @Test
    void decrementUsageLowersCounter() {
        manager.incrementUsage(alice);
        manager.incrementUsage(alice);

        manager.decrementUsage(alice);

        assertThat(manager.getCurrentUsage(alice)).isEqualTo(1);
    }

    @Test
    void decrementUsageClampsAtZero() {
        manager.decrementUsage(alice);
        assertThat(manager.getCurrentUsage(alice)).isZero();

        manager.incrementUsage(alice);
        manager.decrementUsage(alice);
        manager.decrementUsage(alice);
        assertThat(manager.getCurrentUsage(alice)).isZero();
    }

    @Test
    void getMaxQuotaFallsBackToDefault() {
        assertThat(manager.getMaxQuota(alice)).isEqualTo(3);
    }

    @Test
    void setCustomQuotaOverridesDefault() {
        manager.setCustomQuota(alice, 10);

        assertThat(manager.getMaxQuota(alice)).isEqualTo(10);
        assertThat(manager.getMaxQuota(bob)).isEqualTo(3);
    }

    @Test
    void setCustomQuotaRejectsNonPositive() {
        assertThatThrownBy(() -> manager.setCustomQuota(alice, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.setCustomQuota(alice, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removeCustomQuotaRevertsToDefault() {
        manager.setCustomQuota(alice, 10);
        manager.removeCustomQuota(alice);

        assertThat(manager.getMaxQuota(alice)).isEqualTo(3);
    }

    @Test
    void checkQuotaPassesUnderLimit() {
        manager.incrementUsage(alice);
        manager.incrementUsage(alice);

        assertThatCode(() -> manager.checkQuota(alice)).doesNotThrowAnyException();
    }

    @Test
    void checkQuotaThrowsAtLimit() {
        manager.incrementUsage(alice);
        manager.incrementUsage(alice);
        manager.incrementUsage(alice);

        assertThatThrownBy(() -> manager.checkQuota(alice)).isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void checkQuotaUsesCustomQuotaWhenSet() {
        manager.setCustomQuota(alice, 1);
        manager.incrementUsage(alice);

        assertThatThrownBy(() -> manager.checkQuota(alice)).isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void resetAllUsageClearsCounters() {
        manager.incrementUsage(alice);
        manager.incrementUsage(bob);

        manager.resetAllUsage();

        assertThat(manager.getCurrentUsage(alice)).isZero();
        assertThat(manager.getCurrentUsage(bob)).isZero();
    }

    @Test
    void usageIsScopedPerPrincipalKey() {
        Principal aliceAgain = Principal.user("alice", "Different Display Name");
        manager.incrementUsage(alice);

        // Same type:id key — counter is shared
        assertThat(manager.getCurrentUsage(aliceAgain)).isEqualTo(1);
    }

    @Test
    void publicMethodsRejectNullPrincipal() {
        assertThatThrownBy(() -> manager.checkQuota(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> manager.incrementUsage(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> manager.decrementUsage(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> manager.getCurrentUsage(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> manager.getMaxQuota(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> manager.setCustomQuota(null, 5)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> manager.removeCustomQuota(null)).isInstanceOf(NullPointerException.class);
    }
}
