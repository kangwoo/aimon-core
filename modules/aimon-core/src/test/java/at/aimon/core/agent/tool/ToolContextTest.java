package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ToolContext Tests")
class ToolContextTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("empty() creates empty context")
        void emptyContext() {
            ToolContext context = ToolContext.empty();
            assertThat(context.isEmpty()).isTrue();
            assertThat(context.size()).isZero();
        }

        @Test
        @DisplayName("Constructor rejects null map")
        void rejectsNull() {
            assertThatThrownBy(() -> new ToolContext(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Constructor creates defensive copy")
        void defensiveCopy() {
            HashMap<String, Object> mutableMap = new HashMap<>();
            mutableMap.put("key", "original");
            ToolContext context = new ToolContext(mutableMap);

            mutableMap.put("key", "modified");
            mutableMap.put("new", "value");

            assertThat(context.get("key")).contains("original");
            assertThat(context.containsKey("new")).isFalse();
        }

        @Test
        @DisplayName("getContext() returns unmodifiable map")
        void unmodifiableMap() {
            ToolContext context = ToolContext.builder().put("key", "value").build();

            assertThatThrownBy(() -> context.getContext().put("new", "value"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTest {

        @Test
        @DisplayName("Builder puts string values")
        void putString() {
            ToolContext context = ToolContext.builder().put("name", "test").build();
            assertThat(context.get("name")).contains("test");
        }

        @Test
        @DisplayName("Builder puts multiple values")
        void putMultiple() {
            ToolContext context = ToolContext.builder().put("a", 1).put("b", "two").build();
            assertThat(context.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("Builder rejects null key")
        void rejectsNullKey() {
            assertThatThrownBy(() -> ToolContext.builder().put((String) null, "value"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Builder rejects null value")
        void rejectsNullValue() {
            assertThatThrownBy(() -> ToolContext.builder().put("key", null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Builder putAll adds map entries")
        void putAll() {
            ToolContext context = ToolContext.builder().putAll(Map.of("a", 1, "b", 2)).build();
            assertThat(context.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("Builder putAll rejects null map")
        void putAllRejectsNull() {
            assertThatThrownBy(() -> ToolContext.builder().putAll(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("get(String)")
    class GetByString {

        @Test
        @DisplayName("Returns value when present")
        void present() {
            ToolContext context = ToolContext.builder().put("key", "value").build();
            assertThat(context.get("key")).contains("value");
        }

        @Test
        @DisplayName("Returns empty when missing")
        void missing() {
            ToolContext context = ToolContext.empty();
            assertThat(context.get("key")).isEmpty();
        }

        @Test
        @DisplayName("Rejects null key")
        void rejectsNull() {
            ToolContext context = ToolContext.empty();
            assertThatThrownBy(() -> context.get((String) null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("get(String, Class)")
    class GetByStringAndType {

        @Test
        @DisplayName("Returns typed value when present and correct type")
        void correctType() {
            ToolContext context = ToolContext.builder().put("count", 42).build();
            Optional<Integer> value = context.get("count", Integer.class);
            assertThat(value).contains(42);
        }

        @Test
        @DisplayName("Returns empty when type mismatch")
        void typeMismatch() {
            ToolContext context = ToolContext.builder().put("count", "not a number").build();
            Optional<Integer> value = context.get("count", Integer.class);
            assertThat(value).isEmpty();
        }

        @Test
        @DisplayName("Returns empty when missing")
        void missing() {
            ToolContext context = ToolContext.empty();
            Optional<String> value = context.get("key", String.class);
            assertThat(value).isEmpty();
        }

        @Test
        @DisplayName("Rejects null key")
        void rejectsNullKey() {
            ToolContext context = ToolContext.empty();
            assertThatThrownBy(() -> context.get(null, String.class)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Rejects null type")
        void rejectsNullType() {
            ToolContext context = ToolContext.empty();
            assertThatThrownBy(() -> context.get("key", null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("get(ToolContextKey)")
    class GetByTypedKey {

        private static final ToolContextKey<String> NAME_KEY = ToolContextKey.of("name", String.class);
        private static final ToolContextKey<Integer> COUNT_KEY = ToolContextKey.of("count", Integer.class);

        @Test
        @DisplayName("Returns typed value when present")
        void present() {
            ToolContext context = ToolContext.builder().put(NAME_KEY, "test").build();
            assertThat(context.get(NAME_KEY)).contains("test");
        }

        @Test
        @DisplayName("Returns empty when missing")
        void missing() {
            ToolContext context = ToolContext.empty();
            assertThat(context.get(NAME_KEY)).isEmpty();
        }

        @Test
        @DisplayName("Returns empty when type mismatch")
        void typeMismatch() {
            ToolContext context = ToolContext.builder().put("count", "not a number").build();
            assertThat(context.get(COUNT_KEY)).isEmpty();
        }

        @Test
        @DisplayName("Rejects null key")
        void rejectsNull() {
            ToolContext context = ToolContext.empty();
            assertThatThrownBy(() -> context.get((ToolContextKey<String>) null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("containsKey")
    class ContainsKey {

        @Test
        @DisplayName("Returns true for existing key")
        void existing() {
            ToolContext context = ToolContext.builder().put("key", "value").build();
            assertThat(context.containsKey("key")).isTrue();
        }

        @Test
        @DisplayName("Returns false for missing key")
        void missing() {
            ToolContext context = ToolContext.empty();
            assertThat(context.containsKey("key")).isFalse();
        }

        @Test
        @DisplayName("Rejects null key")
        void rejectsNull() {
            ToolContext context = ToolContext.empty();
            assertThatThrownBy(() -> context.containsKey((String) null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Works with typed key")
        void typedKey() {
            ToolContextKey<String> key = ToolContextKey.of("name", String.class);
            ToolContext context = ToolContext.builder().put(key, "test").build();
            assertThat(context.containsKey(key)).isTrue();
        }

        @Test
        @DisplayName("Typed key rejects null")
        void typedKeyRejectsNull() {
            ToolContext context = ToolContext.empty();
            assertThatThrownBy(() -> context.containsKey((ToolContextKey<?>) null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
