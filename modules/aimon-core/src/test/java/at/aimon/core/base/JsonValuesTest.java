package at.aimon.core.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JsonValues")
@SuppressWarnings("unchecked")
class JsonValuesTest {

    @Test
    @DisplayName("a null map member is dropped at every depth; a null list item is kept in place")
    void nullRules() {
        final Map<String, Object> inner = new HashMap<>();
        inner.put("gone", null);
        inner.put("kept", 1);
        final Map<String, Object> source = new HashMap<>();
        source.put("absent", null);
        source.put("nested", inner);
        source.put("items", Arrays.asList("a", null, "b"));

        final Map<String, Object> copy = JsonValues.immutableCopy(source);

        assertThat(copy).containsOnlyKeys("nested", "items");
        assertThat((Map<String, Object>) copy.get("nested")).containsOnlyKeys("kept");
        assertThat((List<Object>) copy.get("items")).containsExactly("a", null, "b");
    }

    @Test
    @DisplayName("the copy is deep and unmodifiable all the way down, and keeps key order")
    void deepAndUnmodifiable() {
        final List<Object> items = new ArrayList<>(List.of(Map.of("k", "v")));
        final Map<String, Object> source = new LinkedHashMap<>();
        source.put("z", 1);
        source.put("a", items);

        final Map<String, Object> copy = JsonValues.immutableCopy(source);
        items.add("later");
        source.put("b", 2);

        assertThat(copy.keySet()).containsExactly("z", "a");
        final List<?> copiedItems = (List<?>) copy.get("a");
        assertThat(copiedItems).hasSize(1);
        assertThatThrownBy(() -> copy.put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<Object>) copiedItems).add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((Map<String, Object>) copiedItems.get(0)).put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("non-string keys become strings, as JSON object keys are")
    void keysBecomeStrings() {
        final Map<String, Object> source = new LinkedHashMap<>();
        source.put("outer", Map.of(1, "one"));

        assertThat((Map<String, Object>) JsonValues.immutableCopy(source).get("outer")).containsOnlyKeys("1");
    }

    @Test
    @DisplayName("a null map is refused")
    void nullRefused() {
        assertThatThrownBy(() -> JsonValues.immutableCopy(null)).isInstanceOf(NullPointerException.class);
    }
}
