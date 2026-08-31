package at.aimon.core.agent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link OpenAttributes} value semantics: shared empty singleton, immutable builder output, defensive map
 * copy, lookup helpers, and equals/hashCode contracts.
 */
@DisplayName("OpenAttributes builder / equality / immutability")
class OpenAttributesTest {

    @Nested
    @DisplayName("empty() and defaults")
    class EmptyAndDefaults {

        @Test
        @DisplayName("empty(): no entries, isEmpty() true, asMap() empty")
        void emptyHasNoEntries() {
            final OpenAttributes attrs = OpenAttributes.empty();
            assertThat(attrs.isEmpty()).isTrue();
            assertThat(attrs.asMap()).isEmpty();
        }

        @Test
        @DisplayName("empty() returns a shared singleton")
        void emptyIsShared() {
            assertThat(OpenAttributes.empty()).isSameAs(OpenAttributes.empty());
        }

        @Test
        @DisplayName("builder().build() equals empty() (value-equal)")
        void builderEmptyEqualsEmpty() {
            assertThat(OpenAttributes.builder().build()).isEqualTo(OpenAttributes.empty());
        }
    }

    @Nested
    @DisplayName("Builder put / putAll")
    class BuilderPut {

        @Test
        @DisplayName("put(k, v): value retrievable via getString; has() reports presence")
        void putRoundTrip() {
            final OpenAttributes attrs = OpenAttributes.builder().put("ops.agentId", "agt-1").put("ops.ouId", "ou-9")
                    .build();

            assertThat(attrs.isEmpty()).isFalse();
            assertThat(attrs.getString("ops.agentId")).hasValue("agt-1");
            assertThat(attrs.getString("ops.ouId")).hasValue("ou-9");
            assertThat(attrs.has("ops.agentId")).isTrue();
            assertThat(attrs.has("missing")).isFalse();
            assertThat(attrs.getString("missing")).isEmpty();
        }

        @Test
        @DisplayName("put with later same key overwrites previous value")
        void putOverwritesSameKey() {
            final OpenAttributes attrs = OpenAttributes.builder().put("k", "v1").put("k", "v2").build();

            assertThat(attrs.getString("k")).hasValue("v2");
        }

        @Test
        @DisplayName("putAll merges all entries, overwriting on conflict")
        void putAllMerges() {
            final Map<String, String> source = new HashMap<>();
            source.put("a", "1");
            source.put("b", "2");
            final OpenAttributes attrs = OpenAttributes.builder().put("a", "0").putAll(source).build();

            assertThat(attrs.getString("a")).hasValue("1");
            assertThat(attrs.getString("b")).hasValue("2");
        }

        @Test
        @DisplayName("put rejects null key / null value")
        void putRejectsNulls() {
            final OpenAttributes.Builder b = OpenAttributes.builder();
            assertThatThrownBy(() -> b.put(null, "v")).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> b.put("k", null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("putAll rejects null map and null entries")
        void putAllRejectsNulls() {
            final OpenAttributes.Builder b = OpenAttributes.builder();
            assertThatThrownBy(() -> b.putAll(null)).isInstanceOf(NullPointerException.class);

            final Map<String, String> nullValueMap = new HashMap<>();
            nullValueMap.put("k", null);
            assertThatThrownBy(() -> OpenAttributes.builder().putAll(nullValueMap))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("asMap() returns an unmodifiable map")
        void asMapUnmodifiable() {
            final OpenAttributes attrs = OpenAttributes.builder().put("k", "v").build();
            assertThatThrownBy(() -> attrs.asMap().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("mutating the source map after build() does not leak into the value object")
        void sourceMapMutationsDoNotLeak() {
            final Map<String, String> source = new HashMap<>();
            source.put("a", "1");
            final OpenAttributes attrs = OpenAttributes.builder().putAll(source).build();

            source.put("b", "2");
            source.put("a", "999");

            assertThat(attrs.getString("a")).hasValue("1");
            assertThat(attrs.has("b")).isFalse();
        }

        @Test
        @DisplayName("getString / has reject null key")
        void accessorsRejectNullKey() {
            final OpenAttributes attrs = OpenAttributes.builder().put("k", "v").build();
            assertThatThrownBy(() -> attrs.getString(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> attrs.has(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Equality / toString")
    class EqualityAndToString {

        @Test
        @DisplayName("equals/hashCode based on entries")
        void equalsByEntries() {
            final OpenAttributes a = OpenAttributes.builder().put("k1", "v1").put("k2", "v2").build();
            final OpenAttributes b = OpenAttributes.builder().put("k2", "v2").put("k1", "v1").build();

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("toString contains entry preview")
        void toStringIncludesValues() {
            final OpenAttributes attrs = OpenAttributes.builder().put("ops.agentId", "agt-1").build();
            assertThat(attrs.toString()).contains("ops.agentId").contains("agt-1");
        }

        @Test
        @DisplayName("equals(null) and equals(other-type) return false")
        void equalsNullAndOtherType() {
            final OpenAttributes attrs = OpenAttributes.builder().put("k", "v").build();
            assertThat(attrs.equals(null)).isFalse();
            assertThat(attrs.equals("not-an-OpenAttributes")).isFalse();
        }
    }
}
