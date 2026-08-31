package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ToolContext typed key integration")
class ToolContextTypedKeyTest {

    @Nested
    @DisplayName("get(ToolContextKey)")
    class TypedGet {

        @Test
        @DisplayName("Should return typed value when key exists")
        void shouldReturnTypedValue() {
            ToolContextKey<String> key = ToolContextKey.of("name", String.class);
            ToolContext context = ToolContext.builder().put(key, "hello").build();

            Optional<String> result = context.get(key);

            assertThat(result).hasValue("hello");
        }

        @Test
        @DisplayName("Should return empty when key is absent")
        void shouldReturnEmptyForAbsentKey() {
            ToolContextKey<String> key = ToolContextKey.of("missing", String.class);
            ToolContext context = ToolContext.empty();

            Optional<String> result = context.get(key);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return empty when value type does not match")
        void shouldReturnEmptyForTypeMismatch() {
            ToolContextKey<String> key = ToolContextKey.of("count", String.class);
            ToolContext context = ToolContext.builder().put("count", 42).build();

            Optional<String> result = context.get(key);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should throw NullPointerException for null key")
        void shouldThrowForNullKey() {
            ToolContext context = ToolContext.empty();

            assertThatNullPointerException().isThrownBy(() -> context.get((ToolContextKey<String>) null));
        }
    }

    @Nested
    @DisplayName("containsKey(ToolContextKey)")
    class TypedContainsKey {

        @Test
        @DisplayName("Should return true when key exists")
        void shouldReturnTrueWhenExists() {
            ToolContextKey<String> key = ToolContextKey.of("env", String.class);
            ToolContext context = ToolContext.builder().put(key, "prod").build();

            assertThat(context.containsKey(key)).isTrue();
        }

        @Test
        @DisplayName("Should return false when key is absent")
        void shouldReturnFalseWhenAbsent() {
            ToolContextKey<String> key = ToolContextKey.of("missing", String.class);
            ToolContext context = ToolContext.empty();

            assertThat(context.containsKey(key)).isFalse();
        }
    }

    @Nested
    @DisplayName("Builder.put(ToolContextKey, T)")
    class TypedPut {

        @Test
        @DisplayName("Should store and retrieve value correctly")
        void shouldStoreAndRetrieveValue() {
            ToolContextKey<Integer> key = ToolContextKey.of("timeout", Integer.class);
            ToolContext context = ToolContext.builder().put(key, 5000).build();

            assertThat(context.get(key)).hasValue(5000);
        }

        @Test
        @DisplayName("Should throw NullPointerException for null key")
        void shouldThrowForNullKey() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ToolContext.builder().put((ToolContextKey<String>) null, "value"));
        }

        @Test
        @DisplayName("Should throw NullPointerException for null value")
        void shouldThrowForNullValue() {
            ToolContextKey<String> key = ToolContextKey.of("key", String.class);

            assertThatNullPointerException().isThrownBy(() -> ToolContext.builder().put(key, null));
        }
    }

    @Nested
    @DisplayName("Interoperability")
    class Interop {

        @Test
        @DisplayName("String put should be retrievable via typed get")
        void stringPutTypedGet() {
            ToolContextKey<String> key = ToolContextKey.of("requestId", String.class);
            ToolContext context = ToolContext.builder().put("requestId", "req-123").build();

            assertThat(context.get(key)).hasValue("req-123");
        }

        @Test
        @DisplayName("Typed put should be retrievable via string get")
        void typedPutStringGet() {
            ToolContextKey<String> key = ToolContextKey.of("requestId", String.class);
            ToolContext context = ToolContext.builder().put(key, "req-456").build();

            assertThat(context.get("requestId", String.class)).hasValue("req-456");
        }
    }

    @Nested
    @DisplayName("Generic type keys")
    class GenericTypes {

        @SuppressWarnings("unchecked")
        private static final ToolContextKey<Set<String>> SET_KEY = ToolContextKey.of("files",
                (Class<Set<String>>) (Class<?>) Set.class);

        @Test
        @DisplayName("Should work with generic type keys")
        void shouldWorkWithGenericTypeKeys() {
            Set<String> files = new HashSet<>();
            files.add("/path/to/file.txt");
            ToolContext context = ToolContext.builder().put(SET_KEY, files).build();

            Optional<Set<String>> result = context.get(SET_KEY);

            assertThat(result).isPresent();
            assertThat(result.get()).containsExactly("/path/to/file.txt");
        }
    }
}
