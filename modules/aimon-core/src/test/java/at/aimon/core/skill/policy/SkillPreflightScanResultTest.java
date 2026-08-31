package at.aimon.core.skill.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.skill.policy.pending.PendingSkillRequest;

/** Unit tests for {@link SkillPreflightScanResult}. */
class SkillPreflightScanResultTest {

    @Test
    void proceedReturnsSingletonAndIsNotSuspend() {
        final SkillPreflightScanResult a = SkillPreflightScanResult.proceed();
        final SkillPreflightScanResult b = SkillPreflightScanResult.proceed();

        assertThat(a).isSameAs(b);
        assertThat(a.shouldSuspend()).isFalse();
        assertThat(a.getPendingSkills()).isEmpty();
    }

    @Test
    void suspendCarriesPendingList() {
        final PendingSkillRequest req = PendingSkillRequest.builder().skillName("commit").build();
        final SkillPreflightScanResult result = SkillPreflightScanResult.suspend(List.of(req));

        assertThat(result.shouldSuspend()).isTrue();
        assertThat(result.getPendingSkills()).containsExactly(req);
    }

    @Test
    void suspendDefensivelyCopiesList() {
        final List<PendingSkillRequest> mutable = new ArrayList<>();
        mutable.add(PendingSkillRequest.builder().skillName("a").build());
        final SkillPreflightScanResult result = SkillPreflightScanResult.suspend(mutable);

        mutable.add(PendingSkillRequest.builder().skillName("b").build());

        assertThat(result.getPendingSkills()).hasSize(1);
        assertThatThrownBy(() -> result.getPendingSkills().add(PendingSkillRequest.builder().skillName("c").build()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void suspendRequiresNonEmptyList() {
        assertThatThrownBy(() -> SkillPreflightScanResult.suspend(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void suspendRejectsNull() {
        assertThatThrownBy(() -> SkillPreflightScanResult.suspend(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalityAndHashCodeBasedOnPendingSkills() {
        final PendingSkillRequest req = PendingSkillRequest.builder().skillName("commit").build();
        final SkillPreflightScanResult a = SkillPreflightScanResult.suspend(List.of(req));
        final SkillPreflightScanResult b = SkillPreflightScanResult.suspend(List.of(req));
        final SkillPreflightScanResult c = SkillPreflightScanResult
                .suspend(List.of(PendingSkillRequest.builder().skillName("deploy").build()));

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(SkillPreflightScanResult.proceed()).isEqualTo(SkillPreflightScanResult.proceed());
        assertThat(SkillPreflightScanResult.proceed()).isNotEqualTo(a);
    }

    @Test
    void toStringDistinguishesProceedFromSuspend() {
        assertThat(SkillPreflightScanResult.proceed().toString()).contains("proceed");
        assertThat(SkillPreflightScanResult.suspend(List.of(PendingSkillRequest.builder().skillName("a").build()))
                .toString()).contains("suspend");
    }
}
