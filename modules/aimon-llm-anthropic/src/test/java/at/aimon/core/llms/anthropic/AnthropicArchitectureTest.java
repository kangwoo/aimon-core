package at.aimon.core.llms.anthropic;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ArchUnit tests for the aimon-llm-anthropic module.
 *
 * <p>
 * Enforces that the Anthropic module only depends on allowed packages:
 *
 * <ul>
 * <li>at.aimon.core.llm.. (core LLM abstractions, including streaming)
 * <li>at.aimon.core.agent.prompt.. (structured system prompt type used by streaming overload)
 * <li>at.aimon.core.base.. (core base types)
 * <li>com.anthropic.. (Anthropic Java SDK)
 * <li>com.fasterxml.jackson.. (JSON processing)
 * <li>java.. (Java standard library)
 * <li>org.slf4j.. (logging)
 * </ul>
 *
 * <p>
 * The explicit whitelist enforces the design constraint that Anthropic SDK types ({@code com.anthropic.*}) never leak
 * out of the provider module into the rest of the framework.
 */
@DisplayName("Anthropic Module Architecture Tests")
class AnthropicArchitectureTest {

    private static JavaClasses classes;

    private static final String PKG_ANTHROPIC_MODULE = "at.aimon.core.llms.anthropic..";
    private static final String PKG_LLM_CORE = "at.aimon.core.llm..";
    private static final String PKG_AGENT_PROMPT = "at.aimon.core.agent.prompt..";
    private static final String PKG_BASE = "at.aimon.core.base..";
    private static final String PKG_ANTHROPIC_SDK = "com.anthropic..";
    private static final String PKG_JACKSON = "com.fasterxml.jackson..";
    private static final String PKG_JAVA = "java..";
    private static final String PKG_SLF4J = "org.slf4j..";

    @BeforeAll
    static void setUp() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("at.aimon.core.llms.anthropic");
    }

    @Test
    @DisplayName("Anthropic module should only depend on allowed packages")
    void anthropicModuleShouldOnlyDependOnAllowedPackages() {
        ArchRule rule = classes().that().resideInAPackage(PKG_ANTHROPIC_MODULE).should().onlyDependOnClassesThat()
                .resideInAnyPackage(PKG_ANTHROPIC_MODULE, PKG_LLM_CORE, PKG_AGENT_PROMPT, PKG_BASE, PKG_ANTHROPIC_SDK,
                        PKG_JACKSON, PKG_JAVA, PKG_SLF4J);

        rule.check(classes);
    }

    @Test
    @DisplayName("Anthropic module should have no cyclic dependencies")
    void anthropicModuleShouldHaveNoCycles() {
        ArchRule rule = slices().matching("at.aimon.core.llms.anthropic.(*)..").should().beFreeOfCycles();

        rule.check(classes);
    }
}
