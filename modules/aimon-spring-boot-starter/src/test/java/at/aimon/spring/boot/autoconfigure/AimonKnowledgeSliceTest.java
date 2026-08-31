package at.aimon.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import at.aimon.core.knowledge.KeywordKnowledgeStore;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.spring.boot.autoconfigure.AimonKnowledgeAutoConfiguration.KnowledgeContribution;

/**
 * The knowledge slice on its own, with no stack behind it.
 *
 * <p>
 * Three values and four outcomes, which is one more than the selector has values: {@code supplied} means
 * something different depending on whether the application actually supplied anything, and both halves have to
 * fail loudly rather than produce a store-shaped hole. The contribution is a value rather than a nullable bean
 * precisely so the "off" case can be asserted as a bean that is present and empty.
 */
class AimonKnowledgeSliceTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AimonKnowledgeAutoConfiguration.class))
            .withPropertyValues("aimon.workspace.root=/workspace", "aimon.agent-defaults.default-agent=test-agent");

    @Test
    @DisplayName("no backend named means no store and a contribution that says so")
    void knowledgeIsOffByDefault() {
        // The contribution bean still exists — that is the shape the spec factory depends on. An ObjectProvider
        // of KnowledgeStore would have given the same "absent" reading for "off" and for "the application's bean
        // failed to construct", and only one of those should start a context.
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(KnowledgeStore.class);
            assertThat(ctx).hasSingleBean(KnowledgeContribution.class);
            assertThat(ctx.getBean(KnowledgeContribution.class).getStore()).isNull();
        });
    }

    @Test
    @DisplayName("keyword builds the in-memory store and hands it to the contribution")
    void keywordBuildsTheStore() {
        runner.withPropertyValues("aimon.knowledge.backend=keyword").run(ctx -> {
            assertThat(ctx).hasSingleBean(KnowledgeStore.class);
            assertThat(ctx.getBean(KnowledgeStore.class)).isInstanceOf(KeywordKnowledgeStore.class);
            // Same instance, not merely the same type: two stores would mean documents indexed into one and
            // searched in the other, which reads as an empty knowledge base rather than as a wiring fault.
            assertThat(ctx.getBean(KnowledgeContribution.class).getStore()).isSameAs(ctx.getBean(KnowledgeStore.class));
        });
    }

    @Test
    @DisplayName("supplied adopts the application's store and builds none of its own")
    void suppliedAdoptsTheApplicationBean() {
        runner.withUserConfiguration(SuppliedStore.class).withPropertyValues("aimon.knowledge.backend=supplied")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(KnowledgeStore.class);
                    assertThat(ctx.getBean(KnowledgeContribution.class).getStore())
                            .isSameAs(ctx.getBean(KnowledgeStore.class));
                });
    }

    @Test
    @DisplayName("supplied with nothing supplied fails, naming the value that would have built one")
    void suppliedWithoutABeanFailsByName() {
        runner.withPropertyValues("aimon.knowledge.backend=supplied")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.KNOWLEDGE_BACKEND)
                        .hasStackTraceContaining(KnowledgeStore.class.getName()));
    }

    @Test
    @DisplayName("a store bean under backend=none is refused rather than quietly ignored")
    void aStoreWithNoBackendIsRefusedByName() {
        // The failure mode this prevents is the expensive one: an application indexes documents into a store
        // that starts and works, and no agent is ever given a tool that can reach it. Nothing errors, and the
        // model simply answers as if the corpus did not exist.
        runner.withUserConfiguration(SuppliedStore.class)
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.KNOWLEDGE_BACKEND)
                        .hasStackTraceContaining(KnowledgeStore.class.getName()));
    }

    @Test
    @DisplayName("an application store wins over the keyword branch")
    void anApplicationStoreBacksOffTheKeywordBranch() {
        // @ConditionalOnMissingBean on the keyword branch, asserted rather than assumed: the alternative is two
        // KnowledgeStore beans and an injection point that fails on ambiguity at the far end of the context.
        runner.withUserConfiguration(SuppliedStore.class).withPropertyValues("aimon.knowledge.backend=keyword")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(KnowledgeStore.class);
                    assertThat(ctx.getBean(KnowledgeStore.class)).isNotInstanceOf(KeywordKnowledgeStore.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class SuppliedStore {

        @Bean
        KnowledgeStore applicationKnowledgeStore() {
            return mock(KnowledgeStore.class);
        }
    }
}
