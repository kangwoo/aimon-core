package at.aimon.core.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ContextBlock Tests")
class ContextBlockTest {

    @Nested
    @DisplayName("Factories")
    class Factories {

        @Test
        @DisplayName("system() produces a cacheable SYSTEM block")
        void systemBlock() {
            ContextBlock block = ContextBlock.system("environment", "body");

            assertThat(block.getKind()).isEqualTo(ContextBlockKind.SYSTEM);
            assertThat(block.getKey()).isEqualTo("environment");
            assertThat(block.getBody()).isEqualTo("body");
            assertThat(block.isCacheable()).isTrue();
        }

        @Test
        @DisplayName("userPrepend() produces a non-cacheable USER_PREPEND block")
        void userPrependBlock() {
            ContextBlock block = ContextBlock.userPrepend("current-date", "2026-07-06");

            assertThat(block.getKind()).isEqualTo(ContextBlockKind.USER_PREPEND);
            assertThat(block.isCacheable()).isFalse();
        }

        @Test
        @DisplayName("attachment() produces a non-cacheable ATTACHMENT block")
        void attachmentBlock() {
            ContextBlock block = ContextBlock.attachment("file-changed", "X was modified");

            assertThat(block.getKind()).isEqualTo(ContextBlockKind.ATTACHMENT);
            assertThat(block.isCacheable()).isFalse();
        }

        @Test
        @DisplayName("empty body is allowed")
        void emptyBodyAllowed() {
            assertThat(ContextBlock.system("k", "").getBody()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("null kind rejected")
        void rejectsNullKind() {
            assertThatThrownBy(() -> ContextBlock.builder().key("k").body("b").build())
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null key rejected")
        void rejectsNullKey() {
            assertThatThrownBy(() -> ContextBlock.builder().kind(ContextBlockKind.SYSTEM).body("b").build())
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null body rejected")
        void rejectsNullBody() {
            assertThatThrownBy(() -> ContextBlock.system("k", null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("empty key rejected")
        void rejectsEmptyKey() {
            assertThatThrownBy(() -> ContextBlock.system("", "b")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty");
        }

        @Test
        @DisplayName("key with disallowed characters rejected")
        void rejectsBadKey() {
            assertThatThrownBy(() -> ContextBlock.system("bad key!", "b")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("[A-Za-z0-9._-]+");
        }

        @Test
        @DisplayName("key with dots, underscores, hyphens accepted")
        void acceptsPunctuatedKey() {
            assertThat(ContextBlock.system("git.branch_name-1", "b").getKey()).isEqualTo("git.branch_name-1");
        }
    }

    @Nested
    @DisplayName("Equality")
    class Equality {

        @Test
        @DisplayName("same fields are equal")
        void sameFieldsEqual() {
            ContextBlock a = ContextBlock.system("k", "b");
            ContextBlock b = ContextBlock.system("k", "b");

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("differing kind is not equal")
        void differingKindNotEqual() {
            assertThat(ContextBlock.system("k", "b")).isNotEqualTo(ContextBlock.userPrepend("k", "b"));
        }

        @Test
        @DisplayName("differing cacheable is not equal")
        void differingCacheableNotEqual() {
            ContextBlock a = ContextBlock.builder().kind(ContextBlockKind.SYSTEM).key("k").body("b").cacheable(true)
                    .build();
            ContextBlock b = ContextBlock.builder().kind(ContextBlockKind.SYSTEM).key("k").body("b").cacheable(false)
                    .build();

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("toString reports kind, key, and body length (not the body)")
        void toStringSummarises() {
            assertThat(ContextBlock.system("k", "secret-body").toString()).contains("SYSTEM").contains("k")
                    .contains("bodyChars=11").doesNotContain("secret-body");
        }
    }
}
