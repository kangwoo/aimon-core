package at.aimon.core.skill.hook.declarative.predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.skill.hook.declarative.ToolInputPredicate;

class CompositePredicateTest {

    private static final ToolInputPredicate TRUE = (n, i) -> true;
    private static final ToolInputPredicate FALSE = (n, i) -> false;

    @Test
    void or_anyTrue_returnsTrue() {
        ToolInputPredicate p = CompositePredicate.or(FALSE, TRUE, FALSE);
        assertThat(p.test("X", ToolInput.of())).isTrue();
    }

    @Test
    void or_allFalse_returnsFalse() {
        ToolInputPredicate p = CompositePredicate.or(FALSE, FALSE);
        assertThat(p.test("X", ToolInput.of())).isFalse();
    }

    @Test
    void or_shortCircuits() {
        boolean[] called = {false};
        ToolInputPredicate observer = (n, i) -> {
            called[0] = true;
            return true;
        };
        ToolInputPredicate p = CompositePredicate.or(TRUE, observer);
        p.test("X", ToolInput.of());
        assertThat(called[0]).isFalse();
    }

    @Test
    void and_allTrue_returnsTrue() {
        ToolInputPredicate p = CompositePredicate.and(TRUE, TRUE);
        assertThat(p.test("X", ToolInput.of())).isTrue();
    }

    @Test
    void and_anyFalse_returnsFalse() {
        ToolInputPredicate p = CompositePredicate.and(TRUE, FALSE, TRUE);
        assertThat(p.test("X", ToolInput.of())).isFalse();
    }

    @Test
    void and_shortCircuits() {
        boolean[] called = {false};
        ToolInputPredicate observer = (n, i) -> {
            called[0] = true;
            return true;
        };
        ToolInputPredicate p = CompositePredicate.and(FALSE, observer);
        p.test("X", ToolInput.of());
        assertThat(called[0]).isFalse();
    }

    @Test
    void singleDelegate_returnedUnchanged() {
        ToolInputPredicate single = NameOnlyPredicate.of("Bash");
        assertThat(CompositePredicate.or(single)).isSameAs(single);
        assertThat(CompositePredicate.and(single)).isSameAs(single);
    }

    @Test
    void emptyDelegates_throws() {
        assertThatThrownBy(() -> CompositePredicate.or()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CompositePredicate.and()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullDelegate_throws() {
        assertThatThrownBy(() -> CompositePredicate.or(TRUE, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CompositePredicate.and(TRUE, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void or_combinesRealPredicates() {
        ToolInputPredicate p = CompositePredicate.or(BashSubcommandPredicate.of("git *"),
                BashSubcommandPredicate.of("npm install"));

        assertThat(p.test("Bash", ToolInput.of(Map.of("command", "git status")))).isTrue();
        assertThat(p.test("Bash", ToolInput.of(Map.of("command", "npm install")))).isTrue();
        assertThat(p.test("Bash", ToolInput.of(Map.of("command", "ls")))).isFalse();
    }

    @Test
    void getters_exposeStructure() {
        ToolInputPredicate a = NameOnlyPredicate.of("Bash");
        ToolInputPredicate b = NameOnlyPredicate.of("Read");
        CompositePredicate p = (CompositePredicate) CompositePredicate.or(a, b);

        assertThat(p.getOp()).isEqualTo(CompositePredicate.Op.OR);
        assertThat(p.getDelegates()).containsExactly(a, b);
    }
}
