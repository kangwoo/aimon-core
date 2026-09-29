package at.aimon.core.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DefinitionAttributes")
class DefinitionAttributesTest {

    @Test
    @DisplayName("null means no attributes")
    void nullIsEmpty() {
        assertThat(DefinitionAttributes.fromFrontmatter(null)).isEmpty();
    }

    @Test
    @DisplayName("nested maps flatten to dotted keys; scalars become text")
    void flattens() {
        final Map<String, Object> sandbox = new LinkedHashMap<>();
        sandbox.put("slot", "build");
        sandbox.put("cpus", 4);
        final Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("sandbox", sandbox);
        raw.put("gpu", false);

        assertThat(DefinitionAttributes.fromFrontmatter(raw)).containsExactly(Map.entry("sandbox.slot", "build"),
                Map.entry("sandbox.cpus", "4"), Map.entry("gpu", "false"));
    }

    @Test
    @DisplayName("a null value is an error naming the key")
    void nullValueRejected() {
        final Map<String, Object> raw = new HashMap<>();
        raw.put("slot", null);

        assertThatThrownBy(() -> DefinitionAttributes.fromFrontmatter(raw)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'slot' has no value");
    }

    @Test
    @DisplayName("a blank key is an error")
    void blankKeyRejected() {
        assertThatThrownBy(() -> DefinitionAttributes.fromFrontmatter(Map.of("sandbox", Map.of(" ", "x"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blank key under 'sandbox'");
    }

    @Test
    @DisplayName("an empty nested map is an error rather than no attribute")
    void emptyNestedMapRejected() {
        assertThatThrownBy(() -> DefinitionAttributes.fromFrontmatter(Map.of("sandbox", Map.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'sandbox' is an empty map");
    }

    @Test
    @DisplayName("a key that is both a value and a group is an error")
    void valueAndGroupRejected() {
        final Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("sandbox", "x");
        raw.put("sandbox.slot", "y");

        assertThatThrownBy(() -> DefinitionAttributes.fromFrontmatter(raw)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'sandbox' is both a value and a group");
    }

    @Test
    @DisplayName("a list value is an error naming the key")
    void listRejected() {
        assertThatThrownBy(() -> DefinitionAttributes.fromFrontmatter(Map.of("sandbox", Map.of("slot", List.of("a")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'sandbox.slot' is a list");
    }

    @Test
    @DisplayName("a YAML-typed scalar such as a date is refused with a hint to quote it")
    void dateRejectedWithQuotingHint() {
        assertThatThrownBy(() -> DefinitionAttributes.fromFrontmatter(Map.of("since", new Date(0))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'since'")
                .hasMessageContaining("quote it");
    }

    @Test
    @DisplayName("copyOf rejects a key with surrounding whitespace instead of trimming it")
    void copyOfRejectsUntrimmedKey() {
        assertThatThrownBy(() -> DefinitionAttributes.copyOf(Map.of("sandbox.slot ", "b")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("surrounding whitespace");
    }

    @Test
    @DisplayName("copyOf rejects null keys and values and returns an unmodifiable copy")
    void copyOfValidates() {
        final Map<String, String> withNull = new HashMap<>();
        withNull.put("slot", null);

        assertThatThrownBy(() -> DefinitionAttributes.copyOf(withNull)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DefinitionAttributes.copyOf(Map.of("slot", "a")).put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
