package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ToolContextKey")
class ToolContextKeyTest {

    @Nested
    @DisplayName("Factory method")
    class Factory {

        @Test
        @DisplayName("Should create key with correct name and type")
        void shouldCreateKeyWithCorrectNameAndType() {
            ToolContextKey<String> key = ToolContextKey.of("myKey", String.class);

            assertThat(key.name()).isEqualTo("myKey");
            assertThat(key.type()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("Should throw NullPointerException for null name")
        void shouldThrowForNullName() {
            assertThatNullPointerException().isThrownBy(() -> ToolContextKey.of(null, String.class))
                    .withMessageContaining("name");
        }

        @Test
        @DisplayName("Should throw NullPointerException for null type")
        void shouldThrowForNullType() {
            assertThatNullPointerException().isThrownBy(() -> ToolContextKey.of("key", null))
                    .withMessageContaining("type");
        }
    }

    @Nested
    @DisplayName("Equality")
    class Equality {

        @Test
        @DisplayName("Should be equal when names match")
        void shouldBeEqualWhenNamesMatch() {
            ToolContextKey<String> key1 = ToolContextKey.of("env", String.class);
            ToolContextKey<String> key2 = ToolContextKey.of("env", String.class);

            assertThat(key1).isEqualTo(key2);
            assertThat(key1.hashCode()).isEqualTo(key2.hashCode());
        }

        @Test
        @DisplayName("Should be equal when names match but types differ")
        void shouldBeEqualWhenNamesMatchButTypesDiffer() {
            ToolContextKey<String> stringKey = ToolContextKey.of("key", String.class);
            ToolContextKey<Integer> intKey = ToolContextKey.of("key", Integer.class);

            assertThat(stringKey).isEqualTo(intKey);
            assertThat(stringKey.hashCode()).isEqualTo(intKey.hashCode());
        }

        @Test
        @DisplayName("Should not be equal when names differ")
        void shouldNotBeEqualWhenNamesDiffer() {
            ToolContextKey<String> key1 = ToolContextKey.of("key1", String.class);
            ToolContextKey<String> key2 = ToolContextKey.of("key2", String.class);

            assertThat(key1).isNotEqualTo(key2);
        }

        @Test
        @DisplayName("Should not be equal to null")
        void shouldNotBeEqualToNull() {
            ToolContextKey<String> key = ToolContextKey.of("key", String.class);

            assertThat(key).isNotEqualTo(null);
        }

        @Test
        @DisplayName("Should not be equal to non-ToolContextKey object")
        void shouldNotBeEqualToNonToolContextKey() {
            ToolContextKey<String> key = ToolContextKey.of("key", String.class);

            assertThat(key).isNotEqualTo("key");
        }
    }

    @Nested
    @DisplayName("toString")
    class ToString {

        @Test
        @DisplayName("Should produce readable format")
        void shouldProduceReadableFormat() {
            ToolContextKey<String> key = ToolContextKey.of("environment", String.class);

            assertThat(key.toString()).contains("environment").contains("String");
        }
    }
}
