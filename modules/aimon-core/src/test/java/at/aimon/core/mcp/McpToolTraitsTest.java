package at.aimon.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.mcp.McpServerConfig.AnnotationTrust;

/**
 * The whole trust decision lives in {@link McpToolTraits#resolve}, so this is the resolution table rather than a set of
 * examples: every combination of trust and the two hints that feed an axis, plus the contradiction the normalisation
 * exists for.
 */
@DisplayName("McpToolTraits")
class McpToolTraitsTest {

    @Nested
    @DisplayName("IGNORE — the default, which reads nothing")
    class Ignored {

        @Test
        @DisplayName("a server claiming to be read-only and harmless is still MUTATING + DESTRUCTIVE")
        void ignoresEvenTheStrongestClaim() {
            final McpToolAnnotations claims = McpToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                    .build();

            final McpToolTraits traits = McpToolTraits.resolve(claims, AnnotationTrust.IGNORE);

            assertThat(traits.getSideEffectLevel()).isEqualTo(SideEffectLevel.MUTATING);
            assertThat(traits.getDestructiveBehavior()).isEqualTo(DestructiveBehavior.DESTRUCTIVE);
        }

        @Test
        @DisplayName("the result is the same object an unread tool gets, so nothing distinguishes the two paths")
        void isIndistinguishableFromUntrusted() {
            assertThat(McpToolTraits.resolve(McpToolAnnotations.empty(), AnnotationTrust.IGNORE))
                    .isEqualTo(McpToolTraits.untrusted());
        }
    }

    @Nested
    @DisplayName("TRUST — the claims become the declarations")
    class Trusted {

        @Test
        @DisplayName("readOnlyHint: true lowers the level and settles destructiveness with it")
        void readOnly() {
            final McpToolTraits traits = McpToolTraits.resolve(McpToolAnnotations.builder().readOnlyHint(true).build(),
                    AnnotationTrust.TRUST);

            assertThat(traits.getSideEffectLevel()).isEqualTo(SideEffectLevel.READ_ONLY);
            assertThat(traits.getDestructiveBehavior()).isEqualTo(DestructiveBehavior.NON_DESTRUCTIVE);
        }

        @Test
        @DisplayName("a write that disclaims destructiveness is the one combination that buys an exemption")
        void additiveWrite() {
            final McpToolTraits traits = McpToolTraits.resolve(
                    McpToolAnnotations.builder().readOnlyHint(false).destructiveHint(false).build(),
                    AnnotationTrust.TRUST);

            assertThat(traits.getSideEffectLevel()).isEqualTo(SideEffectLevel.MUTATING);
            assertThat(traits.getDestructiveBehavior()).isEqualTo(DestructiveBehavior.NON_DESTRUCTIVE);
        }

        @Test
        @DisplayName("a write that admits destructiveness declares it")
        void destructiveWrite() {
            final McpToolTraits traits = McpToolTraits.resolve(
                    McpToolAnnotations.builder().readOnlyHint(false).destructiveHint(true).build(),
                    AnnotationTrust.TRUST);

            assertThat(traits.getSideEffectLevel()).isEqualTo(SideEffectLevel.MUTATING);
            assertThat(traits.getDestructiveBehavior()).isEqualTo(DestructiveBehavior.DESTRUCTIVE);
        }

        @Test
        @DisplayName("a server that sent no annotations gets the MCP defaults, which are the conservative ones")
        void noAnnotationsNeedsNoSpecialCase() {
            assertThat(McpToolTraits.resolve(McpToolAnnotations.empty(), AnnotationTrust.TRUST))
                    .isEqualTo(McpToolTraits.untrusted());
        }

        @Test
        @DisplayName("readOnlyHint alone leaves destructiveHint at its true default, which read-only overrides")
        void readOnlyWinsOverTheAbsentDestructiveDefault() {
            final McpToolTraits traits = McpToolTraits.resolve(McpToolAnnotations.builder().readOnlyHint(true).build(),
                    AnnotationTrust.TRUST);

            assertThat(traits.getDestructiveBehavior()).isEqualTo(DestructiveBehavior.NON_DESTRUCTIVE);
        }

        @Test
        @DisplayName("a self-contradicting server is normalised toward read-only rather than stored as-is")
        void contradictionIsNormalised() {
            final McpToolAnnotations contradiction = McpToolAnnotations.builder().readOnlyHint(true)
                    .destructiveHint(true).build();

            final McpToolTraits traits = McpToolTraits.resolve(contradiction, AnnotationTrust.TRUST);

            assertThat(traits.getSideEffectLevel()).isEqualTo(SideEffectLevel.READ_ONLY);
            assertThat(traits.getDestructiveBehavior()).as("consumers never read the second axis below MUTATING")
                    .isEqualTo(DestructiveBehavior.NON_DESTRUCTIVE);
        }
    }

    @Test
    @DisplayName("untrusted() is the conservative end of both axes")
    void untrusted() {
        assertThat(McpToolTraits.untrusted().getSideEffectLevel()).isEqualTo(SideEffectLevel.MUTATING);
        assertThat(McpToolTraits.untrusted().getDestructiveBehavior()).isEqualTo(DestructiveBehavior.DESTRUCTIVE);
    }

    @Test
    @DisplayName("resolve rejects nulls rather than guessing a trust level")
    void nullsRejected() {
        assertThatNullPointerException().isThrownBy(() -> McpToolTraits.resolve(null, AnnotationTrust.TRUST))
                .withMessageContaining("annotations");
        assertThatNullPointerException().isThrownBy(() -> McpToolTraits.resolve(McpToolAnnotations.empty(), null))
                .withMessageContaining("trust");
    }
}
