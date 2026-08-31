package at.aimon.core.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import at.aimon.core.agent.tool.Tool;

/**
 * Every built-in tool must declare {@code additionalProperties: false} at the top level of its input schema.
 *
 * <p>
 * That key is what turns an undeclared parameter into a violation: without it, JSON Schema's default is to allow
 * whatever the caller sends, and the schema gate has nothing to complain about. Third-party (MCP) schemas keep the
 * permissive default deliberately — this test covers only the schemas we write, which is why it discovers tools by
 * the package convention {@code at.aimon.core.tools.<category>} rather than by scanning for {@code Tool} subtypes
 * everywhere ({@code at.aimon.core.mcp.McpTool} advertises a server's schema, not ours).
 *
 * <h2>Top level, and no recursive search</h2>
 *
 * <p>
 * The assertion looks at the outermost map and nowhere else. A search that recurses would report
 * {@code TodoWriteTool} as covered — it declares the key on the {@code todos} array's item schema but not on the
 * schema the model's call is actually checked against. A test that a bug can satisfy is worse than no test.
 *
 * <h2>No exclusion list</h2>
 *
 * <p>
 * Three tools were put forward as candidates for exemption ({@code ScheduleTaskTool}, {@code SkillTool},
 * {@code TaskTool}); on inspection none of the three reasons held. So there is no exclusion list here at all — a list
 * is where the next unexplained line gets added, and this one has nothing to protect.
 */
@DisplayName("Built-in Tool Schema Architecture Tests")
class BuiltInToolSchemaArchitectureTest {

    private static final String ADDITIONAL_PROPERTIES = "additionalProperties";

    private static List<Class<?>> builtInToolClasses;

    @BeforeAll
    static void setUp() {
        JavaClasses toolPackage = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("at.aimon.core.tools");

        builtInToolClasses = toolPackage.stream().filter(JavaClass::isTopLevelClass)
                .filter(javaClass -> !javaClass.isInterface() && !javaClass.isEnum())
                .filter(javaClass -> !javaClass.getModifiers().contains(JavaModifier.ABSTRACT))
                .filter(javaClass -> javaClass.isAssignableTo(Tool.class)).map(JavaClass::reflect)
                .sorted(Comparator.comparing(Class::getName)).toList();
    }

    @Test
    @DisplayName("The sweep found the built-in tools — an empty scan would pass every other assertion")
    void discoversTheBuiltInTools() {
        assertThat(builtInToolClasses).hasSizeGreaterThan(20);
    }

    @Test
    @DisplayName("Every built-in tool declares additionalProperties: false in its top-level schema")
    void everyBuiltInToolClosesItsSchema() {
        final List<String> missing = new ArrayList<>();
        final List<String> notFalse = new ArrayList<>();
        for (Class<?> toolClass : builtInToolClasses) {
            // Deliberately the top-level map only. See the class javadoc for why a recursive search would lie.
            final Map<String, Object> schema = instantiate(toolClass).getDefinition().getInputSchema();
            if (schema == null || !schema.containsKey(ADDITIONAL_PROPERTIES)) {
                missing.add(toolClass.getSimpleName());
            } else if (!Boolean.FALSE.equals(schema.get(ADDITIONAL_PROPERTIES))) {
                notFalse.add(toolClass.getSimpleName() + " -> " + schema.get(ADDITIONAL_PROPERTIES));
            }
        }

        assertThat(missing).as("built-in tools whose top-level schema does not declare '%s'", ADDITIONAL_PROPERTIES)
                .isEmpty();
        assertThat(notFalse).as("built-in tools that declare '%s' as something other than false", ADDITIONAL_PROPERTIES)
                .isEmpty();
    }

    /**
     * Creates a tool through its narrowest constructor, with a stub for every dependency.
     *
     * <p>
     * Only the schema is read afterwards, and a schema is built from constants — so a tool whose collaborators are
     * all mocks still reports exactly the schema it would report in production.
     */
    private static Tool instantiate(Class<?> toolClass) {
        final Constructor<?> constructor = Arrays.stream(toolClass.getDeclaredConstructors())
                .min(Comparator.comparingInt(Constructor::getParameterCount))
                .orElseThrow(() -> new AssertionError("No constructor on " + toolClass.getName()));
        constructor.setAccessible(true);
        final Object[] arguments = Arrays.stream(constructor.getParameterTypes())
                .map(BuiltInToolSchemaArchitectureTest::stubFor).toArray();
        try {
            return (Tool) constructor.newInstance(arguments);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not instantiate " + toolClass.getName()
                    + " — extend stubFor(...) rather than excluding the tool", e);
        }
    }

    private static Object stubFor(Class<?> type) {
        if (type == int.class || type == short.class || type == byte.class) {
            return 1;
        }
        if (type == long.class) {
            return 1L;
        }
        if (type == double.class || type == float.class) {
            return 1.0;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return 'x';
        }
        if (type == String.class) {
            return "stub";
        }
        if (type == Duration.class) {
            return Duration.ofMinutes(1);
        }
        // Empty collections rather than mocks: a mocked List answers toArray() with null, and a constructor that
        // defensively copies its argument (WorkflowTool does) then dies inside List.copyOf.
        if (type == List.class || type == Collection.class || type == Iterable.class) {
            return List.of();
        }
        if (type == Set.class) {
            return Set.of();
        }
        if (type == Map.class) {
            return Map.of();
        }
        if (type == Optional.class) {
            return Optional.empty();
        }
        return Mockito.mock(type);
    }
}
