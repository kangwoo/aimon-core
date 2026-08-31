package at.aimon.core.hook.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import at.aimon.core.hook.execution.HookExecutionPolicy.TimeoutBehavior;

class HookExecutionPolicyTest {

    @Test
    void defaultsAreThirtySecondsAndFailOpen() {
        final HookExecutionPolicy policy = HookExecutionPolicy.continueOnExceptionAndNeverStop();
        assertThat(policy.timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.timeoutBehavior()).isEqualTo(TimeoutBehavior.FAIL_OPEN);
        assertThat(policy.stopOnBlocked()).isFalse();
    }

    @Test
    void continueOnExceptionButStopOnBlockedDefaultsAreFailOpen() {
        final HookExecutionPolicy policy = HookExecutionPolicy.continueOnExceptionButStopOnBlocked();
        assertThat(policy.timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.timeoutBehavior()).isEqualTo(TimeoutBehavior.FAIL_OPEN);
        assertThat(policy.stopOnBlocked()).isTrue();
    }

    @Test
    void failClosedPolicyUsesFailClosedTimeoutBehavior() {
        final HookExecutionPolicy policy = HookExecutionPolicy.failClosedStopOnBlocked();
        assertThat(policy.timeoutBehavior()).isEqualTo(TimeoutBehavior.FAIL_CLOSED);
        assertThat(policy.stopOnBlocked()).isTrue();
    }

    @Test
    void withTimeoutPreservesOtherFields() {
        final HookExecutionPolicy base = HookExecutionPolicy.continueOnExceptionButStopOnBlocked();
        final HookExecutionPolicy withT = base.withTimeout(Duration.ofMillis(500));

        assertThat(withT.timeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(withT.stopOnBlocked()).isTrue();
        assertThat(withT.timeoutBehavior()).isEqualTo(TimeoutBehavior.FAIL_OPEN);
    }

    @Test
    void withTimeoutBehaviorPreservesOtherFields() {
        final HookExecutionPolicy base = HookExecutionPolicy.continueOnExceptionAndNeverStop();
        final HookExecutionPolicy strict = base.withTimeoutBehavior(TimeoutBehavior.FAIL_CLOSED);

        assertThat(strict.timeoutBehavior()).isEqualTo(TimeoutBehavior.FAIL_CLOSED);
        assertThat(strict.stopOnBlocked()).isFalse();
        assertThat(strict.timeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void zeroOrNegativeTimeoutRejected() {
        assertThatThrownBy(() -> new HookExecutionPolicy(false, e -> HookResult.success(), Duration.ZERO,
                TimeoutBehavior.FAIL_OPEN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HookExecutionPolicy(false, e -> HookResult.success(), Duration.ofMillis(-1),
                TimeoutBehavior.FAIL_OPEN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullTimeoutOrBehaviorRejected() {
        assertThatThrownBy(
                () -> new HookExecutionPolicy(false, e -> HookResult.success(), null, TimeoutBehavior.FAIL_OPEN))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HookExecutionPolicy(false, e -> HookResult.success(), Duration.ofSeconds(1), null))
                .isInstanceOf(NullPointerException.class);
    }

    // ==== timeoutFor — a hook-declared budget widens the outer net (C-4) ====

    /** A hook whose declared budget is whatever the test needs it to be. */
    private static ExecutionHook<HookContext> hookDeclaring(Duration budget) {
        return new ExecutionHook<>() {
            @Override
            public HookResult execute(HookContext context) {
                return HookResult.success();
            }

            @Override
            public java.util.Optional<Duration> getExecutionBudget() {
                return java.util.Optional.ofNullable(budget);
            }
        };
    }

    @Test
    void timeoutForFallsBackToThePolicyTimeoutWhenTheHookDeclaresNothing() {
        final HookExecutionPolicy policy = HookExecutionPolicy.continueOnExceptionAndNeverStop();

        assertThat(policy.timeoutFor(hookDeclaring(null))).isEqualTo(policy.timeout());
        // A plain lambda hook takes the interface default (empty) — the common case must not change behaviour.
        assertThat(policy.timeoutFor(ctx -> HookResult.success())).isEqualTo(policy.timeout());
    }

    @Test
    void timeoutForWidensTheNetForAHookThatDeclaresALongerBudget() {
        final HookExecutionPolicy policy = HookExecutionPolicy.continueOnExceptionAndNeverStop();

        // A 60s handler under the 30s default would otherwise be cut off at 30s, losing its own graceful timeout.
        assertThat(policy.timeoutFor(hookDeclaring(Duration.ofSeconds(60))))
                .isEqualTo(Duration.ofSeconds(60).plus(HookExecutionPolicy.DECLARED_BUDGET_GRACE));
    }

    @Test
    void timeoutForIgnoresABudgetThatIsShorterThanThePolicyTimeout() {
        final HookExecutionPolicy policy = HookExecutionPolicy.continueOnExceptionAndNeverStop();

        // Narrowing the net would only race the hook's own deadline without ever finishing sooner.
        assertThat(policy.timeoutFor(hookDeclaring(Duration.ofSeconds(5)))).isEqualTo(policy.timeout());
    }

    /**
     * Regression guard for the {@code <=} → {@code <} fix. {@code ShellAction.DEFAULT_TIMEOUT} and
     * {@link HookExecutionPolicy#DEFAULT_TIMEOUT} are both 30s, so a declarative shell hook that omits
     * {@code timeoutMs} declares exactly the policy timeout — the most common configuration there is. Without the
     * grace, the outer net would fire at the same instant as the hook's own deadline and swallow its graceful result.
     */
    @Test
    void timeoutForGrantsTheGraceWhenTheDeclaredBudgetEqualsThePolicyTimeout() {
        final HookExecutionPolicy policy = HookExecutionPolicy.continueOnExceptionAndNeverStop();

        assertThat(policy.timeoutFor(hookDeclaring(policy.timeout())))
                .isEqualTo(policy.timeout().plus(HookExecutionPolicy.DECLARED_BUDGET_GRACE));
    }

    @Test
    void timeoutForTreatsZeroOrNegativeBudgetAsNothingDeclared() {
        final HookExecutionPolicy policy = HookExecutionPolicy.continueOnExceptionAndNeverStop();

        // A zero budget is not a meaningful floor, and a negative one is nonsense — both fall back to the policy.
        assertThat(policy.timeoutFor(hookDeclaring(Duration.ZERO))).isEqualTo(policy.timeout());
        assertThat(policy.timeoutFor(hookDeclaring(Duration.ofSeconds(-10)))).isEqualTo(policy.timeout());
    }

    @Test
    void timeoutForClampsABudgetAboveTheMaximum() {
        final HookExecutionPolicy policy = HookExecutionPolicy.continueOnExceptionAndNeverStop();

        // A config-supplied budget must not be able to stall a turn for an hour.
        assertThat(policy.timeoutFor(hookDeclaring(Duration.ofHours(1))))
                .isEqualTo(HookExecutionPolicy.MAX_DECLARED_BUDGET.plus(HookExecutionPolicy.DECLARED_BUDGET_GRACE));
    }

    @Test
    void timeoutForDoesNotOverflowOnAnAbsurdBudget() {
        final HookExecutionPolicy policy = HookExecutionPolicy.continueOnExceptionAndNeverStop();

        // Duration.plus / toNanos would blow up on this; clamping must happen first.
        final Duration effective = policy.timeoutFor(hookDeclaring(Duration.ofMillis(Long.MAX_VALUE)));

        assertThat(effective)
                .isEqualTo(HookExecutionPolicy.MAX_DECLARED_BUDGET.plus(HookExecutionPolicy.DECLARED_BUDGET_GRACE));
        assertThat(effective.toNanos()).isPositive();
    }

    @Test
    void timeoutForTracksTheCurrentPolicyTimeout() {
        final HookExecutionPolicy tight = HookExecutionPolicy.continueOnExceptionAndNeverStop()
                .withTimeout(Duration.ofSeconds(2));

        // Same 5s budget: masked under the 30s default, honoured once the policy tightens below it.
        assertThat(tight.timeoutFor(hookDeclaring(Duration.ofSeconds(5))))
                .isEqualTo(Duration.ofSeconds(5).plus(HookExecutionPolicy.DECLARED_BUDGET_GRACE));
    }

    @Test
    void timeoutForRejectsNullHook() {
        assertThatThrownBy(() -> HookExecutionPolicy.continueOnExceptionAndNeverStop().timeoutFor(null))
                .isInstanceOf(NullPointerException.class);
    }
}
