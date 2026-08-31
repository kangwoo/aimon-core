package at.aimon.core.skill;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link InvokePolicy}. */
class InvokePolicyTest {

    @Test
    void defaults_AreUserFalseModelTrue() {
        final InvokePolicy policy = InvokePolicy.defaults();

        assertThat(policy.isUserInvocable()).isFalse();
        assertThat(policy.isModelInvocable()).isTrue();
    }

    @Test
    void of_AllCombinations() {
        assertThat(InvokePolicy.of(false, false)).satisfies(p -> assertThat(p.isUserInvocable()).isFalse())
                .satisfies(p -> assertThat(p.isModelInvocable()).isFalse());
        assertThat(InvokePolicy.of(true, false)).satisfies(p -> assertThat(p.isUserInvocable()).isTrue())
                .satisfies(p -> assertThat(p.isModelInvocable()).isFalse());
        assertThat(InvokePolicy.of(true, true)).satisfies(p -> assertThat(p.isUserInvocable()).isTrue())
                .satisfies(p -> assertThat(p.isModelInvocable()).isTrue());
    }

    @Test
    void of_DefaultArguments_ReturnsSharedDefaultInstance() {
        // Implementation detail: we deduplicate the default instance — verify the contract holds.
        assertThat(InvokePolicy.of(false, true)).isSameAs(InvokePolicy.defaults());
    }

    @Test
    void equalsAndHashCode_ConsiderBothFlags() {
        final InvokePolicy a = InvokePolicy.of(true, false);
        final InvokePolicy b = InvokePolicy.of(true, false);
        final InvokePolicy c = InvokePolicy.of(true, true);
        final InvokePolicy d = InvokePolicy.of(false, false);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a).isNotEqualTo(d);
    }

    @Test
    void toString_ContainsBothFlags() {
        assertThat(InvokePolicy.of(true, false).toString()).contains("user=true").contains("model=false");
    }
}
