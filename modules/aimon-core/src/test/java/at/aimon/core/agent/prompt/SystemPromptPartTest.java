package at.aimon.core.agent.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SystemPromptPart Tests")
class SystemPromptPartTest {

    @Test
    @DisplayName("Should build with all required fields and empty cacheHintKey")
    void build_requiredFieldsOnly() {
        SystemPromptPart part = SystemPromptPart.builder().content("You are a helpful assistant.")
                .staticness(Staticness.STATIC).kind("agent-instructions").build();

        assertThat(part.getContent()).isEqualTo("You are a helpful assistant.");
        assertThat(part.getStaticness()).isEqualTo(Staticness.STATIC);
        assertThat(part.getKind()).isEqualTo("agent-instructions");
        assertThat(part.getCacheHintKey()).isEmpty();
    }

    @Test
    @DisplayName("Should build with cacheHintKey present")
    void build_withCacheHintKey() {
        SystemPromptPart part = SystemPromptPart.builder().content("tools...").staticness(Staticness.SEMI_STATIC)
                .kind("tool-registry").cacheHintKey("tools:v1").build();

        assertThat(part.getCacheHintKey()).contains("tools:v1");
    }

    @Test
    @DisplayName("Should allow clearing cacheHintKey with null")
    void build_nullCacheHintKeyClearsValue() {
        SystemPromptPart part = SystemPromptPart.builder().content("x").staticness(Staticness.DYNAMIC).kind("user")
                .cacheHintKey("first").cacheHintKey(null).build();

        assertThat(part.getCacheHintKey()).isEmpty();
    }

    @Test
    @DisplayName("Should never return null from getCacheHintKey")
    void cacheHintKey_isNeverNull() {
        SystemPromptPart part = SystemPromptPart.builder().content("x").staticness(Staticness.DYNAMIC).kind("user")
                .build();

        Optional<String> hint = part.getCacheHintKey();
        assertThat(hint).isNotNull();
        assertThat(hint).isEmpty();
    }

    @Test
    @DisplayName("Should reject null content")
    void build_nullContent_throwsNPE() {
        assertThatThrownBy(() -> SystemPromptPart.builder().content(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Content cannot be null");
    }

    @Test
    @DisplayName("Should reject missing content at build time")
    void build_missingContent_throwsNPE() {
        assertThatThrownBy(() -> SystemPromptPart.builder().staticness(Staticness.STATIC).kind("k").build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Content cannot be null");
    }

    @Test
    @DisplayName("Should reject empty content")
    void build_emptyContent_throwsIAE() {
        assertThatThrownBy(() -> SystemPromptPart.builder().content("").staticness(Staticness.STATIC).kind("k").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Content cannot be empty");
    }

    @Test
    @DisplayName("Should reject null staticness")
    void build_nullStaticness_throwsNPE() {
        assertThatThrownBy(() -> SystemPromptPart.builder().staticness(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Staticness cannot be null");
    }

    @Test
    @DisplayName("Should reject missing staticness at build time")
    void build_missingStaticness_throwsNPE() {
        assertThatThrownBy(() -> SystemPromptPart.builder().content("x").kind("k").build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Staticness cannot be null");
    }

    @Test
    @DisplayName("Should reject null kind")
    void build_nullKind_throwsNPE() {
        assertThatThrownBy(() -> SystemPromptPart.builder().kind(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Kind cannot be null");
    }

    @Test
    @DisplayName("Should reject missing kind at build time")
    void build_missingKind_throwsNPE() {
        assertThatThrownBy(() -> SystemPromptPart.builder().content("x").staticness(Staticness.STATIC).build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Kind cannot be null");
    }

    @Test
    @DisplayName("Should reject empty kind")
    void build_emptyKind_throwsIAE() {
        assertThatThrownBy(() -> SystemPromptPart.builder().content("x").staticness(Staticness.STATIC).kind("").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Kind cannot be empty");
    }

    @Test
    @DisplayName("Should be equal when all fields match")
    void equals_sameFields() {
        SystemPromptPart a = SystemPromptPart.builder().content("c").staticness(Staticness.STATIC).kind("k")
                .cacheHintKey("h").build();
        SystemPromptPart b = SystemPromptPart.builder().content("c").staticness(Staticness.STATIC).kind("k")
                .cacheHintKey("h").build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("Should be equal when cacheHintKey is unset in both")
    void equals_bothCacheHintKeyUnset() {
        SystemPromptPart a = SystemPromptPart.builder().content("c").staticness(Staticness.STATIC).kind("k").build();
        SystemPromptPart b = SystemPromptPart.builder().content("c").staticness(Staticness.STATIC).kind("k").build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("Should not be equal when content differs")
    void equals_differentContent() {
        SystemPromptPart a = SystemPromptPart.builder().content("c1").staticness(Staticness.STATIC).kind("k").build();
        SystemPromptPart b = SystemPromptPart.builder().content("c2").staticness(Staticness.STATIC).kind("k").build();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("Should not be equal when staticness differs")
    void equals_differentStaticness() {
        SystemPromptPart a = SystemPromptPart.builder().content("c").staticness(Staticness.STATIC).kind("k").build();
        SystemPromptPart b = SystemPromptPart.builder().content("c").staticness(Staticness.DYNAMIC).kind("k").build();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("Should not be equal when kind differs")
    void equals_differentKind() {
        SystemPromptPart a = SystemPromptPart.builder().content("c").staticness(Staticness.STATIC).kind("k1").build();
        SystemPromptPart b = SystemPromptPart.builder().content("c").staticness(Staticness.STATIC).kind("k2").build();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("Should not be equal when cacheHintKey differs")
    void equals_differentCacheHintKey() {
        SystemPromptPart a = SystemPromptPart.builder().content("c").staticness(Staticness.STATIC).kind("k")
                .cacheHintKey("h1").build();
        SystemPromptPart b = SystemPromptPart.builder().content("c").staticness(Staticness.STATIC).kind("k")
                .cacheHintKey("h2").build();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("Should not equal null or unrelated types")
    void equals_nullAndOtherType() {
        SystemPromptPart a = SystemPromptPart.builder().content("c").staticness(Staticness.STATIC).kind("k").build();

        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("c");
    }

    @Test
    @DisplayName("toString should include kind and staticness")
    void toString_includesKeyFields() {
        SystemPromptPart part = SystemPromptPart.builder().content("content-value").staticness(Staticness.STATIC)
                .kind("agent-instructions").build();

        String s = part.toString();
        assertThat(s).contains("agent-instructions");
        assertThat(s).contains("STATIC");
    }
}
