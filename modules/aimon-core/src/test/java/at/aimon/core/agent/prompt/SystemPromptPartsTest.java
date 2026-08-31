package at.aimon.core.agent.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SystemPromptParts Tests")
class SystemPromptPartsTest {

    private static SystemPromptPart part(String content, String kind) {
        return SystemPromptPart.builder().content(content).staticness(Staticness.STATIC).kind(kind).build();
    }

    @Test
    @DisplayName("Should reject null list input")
    void of_null_throwsNPE() {
        assertThatThrownBy(() -> SystemPromptParts.of(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Parts cannot be null");
    }

    @Test
    @DisplayName("Should reject null element in list")
    void of_listWithNullElement_throwsNPE() {
        List<SystemPromptPart> input = Arrays.asList(part("a", "k"), null);
        assertThatThrownBy(() -> SystemPromptParts.of(input)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Part cannot be null");
    }

    @Test
    @DisplayName("Should return unmodifiable list from parts()")
    void parts_isUnmodifiable() {
        SystemPromptParts parts = SystemPromptParts.of(List.of(part("a", "k1")));

        List<SystemPromptPart> returned = parts.parts();

        assertThatThrownBy(() -> returned.add(part("b", "k2"))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> returned.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should defensively copy input list — mutation after of() does not leak in")
    void of_defensiveCopy() {
        List<SystemPromptPart> input = new ArrayList<>();
        input.add(part("a", "k1"));

        SystemPromptParts parts = SystemPromptParts.of(input);

        // Mutate original list after construction.
        input.add(part("b", "k2"));

        assertThat(parts.size()).isEqualTo(1);
        assertThat(parts.parts()).hasSize(1);
    }

    @Test
    @DisplayName("empty() should return a zero-size container")
    void empty_returnsEmpty() {
        SystemPromptParts empty = SystemPromptParts.empty();

        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.size()).isZero();
        assertThat(empty.parts()).isEmpty();
    }

    @Test
    @DisplayName("concatenated() should join multiple parts with \\n\\n separator")
    void concatenated_multipleParts() {
        SystemPromptParts parts = SystemPromptParts
                .of(List.of(part("first", "k1"), part("second", "k2"), part("third", "k3")));

        assertThat(parts.concatenated()).isEqualTo("first\n\nsecond\n\nthird");
    }

    @Test
    @DisplayName("concatenated() on empty container should return empty string")
    void concatenated_empty() {
        assertThat(SystemPromptParts.empty().concatenated()).isEmpty();
    }

    @Test
    @DisplayName("concatenated() with single part should return its content")
    void concatenated_singlePart() {
        SystemPromptParts parts = SystemPromptParts.of(List.of(part("only", "k1")));

        assertThat(parts.concatenated()).isEqualTo("only");
    }

    @Test
    @DisplayName("append() should return new instance and leave original unchanged")
    void append_returnsNewInstance() {
        SystemPromptParts original = SystemPromptParts.of(List.of(part("a", "k1")));

        SystemPromptParts appended = original.append(part("b", "k2"));

        assertThat(appended).isNotSameAs(original);
        assertThat(original.size()).isEqualTo(1);
        assertThat(appended.size()).isEqualTo(2);
        assertThat(appended.parts()).containsExactly(part("a", "k1"), part("b", "k2"));
    }

    @Test
    @DisplayName("append() should reject null part")
    void append_null_throwsNPE() {
        SystemPromptParts original = SystemPromptParts.empty();

        assertThatThrownBy(() -> original.append(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Part cannot be null");
    }

    @Test
    @DisplayName("append() on empty should produce single-element container")
    void append_onEmpty() {
        SystemPromptParts result = SystemPromptParts.empty().append(part("a", "k1"));

        assertThat(result.size()).isEqualTo(1);
        assertThat(result.concatenated()).isEqualTo("a");
    }

    @Test
    @DisplayName("isEmpty / size should reflect contents")
    void isEmptyAndSize() {
        SystemPromptParts empty = SystemPromptParts.empty();
        SystemPromptParts two = SystemPromptParts.of(List.of(part("a", "k1"), part("b", "k2")));

        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.size()).isZero();
        assertThat(two.isEmpty()).isFalse();
        assertThat(two.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should preserve order of parts")
    void ordering() {
        SystemPromptPart a = part("a", "k1");
        SystemPromptPart b = part("b", "k2");
        SystemPromptPart c = part("c", "k3");

        SystemPromptParts parts = SystemPromptParts.of(List.of(a, b, c));

        assertThat(parts.parts()).containsExactly(a, b, c);
    }

    @Test
    @DisplayName("Should be equal when parts match in content and order")
    void equals_sameParts() {
        SystemPromptParts a = SystemPromptParts.of(List.of(part("x", "k1"), part("y", "k2")));
        SystemPromptParts b = SystemPromptParts.of(List.of(part("x", "k1"), part("y", "k2")));

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("Should not be equal when order differs")
    void equals_differentOrder() {
        SystemPromptParts a = SystemPromptParts.of(List.of(part("x", "k1"), part("y", "k2")));
        SystemPromptParts b = SystemPromptParts.of(List.of(part("y", "k2"), part("x", "k1")));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("Should not equal null or unrelated types")
    void equals_nullAndOtherType() {
        SystemPromptParts a = SystemPromptParts.empty();

        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo(Collections.emptyList());
    }

    @Test
    @DisplayName("toString should include size")
    void toString_includesSize() {
        SystemPromptParts parts = SystemPromptParts.of(List.of(part("a", "k1"), part("b", "k2")));

        assertThat(parts.toString()).contains("size=2");
    }
}
