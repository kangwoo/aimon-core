package at.aimon.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.orca.OrcaProviderDependencies;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.credential.InMemoryCredentialStore;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.tools.web.fetch.ContentExtractor;
import at.aimon.core.tools.web.security.SsrfGuard;

class OrcaBrowserToolProviderTest {

    private final SsrfGuard ssrfGuard = mock(SsrfGuard.class);
    private final ContentExtractor contentExtractor = mock(ContentExtractor.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PlaywrightWorkerPool workerPool = mock(PlaywrightWorkerPool.class);
    private final BrowserSessionStore sessionStore = mock(BrowserSessionStore.class);

    private final OrcaBrowserToolProvider provider = new OrcaBrowserToolProvider(ssrfGuard, contentExtractor,
            objectMapper, workerPool, sessionStore);

    @Test
    void shouldRegisterBrowserTool() {
        ToolRegistry registry = new DefaultToolRegistry();
        OrcaToolProviderContext context = OrcaToolProviderContext.builder()
                .dependencies(OrcaProviderDependencies.builder().build()).build();

        provider.registerTools(registry, context);

        assertThat(registry.findByName("Browser")).isPresent();
    }

    @Test
    void shouldThrowWhenSsrfGuardIsNull() {
        assertThatThrownBy(
                () -> new OrcaBrowserToolProvider(null, contentExtractor, objectMapper, workerPool, sessionStore))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldThrowWhenContentExtractorIsNull() {
        assertThatThrownBy(() -> new OrcaBrowserToolProvider(ssrfGuard, null, objectMapper, workerPool, sessionStore))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldThrowWhenObjectMapperIsNull() {
        assertThatThrownBy(
                () -> new OrcaBrowserToolProvider(ssrfGuard, contentExtractor, null, workerPool, sessionStore))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldThrowWhenWorkerPoolIsNull() {
        assertThatThrownBy(
                () -> new OrcaBrowserToolProvider(ssrfGuard, contentExtractor, objectMapper, null, sessionStore))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldThrowWhenSessionStoreIsNull() {
        assertThatThrownBy(
                () -> new OrcaBrowserToolProvider(ssrfGuard, contentExtractor, objectMapper, workerPool, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldThrowWhenRegistryIsNull() {
        OrcaToolProviderContext context = OrcaToolProviderContext.builder()
                .dependencies(OrcaProviderDependencies.builder().build()).build();

        assertThatThrownBy(() -> provider.registerTools(null, context)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldThrowWhenContextIsNull() {
        ToolRegistry registry = new DefaultToolRegistry();

        assertThatThrownBy(() -> provider.registerTools(registry, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRegisterSaveAuthHandler() {
        ToolRegistry registry = new DefaultToolRegistry();
        OrcaToolProviderContext context = OrcaToolProviderContext.builder()
                .dependencies(OrcaProviderDependencies.builder().build()).build();

        provider.registerTools(registry, context);

        Tool tool = registry.findByName("Browser").orElseThrow();
        Map<String, Object> schema = tool.getDefinition().getInputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> actionProp = (Map<String, Object>) properties.get("action");
        List<String> actionEnum = (List<String>) actionProp.get("enum");

        assertThat(actionEnum).contains("save_auth");
    }

    @Test
    void shouldAcceptCredentialStoreFromContext() {
        ToolRegistry registry = new DefaultToolRegistry();
        OrcaToolProviderContext context = OrcaToolProviderContext.builder()
                .dependencies(OrcaProviderDependencies.builder()
                        .credentialStore(
                                InMemoryCredentialStore.builder().profile("test", Map.of("key", "value")).build())
                        .build())
                .build();

        provider.registerTools(registry, context);

        assertThat(registry.findByName("Browser")).isPresent();
    }

    @Test
    void shouldRegisterBaseBrowserToolWhenArtifactDisabled() {
        OrcaBrowserToolProvider disabledProvider = new OrcaBrowserToolProvider(ssrfGuard, contentExtractor,
                objectMapper, workerPool, sessionStore, false);

        ToolRegistry registry = new DefaultToolRegistry();
        OrcaToolProviderContext context = OrcaToolProviderContext.builder()
                .dependencies(OrcaProviderDependencies.builder().build()).build();

        disabledProvider.registerTools(registry, context);

        Tool tool = registry.findByName("Browser").orElseThrow();
        assertThat(tool).isInstanceOf(BrowserTool.class);
    }

    @Test
    void shouldRegisterArtifactBrowserToolWhenArtifactEnabled() {
        OrcaBrowserToolProvider enabledProvider = new OrcaBrowserToolProvider(ssrfGuard, contentExtractor, objectMapper,
                workerPool, sessionStore, true);

        VirtualFileSystem fileSystem = mock(VirtualFileSystem.class);
        ToolRegistry registry = new DefaultToolRegistry();
        OrcaToolProviderContext context = OrcaToolProviderContext.builder().fileSystem(fileSystem)
                .dependencies(OrcaProviderDependencies.builder().build()).build();

        enabledProvider.registerTools(registry, context);

        Tool tool = registry.findByName("Browser").orElseThrow();
        assertThat(tool).isInstanceOf(at.aimon.browser.playwright.artifact.ArtifactAwareBrowserTool.class);
    }

    @Test
    void shouldThrowWhenArtifactEnabledButFileSystemIsNull() {
        OrcaBrowserToolProvider enabledProvider = new OrcaBrowserToolProvider(ssrfGuard, contentExtractor, objectMapper,
                workerPool, sessionStore, true);

        ToolRegistry registry = new DefaultToolRegistry();
        OrcaToolProviderContext context = OrcaToolProviderContext.builder()
                .dependencies(OrcaProviderDependencies.builder().build()).build();

        assertThatThrownBy(() -> enabledProvider.registerTools(registry, context))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("fileSystem");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldIncludeCredentialRefInSchema() {
        ToolRegistry registry = new DefaultToolRegistry();
        OrcaToolProviderContext context = OrcaToolProviderContext.builder()
                .dependencies(OrcaProviderDependencies.builder().build()).build();

        provider.registerTools(registry, context);

        Tool tool = registry.findByName("Browser").orElseThrow();
        Map<String, Object> schema = tool.getDefinition().getInputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties).containsKey("credential_ref");
    }

    @Test
    void shouldIncludeCredentialProfilesInDescription() {
        ToolRegistry registry = new DefaultToolRegistry();
        OrcaToolProviderContext context = OrcaToolProviderContext.builder()
                .dependencies(OrcaProviderDependencies.builder()
                        .credentialStore(
                                InMemoryCredentialStore.builder().profile("jira", Map.of("username", "admin")).build())
                        .build())
                .build();

        provider.registerTools(registry, context);

        Tool tool = registry.findByName("Browser").orElseThrow();
        String description = tool.getDefinition().getDescription();
        assertThat(description).contains("Available credential profiles");
        assertThat(description).contains("jira");
        assertThat(description).contains("username");
    }
}
