package at.aimon.core.agent.tool.search;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.Tool;

@DisplayName("ToolSearchCatalog Tests")
class ToolSearchCatalogTest {

    private ToolSearchCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new ToolSearchCatalog(new KeywordToolSearchStrategy());
    }

    @Nested
    @DisplayName("Registration")
    class Registration {

        @Test
        @DisplayName("register(Tool) registers as EAGER")
        void testRegisterTool() {
            catalog.register(MockTools.create("Read", "Read files"));

            assertThat(catalog.getEagerTools()).hasSize(1);
            assertThat(catalog.getDeferredTools()).isEmpty();
        }

        @Test
        @DisplayName("register(SearchableTool) with DEFERRED mode")
        void testRegisterDeferred() {
            catalog.register(SearchableTool.deferred(MockTools.create("WebFetch", "Fetch web")));

            assertThat(catalog.getEagerTools()).isEmpty();
            assertThat(catalog.getDeferredTools()).hasSize(1);
        }

        @Test
        @DisplayName("register(SearchableTool) with EAGER mode")
        void testRegisterEager() {
            catalog.register(SearchableTool.eager(MockTools.create("Read", "Read files")));

            assertThat(catalog.getEagerTools()).hasSize(1);
            assertThat(catalog.getDeferredTools()).isEmpty();
        }
    }

    @Nested
    @DisplayName("findAll (ToolRegistry contract)")
    class FindAll {

        @Test
        @DisplayName("findAll() returns only eager tools")
        void testFindAllEagerOnly() {
            catalog.register(MockTools.create("Read", "Read files"));
            catalog.register(SearchableTool.deferred(MockTools.create("WebFetch", "Fetch web")));

            final List<Tool> tools = catalog.findAll();

            assertThat(tools).hasSize(1);
            assertThat(tools.get(0).getDefinition().getName()).isEqualTo("Read");
        }

        @Test
        @DisplayName("findAllExcept() excludes specified eager tools")
        void testFindAllExcept() {
            catalog.register(MockTools.create("Read", "Read files"));
            catalog.register(MockTools.create("Write", "Write files"));

            final List<Tool> tools = catalog.findAllExcept("Read");

            assertThat(tools).hasSize(1);
            assertThat(tools.get(0).getDefinition().getName()).isEqualTo("Write");
        }
    }

    @Nested
    @DisplayName("findByName")
    class FindByName {

        @Test
        @DisplayName("findByName() finds eager tools")
        void testFindEager() {
            catalog.register(MockTools.create("Read", "Read files"));

            assertThat(catalog.findByName("Read")).isPresent();
        }

        @Test
        @DisplayName("findByName() finds deferred tools")
        void testFindDeferred() {
            catalog.register(SearchableTool.deferred(MockTools.create("WebFetch", "Fetch web")));

            assertThat(catalog.findByName("WebFetch")).isPresent();
        }

        @Test
        @DisplayName("findByName() strips functions. prefix")
        void testFunctionsPrefix() {
            catalog.register(MockTools.create("Read", "Read files"));

            assertThat(catalog.findByName("functions.Read")).isPresent();
        }

        @Test
        @DisplayName("findByName() returns empty for unknown tool")
        void testNotFound() {
            assertThat(catalog.findByName("NonExistent")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Search")
    class Search {

        @Test
        @DisplayName("search() only searches deferred tools")
        void testSearchDeferred() {
            catalog.register(MockTools.create("Read", "Read files"));
            catalog.register(SearchableTool.deferred(MockTools.create("WebFetch", "Fetch web content")));

            final List<SearchableTool> results = catalog.search("fetch", 5);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getName()).isEqualTo("WebFetch");
        }

        @Test
        @DisplayName("search() does not modify activation state")
        void testSearchPure() {
            catalog.register(SearchableTool.deferred(MockTools.create("WebFetch", "Fetch web content")));

            catalog.search("fetch", 5);

            // No activation state to check on catalog itself — it's stateless
            assertThat(catalog.getEagerTools()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Select")
    class Select {

        @Test
        @DisplayName("select() returns matching deferred tools")
        void testSelectExisting() {
            catalog.register(SearchableTool.deferred(MockTools.create("Read", "Read files")));
            catalog.register(SearchableTool.deferred(MockTools.create("Edit", "Edit files")));

            final List<SearchableTool> results = catalog.select(List.of("Read", "Edit"));

            assertThat(results).hasSize(2);
        }

        @Test
        @DisplayName("select() ignores non-existent tools silently")
        void testSelectNonExistent() {
            catalog.register(SearchableTool.deferred(MockTools.create("Read", "Read files")));

            final List<SearchableTool> results = catalog.select(List.of("Read", "NonExistent"));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getName()).isEqualTo("Read");
        }

        @Test
        @DisplayName("select() does not return eager tools")
        void testSelectEager() {
            catalog.register(SearchableTool.eager(MockTools.create("Read", "Read files")));

            final List<SearchableTool> results = catalog.select(List.of("Read"));

            assertThat(results).isEmpty();
        }
    }

    @Nested
    @DisplayName("searchByRequired")
    class SearchByRequired {

        @Test
        @DisplayName("Should filter and rank")
        void testSearchByRequired() {
            catalog.register(
                    SearchableTool.deferred(MockTools.create("mcp__slack__send_message", "Send Slack message")));
            catalog.register(
                    SearchableTool.deferred(MockTools.create("mcp__slack__list_channels", "List Slack channels")));
            catalog.register(SearchableTool.deferred(MockTools.create("mcp__github__create_pr", "Create GitHub PR")));

            final List<SearchableTool> results = catalog.searchByRequired("slack", List.of("send"), 5);

            assertThat(results).hasSize(2);
            assertThat(results.get(0).getName()).isEqualTo("mcp__slack__send_message");
        }
    }

    @Nested
    @DisplayName("createRegistry")
    class CreateRegistry {

        @Test
        @DisplayName("Creates independent ToolSearchRegistry instances")
        void testCreateRegistry() {
            catalog.register(MockTools.create("Read", "Read files"));
            catalog.register(SearchableTool.deferred(MockTools.create("WebFetch", "Fetch web")));

            final ToolSearchRegistry regA = catalog.createRegistry();
            final ToolSearchRegistry regB = catalog.createRegistry();

            // Activate in A should not affect B
            regA.searchAndActivate("select:WebFetch", 5);

            assertThat(regA.findAll()).hasSize(2); // Read + WebFetch
            assertThat(regB.findAll()).hasSize(1); // Read only
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("size() counts all tools")
        void testSize() {
            catalog.register(MockTools.create("Read", "Read files"));
            catalog.register(SearchableTool.deferred(MockTools.create("WebFetch", "Fetch web")));

            assertThat(catalog.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("isEmpty() is true when no tools registered")
        void testEmpty() {
            assertThat(catalog.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("clear() removes all tools")
        void testClear() {
            catalog.register(MockTools.create("Read", "Read files"));
            catalog.clear();

            assertThat(catalog.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("unregister() removes a tool")
        void testUnregister() {
            catalog.register(MockTools.create("Read", "Read files"));
            catalog.unregister("Read");

            assertThat(catalog.findByName("Read")).isEmpty();
        }
    }
}
