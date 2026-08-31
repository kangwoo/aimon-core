package at.aimon.core.agent.tool.search;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.Tool;

@DisplayName("ToolSearchRegistry Tests")
class ToolSearchRegistryTest {

    private ToolSearchCatalog catalog;
    private ToolSearchRegistry registry;

    @BeforeEach
    void setUp() {
        catalog = new ToolSearchCatalog(new KeywordToolSearchStrategy());
        catalog.register(MockTools.create("Read", "Read files"));
        catalog.register(MockTools.create("Write", "Write files"));
        catalog.register(SearchableTool.deferred(MockTools.create("WebFetch", "Fetch web content")));
        catalog.register(SearchableTool.deferred(MockTools.create("WebSearch", "Search the web")));
        catalog.register(SearchableTool.deferred(MockTools.create("Grep", "Search file contents")));

        registry = catalog.createRegistry();
    }

    @Nested
    @DisplayName("findAll")
    class FindAll {

        @Test
        @DisplayName("Returns only eager tools initially")
        void testInitialFindAll() {
            final List<Tool> tools = registry.findAll();

            assertThat(tools).hasSize(2);
            assertThat(tools).extracting(t -> t.getDefinition().getName()).containsExactlyInAnyOrder("Read", "Write");
        }

        @Test
        @DisplayName("Returns eager + activated deferred tools after search")
        void testFindAllAfterActivation() {
            registry.searchAndActivate("select:WebFetch", 5);

            final List<Tool> tools = registry.findAll();

            assertThat(tools).hasSize(3);
            assertThat(tools).extracting(t -> t.getDefinition().getName()).containsExactlyInAnyOrder("Read", "Write",
                    "WebFetch");
        }
    }

    @Nested
    @DisplayName("findByName")
    class FindByName {

        @Test
        @DisplayName("Finds any tool regardless of activation")
        void testFindByName() {
            assertThat(registry.findByName("Read")).isPresent();
            assertThat(registry.findByName("WebFetch")).isPresent();
        }
    }

    @Nested
    @DisplayName("searchAndActivate")
    class SearchAndActivate {

        @Test
        @DisplayName("Keyword search activates matching tools")
        void testKeywordSearch() {
            final List<SearchableTool> results = registry.searchAndActivate("web", 5);

            assertThat(results).extracting(SearchableTool::getName).containsExactlyInAnyOrder("WebFetch", "WebSearch");
            assertThat(registry.getActivationState().isActivated("WebFetch")).isTrue();
            assertThat(registry.getActivationState().isActivated("WebSearch")).isTrue();
        }

        @Test
        @DisplayName("Direct selection activates tools")
        void testDirectSelect() {
            final List<SearchableTool> results = registry.searchAndActivate("select:WebFetch,Grep", 5);

            assertThat(results).hasSize(2);
            assertThat(registry.findAll()).hasSize(4); // Read, Write, WebFetch, Grep
        }

        @Test
        @DisplayName("Required keyword search activates tools")
        void testRequiredKeyword() {
            final List<SearchableTool> results = registry.searchAndActivate("+web fetch", 5);

            assertThat(results).isNotEmpty();
            assertThat(results).extracting(SearchableTool::getName).allMatch(n -> n.toLowerCase().contains("web"));
        }

        @Test
        @DisplayName("Idempotent: re-search same tool does not cause issues")
        void testIdempotent() {
            registry.searchAndActivate("select:WebFetch", 5);
            registry.searchAndActivate("select:WebFetch", 5);

            assertThat(registry.findAll()).hasSize(3); // Read, Write, WebFetch
        }

        @Test
        @DisplayName("No results for unmatched query")
        void testNoResults() {
            final List<SearchableTool> results = registry.searchAndActivate("blockchain", 5);

            assertThat(results).isEmpty();
            assertThat(registry.findAll()).hasSize(2); // Just eager tools
        }
    }

    @Nested
    @DisplayName("Read-only View")
    class ReadOnlyView {

        @Test
        @DisplayName("register() throws UnsupportedOperationException")
        void testRegisterThrows() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> registry.register(MockTools.create("New", "desc")));
        }

        @Test
        @DisplayName("unregister() throws UnsupportedOperationException")
        void testUnregisterThrows() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> registry.unregister("Read"));
        }

        @Test
        @DisplayName("clear() throws UnsupportedOperationException")
        void testClearThrows() {
            assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> registry.clear());
        }
    }

    @Nested
    @DisplayName("findAllExcept")
    class FindAllExcept {

        @Test
        @DisplayName("Excludes specified tools from result")
        void testExclude() {
            registry.searchAndActivate("select:WebFetch", 5);

            final List<Tool> tools = registry.findAllExcept("Read");

            assertThat(tools).extracting(t -> t.getDefinition().getName()).containsExactlyInAnyOrder("Write",
                    "WebFetch");
        }
    }

    @Nested
    @DisplayName("Session Isolation")
    class SessionIsolation {

        @Test
        @DisplayName("Separate registries have independent activation state")
        void testIsolation() {
            final ToolSearchRegistry registryA = catalog.createRegistry();
            final ToolSearchRegistry registryB = catalog.createRegistry();

            registryA.searchAndActivate("select:WebFetch", 5);

            assertThat(registryA.findAll()).hasSize(3); // Read, Write, WebFetch
            assertThat(registryB.findAll()).hasSize(2); // Read, Write only
        }
    }
}
