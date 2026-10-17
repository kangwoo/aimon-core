package at.aimon.core.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EnvironmentRequestAttributes.extract")
class EnvironmentRequestAttributesTest {

    @Test
    @DisplayName("forwards only environment.* entries, prefix removed, nested segments kept")
    void forwardsPrefixedSubsetWithoutPrefix() {
        final Map<String, String> extracted = EnvironmentRequestAttributes
                .extract(Map.of("environment.sandbox.workspace", "ws-7", "environment.mode", "strict", "feature.flag",
                        true, "environmentX.foo", "near-miss", "ENVIRONMENT.upper", "case"));

        assertThat(extracted).containsOnly(Map.entry("sandbox.workspace", "ws-7"), Map.entry("mode", "strict"));
    }

    @Test
    @DisplayName("null or empty input, or nothing prefixed, gives an empty unmodifiable map")
    void emptyCases() {
        assertThat(EnvironmentRequestAttributes.extract(null)).isEmpty();
        assertThat(EnvironmentRequestAttributes.extract(Map.of())).isEmpty();
        final Map<String, String> none = EnvironmentRequestAttributes.extract(Map.of("feature.flag", "on"));
        assertThat(none).isEmpty();
        assertThatThrownBy(() -> none.put("a", "b")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the result is unmodifiable and detached from the input")
    void resultIsUnmodifiableCopy() {
        final Map<String, Object> input = new HashMap<>(Map.of("environment.a", "1"));
        final Map<String, String> extracted = EnvironmentRequestAttributes.extract(input);
        input.put("environment.b", "2");

        assertThat(extracted).containsOnly(Map.entry("a", "1"));
        assertThatThrownBy(() -> extracted.put("c", "3")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a non-String value is refused, naming the key and class but never the value")
    void nonStringValuesAreRefused() {
        for (Object value : List.of(Boolean.TRUE, 42, List.of("secret-ws"))) {
            assertThatThrownBy(() -> EnvironmentRequestAttributes.extract(Map.of("environment.sandbox.ws", value)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("environment.sandbox.ws")
                    .hasMessageContaining(value.getClass().getName()).hasMessageNotContaining("secret-ws");
        }
    }

    @Test
    @DisplayName("a null value under the prefix is refused as class null; a null value elsewhere is ignored")
    void nullValues() {
        final Map<String, Object> prefixed = new HashMap<>();
        prefixed.put("environment.sandbox.ws", null);
        assertThatThrownBy(() -> EnvironmentRequestAttributes.extract(prefixed))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("environment.sandbox.ws")
                .hasMessageContaining("null");

        final Map<String, Object> elsewhere = new HashMap<>();
        elsewhere.put("feature.flag", null);
        elsewhere.put(null, "x");
        assertThat(EnvironmentRequestAttributes.extract(elsewhere)).isEmpty();
    }

    @Test
    @DisplayName("a key that is exactly the prefix is refused")
    void barePrefixIsRefused() {
        assertThatThrownBy(() -> EnvironmentRequestAttributes.extract(Map.of("environment.", "x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'environment.'");
    }
}
