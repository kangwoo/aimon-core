package at.aimon.core.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;

class LlmCallMetadataTest {

    @Test
    @DisplayName("withoutCallSite drops component, parentComponent and feature and keeps principal, traceId and tags")
    void withoutCallSiteDropsOnlyTheCallSite() {
        final LlmCallMetadata metadata = LlmCallMetadata.builder().component("agent").parentComponent("parent")
                .feature(LlmCallMetadata.Feature.REACT_LOOP).principal(Principal.user("alice")).traceId("session-1")
                .tag("routing", "review").build();

        final LlmCallMetadata stripped = metadata.withoutCallSite();

        assertThat(stripped).isEqualTo(LlmCallMetadata.builder().principal(Principal.user("alice")).traceId("session-1")
                .tag("routing", "review").build());
    }

    @Test
    @DisplayName("withoutCallSite returns the same instance when there is no call site to drop")
    void withoutCallSiteIsIdentityWithoutCallSite() {
        final LlmCallMetadata metadata = LlmCallMetadata.builder().traceId("session-1").tag("routing", "review")
                .build();

        assertThat(metadata.withoutCallSite()).isSameAs(metadata);
        assertThat(LlmCallMetadata.empty().withoutCallSite()).isSameAs(LlmCallMetadata.empty());
    }

    @Test
    @DisplayName("withoutCallSite of metadata that carries only a call site is empty")
    void withoutCallSiteOfCallSiteOnlyIsEmpty() {
        assertThat(LlmCallMetadata.builder().component("agent").feature("f").build().withoutCallSite().isEmpty())
                .isTrue();
    }

    @Test
    @DisplayName("merged under engine defaults, the stripped metadata keeps the engine's attribution and the tags")
    void strippedMetadataKeepsTheCalleesAttribution() {
        final LlmCallMetadata turn = LlmCallMetadata.builder().component("agent")
                .feature(LlmCallMetadata.Feature.REACT_LOOP).tag("routing", "review").build();
        final LlmCallMetadata engineDefaults = LlmCallMetadata.builder().component("compaction-engine")
                .feature(LlmCallMetadata.Feature.COMPACTION).traceId("session-1").build();

        final LlmCallMetadata merged = turn.withoutCallSite().withDefaults(engineDefaults);

        assertThat(merged.getComponent()).contains("compaction-engine");
        assertThat(merged.getFeature()).contains(LlmCallMetadata.Feature.COMPACTION);
        assertThat(merged.getTags()).containsEntry("routing", "review");
    }

    @Test
    @DisplayName("withPrincipal replaces the principal and keeps every other field")
    void withPrincipalReplacesOnlyThePrincipal() {
        final LlmCallMetadata metadata = LlmCallMetadata.builder().component("agent").parentComponent("parent")
                .feature("f").principal(Principal.user("bob")).traceId("session-1").tag("routing", "review").build();

        assertThat(metadata.withPrincipal(Principal.user("alice")))
                .isEqualTo(LlmCallMetadata.builder().component("agent").parentComponent("parent").feature("f")
                        .principal(Principal.user("alice")).traceId("session-1").tag("routing", "review").build());
    }

    @Test
    @DisplayName("withPrincipal of null, or of the principal already set, returns the same instance")
    void withPrincipalIsIdentityForNullOrSamePrincipal() {
        final LlmCallMetadata metadata = LlmCallMetadata.builder().principal(Principal.user("bob")).build();

        assertThat(metadata.withPrincipal(null)).isSameAs(metadata);
        assertThat(metadata.withPrincipal(Principal.user("bob"))).isSameAs(metadata);
        assertThat(LlmCallMetadata.empty().withPrincipal(Principal.user("alice")).getPrincipal())
                .contains(Principal.user("alice"));
    }
}
