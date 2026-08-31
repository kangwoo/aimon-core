package at.aimon.core.config.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.skill.hook.declarative.NoOpShellActionExecutor;

@DisplayName("HookRegistryApplier")
class HookRegistryApplierTest {

    private final JacksonHookConfigParser parser = new JacksonHookConfigParser();
    private final HookConfigMerger merger = new HookConfigMerger();

    private HookRegistryApplier bootstrap() {
        return new HookRegistryApplier(NoOpShellActionExecutor.INSTANCE, null, null, Map.of());
    }

    @Test
    @DisplayName("preTool command entries are registered as pre-tool hooks")
    void preToolCommandRegistered() {
        final HookConfigDocument doc = parser.parse(
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"x\"}]}]}}");
        final MergedHookConfig merged = merger
                .merge(LayeredHookConfig.builder().put(HookConfigSource.PROJECT, doc).build());

        final DefaultHookRegistry registry = new DefaultHookRegistry();
        bootstrap().apply(merged, registry);

        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(1);
        assertThat(registry.getHooks(HookEventType.POST_TOOL)).isEmpty();
    }

    @Test
    @DisplayName("postTool entries are registered as post-tool hooks")
    void postToolCommandRegistered() {
        final HookConfigDocument doc = parser.parse(
                "{\"hooks\":{\"PostToolUse\":[{\"matcher\":\"*\",\"hooks\":[{\"type\":\"command\",\"command\":\"x\"}]}]}}");
        final MergedHookConfig merged = merger
                .merge(LayeredHookConfig.builder().put(HookConfigSource.PROJECT, doc).build());

        final DefaultHookRegistry registry = new DefaultHookRegistry();
        bootstrap().apply(merged, registry);

        assertThat(registry.getHooks(HookEventType.POST_TOOL)).hasSize(1);
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).isEmpty();
    }

    @Test
    @DisplayName("type=deny on postTool is skipped with no registration")
    void denyOnPostToolSkipped() {
        final HookConfigDocument doc = parser.parse(
                "{\"hooks\":{\"PostToolUse\":[{\"matcher\":\"*\",\"hooks\":[{\"type\":\"deny\",\"reason\":\"nope\"}]}]}}");
        final MergedHookConfig merged = merger
                .merge(LayeredHookConfig.builder().put(HookConfigSource.PROJECT, doc).build());

        final DefaultHookRegistry registry = new DefaultHookRegistry();
        bootstrap().apply(merged, registry);

        assertThat(registry.getHooks(HookEventType.POST_TOOL)).isEmpty();
    }

    @Test
    @DisplayName("non-shell action on onStart/onStop is skipped")
    void nonShellOnLifecycleSkipped() {
        final HookConfigDocument doc = parser.parse(
                "{\"hooks\":{\"Stop\":[{\"hooks\":[{\"type\":\"http\"," + "\"url\":\"https://example.test/h\"}]}]}}");
        final MergedHookConfig merged = merger
                .merge(LayeredHookConfig.builder().put(HookConfigSource.PROJECT, doc).build());

        final DefaultHookRegistry registry = new DefaultHookRegistry();
        bootstrap().apply(merged, registry);

        assertThat(registry.getHooks(HookEventType.ON_STOP)).isEmpty();
    }

    @Test
    @DisplayName("onStart shell entries are registered as on-start hooks")
    void onStartShellRegistered() {
        final HookConfigDocument doc = parser
                .parse("{\"hooks\":{\"onStart\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"echo hi\"}]}]}}");
        final MergedHookConfig merged = merger
                .merge(LayeredHookConfig.builder().put(HookConfigSource.PROJECT, doc).build());

        final DefaultHookRegistry registry = new DefaultHookRegistry();
        bootstrap().apply(merged, registry);

        assertThat(registry.getHooks(HookEventType.ON_START)).hasSize(1);
    }

    @Test
    @DisplayName("empty handler list is skipped with WARN (no hooks registered)")
    void emptyHandlersSkipped() {
        final HookConfigDocument doc = parser
                .parse("{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":[]}]}}");
        final MergedHookConfig merged = merger
                .merge(LayeredHookConfig.builder().put(HookConfigSource.PROJECT, doc).build());

        final DefaultHookRegistry registry = new DefaultHookRegistry();
        bootstrap().apply(merged, registry);

        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).isEmpty();
    }

    @Test
    @DisplayName("invalid handler (command without 'command' field) is skipped, others survive")
    void invalidHandlerSkipped() {
        final HookConfigDocument doc = parser.parse("{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":["
                + "{\"type\":\"command\"}," + "{\"type\":\"command\",\"command\":\"ok\"}" + "]}]}}");
        final MergedHookConfig merged = merger
                .merge(LayeredHookConfig.builder().put(HookConfigSource.PROJECT, doc).build());

        final DefaultHookRegistry registry = new DefaultHookRegistry();
        bootstrap().apply(merged, registry);

        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(1);
    }

    @Test
    @DisplayName("SKILL entries are skipped (handled by SkillHookActivator)")
    void skillEntriesSkipped() {
        final HookConfigDocument doc = parser.parse(
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"x\"}]}]}}");
        final MergedHookConfig merged = merger.merge(LayeredHookConfig.builder().putSkill("my-skill", doc).build());

        final DefaultHookRegistry registry = new DefaultHookRegistry();
        bootstrap().apply(merged, registry);

        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).isEmpty();
    }

    @Test
    @DisplayName("USER -> PROJECT -> LOCAL precedence is preserved as registration order")
    void layeredOrderPreserved() {
        final HookConfigDocument user = parser.parse(
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"u\"}]}]}}");
        final HookConfigDocument project = parser.parse(
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"p\"}]}]}}");
        final HookConfigDocument local = parser.parse(
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"l\"}]}]}}");
        final MergedHookConfig merged = merger.merge(LayeredHookConfig.builder().put(HookConfigSource.USER, user)
                .put(HookConfigSource.PROJECT, project).put(HookConfigSource.LOCAL, local).build());

        final DefaultHookRegistry registry = new DefaultHookRegistry();
        bootstrap().apply(merged, registry);

        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(3);
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).extracting(h -> h.getClass().getSimpleName())
                .containsOnly("DeclarativePreToolHook");
    }

    @Test
    @DisplayName("invalid matcher falls back to name-only without throwing")
    void invalidMatcherFallback() {
        final HookConfigDocument doc = parser.parse("{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"\\u0000(\","
                + "\"hooks\":[{\"type\":\"command\",\"command\":\"x\"}]}]}}");
        final MergedHookConfig merged = merger
                .merge(LayeredHookConfig.builder().put(HookConfigSource.PROJECT, doc).build());

        final DefaultHookRegistry registry = new DefaultHookRegistry();
        bootstrap().apply(merged, registry);

        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(1);
    }

    /**
     * The discriminator fed to {@code DeclarativeHookId} is the contract that async-rewake routing and hot-reload
     * cancellation depend on: a hook's id must be unique among its siblings and must not move when an unrelated
     * document changes.
     */
    @Nested
    @DisplayName("hook id stability")
    class HookIdStability {

        private static final String USER_DOC = "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\","
                + "\"hooks\":[{\"type\":\"command\",\"command\":\"user-a\"}]}]}}";
        private static final String USER_DOC_TWO_HANDLERS = "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\","
                + "\"hooks\":[{\"type\":\"command\",\"command\":\"a\"},"
                + "{\"type\":\"command\",\"command\":\"b\"}]}]}}";
        private static final String PROJECT_DOC = "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Read\","
                + "\"hooks\":[{\"type\":\"command\",\"command\":\"project-a\"}]}]}}";

        private List<String> preToolHookIds(LayeredHookConfig layered) {
            final DefaultHookRegistry registry = new DefaultHookRegistry();
            bootstrap().apply(merger.merge(layered), registry);
            return registry.getHooks(HookEventType.PRE_TOOL).stream().map(h -> h.getHookId()).toList();
        }

        @Test
        @DisplayName("two hooks declared in the same document get different ids")
        void siblingsInOneDocumentGetDistinctIds() {
            final List<String> ids = preToolHookIds(LayeredHookConfig.builder()
                    .put(HookConfigSource.USER, parser.parse(USER_DOC_TWO_HANDLERS)).build());

            assertThat(ids).hasSize(2).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("entries and handlers across one document are all distinct")
        void everyRegisteredHookHasAUniqueId() {
            final String doc = "{\"hooks\":{\"PreToolUse\":["
                    + "{\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"a\"},"
                    + "{\"type\":\"command\",\"command\":\"b\"}]},"
                    + "{\"matcher\":\"Read\",\"hooks\":[{\"type\":\"command\",\"command\":\"c\"}]}]}}";

            final List<String> ids = preToolHookIds(
                    LayeredHookConfig.builder().put(HookConfigSource.PROJECT, parser.parse(doc)).build());

            assertThat(ids).hasSize(3).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a hook keeps the same id across a reload of identical config")
        void idsAreStableAcrossReloadOfIdenticalConfig() {
            final List<String> first = preToolHookIds(
                    LayeredHookConfig.builder().put(HookConfigSource.USER, parser.parse(USER_DOC))
                            .put(HookConfigSource.PROJECT, parser.parse(PROJECT_DOC)).build());
            final List<String> second = preToolHookIds(
                    LayeredHookConfig.builder().put(HookConfigSource.USER, parser.parse(USER_DOC))
                            .put(HookConfigSource.PROJECT, parser.parse(PROJECT_DOC)).build());

            assertThat(second).isEqualTo(first);
        }

        /**
         * Regression guard: the entry index used to be counted across the merged dispatch stream, so adding a PROJECT
         * entry shifted every USER hook's id and orphaned its pending rewake envelopes.
         */
        @Test
        @DisplayName("a hook's id is unchanged when an unrelated hook is added to a different layer")
        void idIsUnaffectedByEditsInAnotherLayer() {
            final List<String> userOnly = preToolHookIds(
                    LayeredHookConfig.builder().put(HookConfigSource.USER, parser.parse(USER_DOC)).build());

            final List<String> withProject = preToolHookIds(
                    LayeredHookConfig.builder().put(HookConfigSource.USER, parser.parse(USER_DOC))
                            .put(HookConfigSource.PROJECT, parser.parse(PROJECT_DOC)).build());

            assertThat(userOnly).hasSize(1);
            assertThat(withProject).hasSize(2).containsAll(userOnly).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("removing a lower-precedence layer does not re-id the surviving layer's hooks")
        void idIsUnaffectedByRemovalOfAnotherLayer() {
            final List<String> both = preToolHookIds(
                    LayeredHookConfig.builder().put(HookConfigSource.USER, parser.parse(USER_DOC))
                            .put(HookConfigSource.PROJECT, parser.parse(PROJECT_DOC)).build());

            final List<String> projectOnly = preToolHookIds(
                    LayeredHookConfig.builder().put(HookConfigSource.PROJECT, parser.parse(PROJECT_DOC)).build());

            assertThat(projectOnly).hasSize(1);
            assertThat(both).containsAll(projectOnly);
        }
    }
}
