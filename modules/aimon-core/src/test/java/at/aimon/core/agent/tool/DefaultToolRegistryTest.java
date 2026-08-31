package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultToolRegistry Tests")
class DefaultToolRegistryTest {

    private DefaultToolRegistry registry;
    private Tool mockTool1;
    private Tool mockTool2;

    @BeforeEach
    void setUp() {
        registry = new DefaultToolRegistry();
        mockTool1 = new MockTool("tool1", "First tools");
        mockTool2 = new MockTool("tool2", "Second tools");
    }

    @Nested
    @DisplayName("Registration")
    class Registration {
        @Test
        @DisplayName("Should register tools successfully")
        void testRegisterTool() {
            registry.register(mockTool1);

            assertThat(registry.hasToolNamed("tool1")).isTrue();
            assertThat(registry.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should register multiple tools")
        void testRegisterMultipleTools() {
            registry.register(mockTool1);
            registry.register(mockTool2);

            assertThat(registry.size()).isEqualTo(2);
            assertThat(registry.getToolNames()).containsExactlyInAnyOrder("tool1", "tool2");
        }

        @Test
        @DisplayName("Should replace existing tools with same name")
        void testReplaceExistingTool() {
            registry.register(mockTool1);
            Tool replacementTool = new MockTool("tool1", "Replacement tools");
            registry.register(replacementTool);

            assertThat(registry.size()).isEqualTo(1);
            assertThat(registry.findByName("tool1")).hasValue(replacementTool);
        }

        @Test
        @DisplayName("Should reject null tools")
        void testRejectNullTool() {
            assertThatThrownBy(() -> registry.register(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Tool cannot be null");
        }
    }

    @Nested
    @DisplayName("Unregistration")
    class Unregistration {
        @Test
        @DisplayName("Should unregister tools successfully")
        void testUnregisterTool() {
            registry.register(mockTool1);
            registry.unregister("tool1");

            assertThat(registry.hasToolNamed("tool1")).isFalse();
            assertThat(registry.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should handle unregistering non-existent tools")
        void testUnregisterNonExistentTool() {
            assertThatNoException().isThrownBy(() -> registry.unregister("nonexistent"));
        }

        @Test
        @DisplayName("Should reject null tools name")
        void testUnregisterRejectsNull() {
            assertThatThrownBy(() -> registry.unregister(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Tool name cannot be null");
        }
    }

    @Nested
    @DisplayName("Tool Retrieval")
    class ToolRetrieval {
        @Test
        @DisplayName("Should get tools by name")
        void testGetTool() {
            registry.register(mockTool1);

            var retrieved = registry.findByName("tool1");

            assertThat(retrieved).isPresent().hasValue(mockTool1);
        }

        @Test
        @DisplayName("Should return empty Optional for non-existent tools")
        void testGetNonExistentTool() {
            var retrieved = registry.findByName("nonexistent");

            assertThat(retrieved).isEmpty();
        }

        @Test
        @DisplayName("Should reject null tools name in getTool")
        void testGetToolRejectsNull() {
            assertThatThrownBy(() -> registry.findByName(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Tool name cannot be null");
        }
    }

    @Nested
    @DisplayName("Tool Names")
    class ToolNames {
        @Test
        @DisplayName("Should get all tools names")
        void testGetToolNames() {
            registry.register(mockTool1);
            registry.register(mockTool2);

            Set<String> names = registry.getToolNames();

            assertThat(names).containsExactlyInAnyOrder("tool1", "tool2");
        }

        @Test
        @DisplayName("Should return empty set when no tools registered")
        void testGetToolNamesEmpty() {
            Set<String> names = registry.getToolNames();

            assertThat(names).isEmpty();
        }

        @Test
        @DisplayName("Should return immutable set of names")
        void testGetToolNamesImmutable() {
            registry.register(mockTool1);
            Set<String> names = registry.getToolNames();

            assertThatThrownBy(() -> names.add("new-tools")).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Tools Except")
    class ToolsExcept {
        @Test
        @DisplayName("Should get tools except specified tools")
        void testGetToolsExceptSingleTool() {
            registry.register(mockTool1);
            registry.register(mockTool2);
            Tool mockTool3 = new MockTool("tool3", "Third tools");
            registry.register(mockTool3);

            List<Tool> tools = registry.findAllExcept("tool2");

            assertThat(tools).hasSize(2);
            assertThat(tools).extracting(tool -> tool.getDefinition().getName()).containsExactlyInAnyOrder("tool1",
                    "tool3");
        }

        @Test
        @DisplayName("Should return all tools when excluded tools doesn't exist")
        void testGetToolsExceptNonExistentTool() {
            registry.register(mockTool1);
            registry.register(mockTool2);

            List<Tool> tools = registry.findAllExcept("nonexistent");

            assertThat(tools).hasSize(2);
            assertThat(tools).extracting(tool -> tool.getDefinition().getName()).containsExactlyInAnyOrder("tool1",
                    "tool2");
        }

        @Test
        @DisplayName("Should return empty list when only tools is excluded")
        void testGetToolsExceptOnlyTool() {
            registry.register(mockTool1);

            List<Tool> tools = registry.findAllExcept("tool1");

            assertThat(tools).isEmpty();
        }

        @Test
        @DisplayName("Should return empty list when registry is empty")
        void testGetToolsExceptEmpty() {
            List<Tool> tools = registry.findAllExcept("tool1");

            assertThat(tools).isEmpty();
        }

        @Test
        @DisplayName("Should reject null excluded tools names array")
        void testGetToolsExceptRejectsNull() {
            assertThatThrownBy(() -> registry.findAllExcept((String[]) null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Excluded tools names cannot be null");
        }

        @Test
        @DisplayName("Should return immutable list")
        void testGetToolsExceptImmutable() {
            registry.register(mockTool1);
            registry.register(mockTool2);
            List<Tool> tools = registry.findAllExcept("tool1");

            assertThatThrownBy(() -> tools.add(mockTool2)).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Should get tools except multiple tools")
        void testGetToolsExceptMultipleTools() {
            registry.register(mockTool1);
            registry.register(mockTool2);
            registry.register(new MockTool("tool3", "Third tools"));
            registry.register(new MockTool("tool4", "Fourth tools"));

            List<Tool> tools = registry.findAllExcept("tool1", "tool3");

            assertThat(tools).hasSize(2);
            assertThat(tools).extracting(tool -> tool.getDefinition().getName()).containsExactlyInAnyOrder("tool2",
                    "tool4");
        }

        @Test
        @DisplayName("Should handle empty excluded varargs")
        void testGetToolsExceptEmptyVarargs() {
            registry.register(mockTool1);
            registry.register(mockTool2);

            List<Tool> tools = registry.findAllExcept();

            assertThat(tools).hasSize(2);
            assertThat(tools).extracting(tool -> tool.getDefinition().getName()).containsExactlyInAnyOrder("tool1",
                    "tool2");
        }

        @Test
        @DisplayName("Should return Tool instances not just definitions")
        void testGetToolsExceptReturnsTool() {
            registry.register(mockTool1);
            registry.register(mockTool2);

            List<Tool> tools = registry.findAllExcept("tool1");

            assertThat(tools).hasSize(1);
            assertThat(tools.get(0)).isSameAs(mockTool2);
        }
    }

    @Nested
    @DisplayName("Tool Existence")
    class ToolExistence {
        @Test
        @DisplayName("Should check if tools exists")
        void testHasToolNamed() {
            registry.register(mockTool1);

            assertThat(registry.hasToolNamed("tool1")).isTrue();
            assertThat(registry.hasToolNamed("nonexistent")).isFalse();
        }

        @Test
        @DisplayName("Should reject null tools name in hasToolNamed")
        void testHasToolNamedRejectsNull() {
            assertThatThrownBy(() -> registry.hasToolNamed(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Tool name cannot be null");
        }
    }

    @Nested
    @DisplayName("Size and Empty")
    class SizeAndEmpty {
        @Test
        @DisplayName("Should return correct size")
        void testSize() {
            assertThat(registry.size()).isEqualTo(0);

            registry.register(mockTool1);
            assertThat(registry.size()).isEqualTo(1);

            registry.register(mockTool2);
            assertThat(registry.size()).isEqualTo(2);

            registry.unregister("tool1");
            assertThat(registry.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should check if empty")
        void testIsEmpty() {
            assertThat(registry.isEmpty()).isTrue();

            registry.register(mockTool1);
            assertThat(registry.isEmpty()).isFalse();

            registry.clear();
            assertThat(registry.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("Clear")
    class Clear {
        @Test
        @DisplayName("Should clear all tools")
        void testClear() {
            registry.register(mockTool1);
            registry.register(mockTool2);

            registry.clear();

            assertThat(registry.isEmpty()).isTrue();
            assertThat(registry.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should handle clearing empty registry")
        void testClearEmpty() {
            assertThatNoException().isThrownBy(() -> registry.clear());
        }
    }

    @Nested
    @DisplayName("Functions Prefix Stripping")
    class FunctionsPrefixStripping {
        @Test
        @DisplayName("Should strip functions. prefix when finding by name (default enabled)")
        void testStripFunctionsPrefixEnabled() {
            registry.register(mockTool1);

            var retrieved = registry.findByName("functions.tool1");

            assertThat(retrieved).isPresent().hasValue(mockTool1);
        }

        @Test
        @DisplayName("Should strip functions. prefix when checking hasToolNamed (default enabled)")
        void testStripFunctionsPrefixHasToolNamed() {
            registry.register(mockTool1);

            assertThat(registry.hasToolNamed("functions.tool1")).isTrue();
            assertThat(registry.hasToolNamed("tool1")).isTrue();
        }

        @Test
        @DisplayName("Should not strip functions. prefix when disabled")
        void testStripFunctionsPrefixDisabled() {
            DefaultToolRegistry disabledRegistry = new DefaultToolRegistry(false);
            disabledRegistry.register(mockTool1);

            var retrieved = disabledRegistry.findByName("functions.tool1");

            assertThat(retrieved).isEmpty();
        }

        @Test
        @DisplayName("Should find tool with original name even when prefix stripping enabled")
        void testFindWithOriginalName() {
            registry.register(mockTool1);

            var retrieved = registry.findByName("tool1");

            assertThat(retrieved).isPresent().hasValue(mockTool1);
        }

        @Test
        @DisplayName("Should not affect non-prefixed names")
        void testNonPrefixedNames() {
            registry.register(mockTool1);

            var retrieved = registry.findByName("other.tool1");

            assertThat(retrieved).isEmpty();
        }
    }

    @Nested
    @DisplayName("Category Grouping")
    class CategoryGrouping {
        @Test
        @DisplayName("Should default tools without category to 'general'")
        void testDefaultCategoryGeneral() {
            registry.register(mockTool1);

            assertThat(registry.findCategories()).containsExactly("general");
            assertThat(registry.findAllByCategory("general")).containsExactly(mockTool1);
        }

        @Test
        @DisplayName("Should return categories in registration order")
        void testCategoriesInRegistrationOrder() {
            registry.register(new MockTool("a", "first", ToolCategories.FILESYSTEM));
            registry.register(new MockTool("b", "second", ToolCategories.EXECUTION));
            registry.register(new MockTool("c", "third", ToolCategories.SEARCH));
            registry.register(new MockTool("d", "fourth", ToolCategories.WORKFLOW));

            assertThat(registry.findCategories()).containsExactly(ToolCategories.FILESYSTEM, ToolCategories.EXECUTION,
                    ToolCategories.SEARCH, ToolCategories.WORKFLOW);
        }

        @Test
        @DisplayName("Should keep first-seen position when category repeats")
        void testCategoryFirstSeenPosition() {
            registry.register(new MockTool("a", "1", ToolCategories.FILESYSTEM));
            registry.register(new MockTool("b", "2", ToolCategories.EXECUTION));
            registry.register(new MockTool("c", "3", ToolCategories.FILESYSTEM));

            assertThat(registry.findCategories()).containsExactly(ToolCategories.FILESYSTEM, ToolCategories.EXECUTION);
        }

        @Test
        @DisplayName("Should filter tools by category in registration order")
        void testFindAllByCategory() {
            Tool a = new MockTool("a", "1", ToolCategories.FILESYSTEM);
            Tool b = new MockTool("b", "2", ToolCategories.EXECUTION);
            Tool c = new MockTool("c", "3", ToolCategories.FILESYSTEM);
            registry.register(a);
            registry.register(b);
            registry.register(c);

            assertThat(registry.findAllByCategory(ToolCategories.FILESYSTEM)).containsExactly(a, c);
            assertThat(registry.findAllByCategory(ToolCategories.EXECUTION)).containsExactly(b);
            assertThat(registry.findAllByCategory("nonexistent")).isEmpty();
        }

        @Test
        @DisplayName("Should reject null category in findAllByCategory")
        void testFindAllByCategoryRejectsNull() {
            assertThatThrownBy(() -> registry.findAllByCategory(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Category cannot be null");
        }

        @Test
        @DisplayName("Should group tools by category preserving order")
        void testFindAllGroupedByCategory() {
            Tool a = new MockTool("a", "1", ToolCategories.FILESYSTEM);
            Tool b = new MockTool("b", "2", ToolCategories.EXECUTION);
            Tool c = new MockTool("c", "3", ToolCategories.FILESYSTEM);
            registry.register(a);
            registry.register(b);
            registry.register(c);

            Map<String, List<Tool>> grouped = registry.findAllGroupedByCategory();

            assertThat(grouped.keySet()).containsExactly(ToolCategories.FILESYSTEM, ToolCategories.EXECUTION);
            assertThat(grouped.get(ToolCategories.FILESYSTEM)).containsExactly(a, c);
            assertThat(grouped.get(ToolCategories.EXECUTION)).containsExactly(b);
        }

        @Test
        @DisplayName("Should return immutable grouped map and lists")
        void testFindAllGroupedByCategoryImmutable() {
            registry.register(new MockTool("a", "1", ToolCategories.FILESYSTEM));

            Map<String, List<Tool>> grouped = registry.findAllGroupedByCategory();

            assertThatThrownBy(() -> grouped.put("x", List.of())).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> grouped.get(ToolCategories.FILESYSTEM).add(mockTool2))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Should return empty results when registry empty")
        void testEmptyRegistry() {
            assertThat(registry.findCategories()).isEmpty();
            assertThat(registry.findAllByCategory(ToolCategories.FILESYSTEM)).isEmpty();
            assertThat(registry.findAllGroupedByCategory()).isEmpty();
        }
    }

    /** Mock tools implementation for testing. */
    private static class MockTool extends AbstractTool {
        public MockTool(String name, String description) {
            super(name, description, Map.of("type", "object"));
        }

        public MockTool(String name, String description, String category) {
            super(name, description, category, Map.of("type", "object"));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("Executed " + getDefinition().getName() + " with input " + input);
        }
    }
}
