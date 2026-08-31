package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ToolInput Tests")
class ToolInputTest {

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("of() creates empty input")
        void ofEmpty() {
            ToolInput input = ToolInput.of();
            assertThat(input.isEmpty()).isTrue();
            assertThat(input.size()).isZero();
        }

        @Test
        @DisplayName("of(Map) creates input from map")
        void ofMap() {
            ToolInput input = ToolInput.of(Map.of("key", "value"));
            assertThat(input.size()).isEqualTo(1);
            assertThat(input.getRequiredString("key")).isEqualTo("value");
        }

        @Test
        @DisplayName("of(Map) rejects null")
        void ofMapRejectsNull() {
            assertThatThrownBy(() -> ToolInput.of((Map<String, Object>) null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("of(k, v) creates single-entry input")
        void ofSingleEntry() {
            ToolInput input = ToolInput.of("name", "test");
            assertThat(input.getRequiredString("name")).isEqualTo("test");
        }

        @Test
        @DisplayName("of(k1,v1,k2,v2) creates two-entry input")
        void ofTwoEntries() {
            ToolInput input = ToolInput.of("k1", "v1", "k2", 42);
            assertThat(input.getRequiredString("k1")).isEqualTo("v1");
            assertThat(input.getRequiredInteger("k2")).isEqualTo(42);
        }

        @Test
        @DisplayName("of(Map) drops null values instead of throwing")
        void ofMapDropsNullValues() {
            // Map.of cannot even express this case, so the source has to be a HashMap — which is exactly what the LLM
            // response converters hand over. The old Map.copyOf threw here.
            java.util.HashMap<String, Object> raw = new java.util.HashMap<>();
            raw.put("file_path", "/x");
            raw.put("offset", null);

            ToolInput input = ToolInput.of(raw);

            assertThat(input.has("offset")).isFalse();
            assertThat(input.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("of(k, v) with a null value yields an empty input")
        void ofVarargsDropsNullValues() {
            // The varargs overloads assembled a Map.of, which throws on a null value *before* the constructor's
            // normalization can run. Missing this would have made the new contract half-true: tolerant through
            // of(Map), still fatal through of(k, v).
            ToolInput input = ToolInput.of("offset", null);

            assertThat(input.isEmpty()).isTrue();
            assertThat(input.has("offset")).isFalse();
        }

        @Test
        @DisplayName("of(k1,v1,k2,v2) keeps the non-null half")
        void ofVarargsKeepsNonNullEntries() {
            ToolInput input = ToolInput.of("file_path", "/x", "offset", null);

            assertThat(input.getRequiredString("file_path")).isEqualTo("/x");
            assertThat(input.has("offset")).isFalse();
        }

        @Test
        @DisplayName("of(k, v) still rejects a null key and a duplicate key")
        void ofVarargsStillRejectsNullAndDuplicateKeys() {
            // Only the null-*value* behaviour was meant to change. Hand-assembling the map would otherwise have
            // silently accepted a null key and let a duplicate overwrite, both of which Map.of used to reject.
            assertThatThrownBy(() -> ToolInput.of(null, "v")).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> ToolInput.of("k", 1, "k", 2)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Duplicate parameter: k");
        }

        @Test
        @DisplayName("Keys keep the order they were supplied in")
        void preservesInsertionOrder() {
            ToolInput input = ToolInput.of("zulu", 1, "alpha", 2, "mike", 3);

            assertThat(input.keys()).containsExactly("zulu", "alpha", "mike");
        }

        @Test
        @DisplayName("Input is immutable - changes to source map do not affect it")
        void immutability() {
            java.util.HashMap<String, Object> mutableMap = new java.util.HashMap<>();
            mutableMap.put("key", "original");
            ToolInput input = ToolInput.of(mutableMap);

            mutableMap.put("key", "modified");
            mutableMap.put("new", "value");

            assertThat(input.getRequiredString("key")).isEqualTo("original");
            assertThat(input.has("new")).isFalse();
        }
    }

    @Nested
    @DisplayName("Required parameters")
    class RequiredParameters {

        @Test
        @DisplayName("getRequiredString returns value when present")
        void getRequiredStringPresent() {
            ToolInput input = ToolInput.of("path", "/tmp/file");
            assertThat(input.getRequiredString("path")).isEqualTo("/tmp/file");
        }

        @Test
        @DisplayName("getRequiredString throws for missing key")
        void getRequiredStringMissing() {
            ToolInput input = ToolInput.of();
            assertThatThrownBy(() -> input.getRequiredString("missing")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Missing required parameter: missing");
        }

        @Test
        @DisplayName("getRequiredString throws for wrong type")
        void getRequiredStringWrongType() {
            ToolInput input = ToolInput.of("num", 42);
            assertThatThrownBy(() -> input.getRequiredString("num")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be a string");
        }

        @Test
        @DisplayName("getRequiredString throws for null key")
        void getRequiredStringNullKey() {
            ToolInput input = ToolInput.of();
            assertThatThrownBy(() -> input.getRequiredString(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("getRequiredInteger returns value when present")
        void getRequiredIntegerPresent() {
            ToolInput input = ToolInput.of("count", 5);
            assertThat(input.getRequiredInteger("count")).isEqualTo(5);
        }

        @Test
        @DisplayName("getRequiredInteger throws for missing key")
        void getRequiredIntegerMissing() {
            ToolInput input = ToolInput.of();
            assertThatThrownBy(() -> input.getRequiredInteger("missing")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("getRequiredBoolean returns value when present")
        void getRequiredBooleanPresent() {
            ToolInput input = ToolInput.of("flag", true);
            assertThat(input.getRequiredBoolean("flag")).isTrue();
        }

        @Test
        @DisplayName("getRequiredBoolean throws for wrong type")
        void getRequiredBooleanWrongType() {
            ToolInput input = ToolInput.of("flag", "yes");
            assertThatThrownBy(() -> input.getRequiredBoolean("flag")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be a boolean");
        }
    }

    @Nested
    @DisplayName("Optional parameters with defaults")
    class OptionalWithDefaults {

        @Test
        @DisplayName("getString returns value when present")
        void getStringPresent() {
            ToolInput input = ToolInput.of("mode", "fast");
            assertThat(input.getString("mode", "slow")).isEqualTo("fast");
        }

        @Test
        @DisplayName("getString returns default when missing")
        void getStringMissing() {
            ToolInput input = ToolInput.of();
            assertThat(input.getString("mode", "slow")).isEqualTo("slow");
        }

        @Test
        @DisplayName("getInteger returns value when present")
        void getIntegerPresent() {
            ToolInput input = ToolInput.of("timeout", 5000);
            assertThat(input.getInteger("timeout", 2000)).isEqualTo(5000);
        }

        @Test
        @DisplayName("getInteger returns default when missing")
        void getIntegerMissing() {
            ToolInput input = ToolInput.of();
            assertThat(input.getInteger("timeout", 2000)).isEqualTo(2000);
        }

        @Test
        @DisplayName("getInteger converts from Number types")
        void getIntegerFromNumber() {
            ToolInput input = ToolInput.of("count", 42L);
            assertThat(input.getInteger("count", 0)).isEqualTo(42);
        }

        @Test
        @DisplayName("getInteger converts from String")
        void getIntegerFromString() {
            ToolInput input = ToolInput.of("count", "42");
            assertThat(input.getInteger("count", 0)).isEqualTo(42);
        }

        @Test
        @DisplayName("getInteger throws for invalid string")
        void getIntegerInvalidString() {
            ToolInput input = ToolInput.of("count", "abc");
            assertThatThrownBy(() -> input.getInteger("count", 0)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be an integer");
        }

        @Test
        @DisplayName("getInteger throws for out-of-range long")
        void getIntegerOverflow() {
            ToolInput input = ToolInput.of("count", Long.MAX_VALUE);
            assertThatThrownBy(() -> input.getInteger("count", 0)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("out of integer range");
        }

        @Test
        @DisplayName("getLong returns value when present")
        void getLongPresent() {
            ToolInput input = ToolInput.of("size", 999999999999L);
            assertThat(input.getLong("size", 0L)).isEqualTo(999999999999L);
        }

        @Test
        @DisplayName("getLong returns default when missing")
        void getLongMissing() {
            ToolInput input = ToolInput.of();
            assertThat(input.getLong("size", 100L)).isEqualTo(100L);
        }

        @Test
        @DisplayName("getBoolean returns value when present")
        void getBooleanPresent() {
            ToolInput input = ToolInput.of("verbose", true);
            assertThat(input.getBoolean("verbose", false)).isTrue();
        }

        @Test
        @DisplayName("getBoolean returns default when missing")
        void getBooleanMissing() {
            ToolInput input = ToolInput.of();
            assertThat(input.getBoolean("verbose", false)).isFalse();
        }
    }

    @Nested
    @DisplayName("Nullable parameters")
    class NullableParameters {

        @Test
        @DisplayName("getStringOrNull returns value when present")
        void present() {
            ToolInput input = ToolInput.of("desc", "hello");
            assertThat(input.getStringOrNull("desc")).isEqualTo("hello");
        }

        @Test
        @DisplayName("getStringOrNull returns null when missing")
        void missing() {
            ToolInput input = ToolInput.of();
            assertThat(input.getStringOrNull("desc")).isNull();
        }

        @Test
        @DisplayName("getIntegerOrNull returns null when missing")
        void integerMissing() {
            ToolInput input = ToolInput.of();
            assertThat(input.getIntegerOrNull("count")).isNull();
        }

        @Test
        @DisplayName("getLongOrNull returns null when missing")
        void longMissing() {
            ToolInput input = ToolInput.of();
            assertThat(input.getLongOrNull("size")).isNull();
        }

        @Test
        @DisplayName("getBooleanOrNull returns null when missing")
        void booleanMissing() {
            ToolInput input = ToolInput.of();
            assertThat(input.getBooleanOrNull("flag")).isNull();
        }
    }

    @Nested
    @DisplayName("Validation helpers")
    class ValidationHelpers {

        @Test
        @DisplayName("has returns true for existing key")
        void hasExisting() {
            ToolInput input = ToolInput.of("key", "val");
            assertThat(input.has("key")).isTrue();
        }

        @Test
        @DisplayName("has returns false for missing key")
        void hasMissing() {
            ToolInput input = ToolInput.of();
            assertThat(input.has("key")).isFalse();
        }

        @Test
        @DisplayName("Every accessor treats a null-valued key the same as a missing one")
        void allAccessorsAgreeOnANullValuedKey() {
            java.util.HashMap<String, Object> raw = new java.util.HashMap<>();
            raw.put("offset", null);
            ToolInput input = ToolInput.of(raw);

            // The four accessors are the reason the entry is dropped rather than kept-as-null: a retained null would
            // make getRequiredInteger report a *type* error ("must be an integer, but was: null") for a parameter the
            // model simply did not supply, which is the wrong thing to hand back to it.
            assertThat(input.has("offset")).isFalse();
            assertThat(input.getInteger("offset", 7)).isEqualTo(7);
            assertThat(input.getIntegerOrNull("offset")).isNull();
            assertThatThrownBy(() -> input.getRequiredInteger("offset")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Missing required parameter: offset");
        }

        @Test
        @DisplayName("has throws for null key")
        void hasNullKey() {
            ToolInput input = ToolInput.of();
            assertThatThrownBy(() -> input.has(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("keys returns all parameter keys")
        void keys() {
            ToolInput input = ToolInput.of("a", 1, "b", 2);
            assertThat(input.keys()).containsExactlyInAnyOrder("a", "b");
        }

        @Test
        @DisplayName("get returns raw value")
        void getRaw() {
            ToolInput input = ToolInput.of("key", 42);
            assertThat(input.get("key")).isEqualTo(42);
        }

        @Test
        @DisplayName("get returns null for missing key")
        void getRawMissing() {
            ToolInput input = ToolInput.of();
            assertThat(input.get("key")).isNull();
        }
    }

    @Nested
    @DisplayName("toMap")
    class ToMap {

        @Test
        @DisplayName("toMap returns unmodifiable view")
        void unmodifiable() {
            ToolInput input = ToolInput.of("key", "val");
            assertThatThrownBy(() -> input.toMap().put("new", "value"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("toMap contains all entries")
        void contents() {
            ToolInput input = ToolInput.of("a", 1, "b", "two");
            Map<String, Object> map = input.toMap();
            assertThat(map).hasSize(2).containsEntry("a", 1).containsEntry("b", "two");
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsHashCode {

        @Test
        @DisplayName("Equal inputs are equal")
        void equal() {
            ToolInput a = ToolInput.of("k", "v");
            ToolInput b = ToolInput.of("k", "v");
            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("Different inputs are not equal")
        void notEqual() {
            ToolInput a = ToolInput.of("k", "v1");
            ToolInput b = ToolInput.of("k", "v2");
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("toString contains data")
        void toStringContains() {
            ToolInput input = ToolInput.of("key", "val");
            assertThat(input.toString()).contains("key").contains("val");
        }
    }
}
