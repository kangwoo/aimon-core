package at.aimon.core.llms.openai;

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
 * ArchUnit tests for the aimon-llm-openai module.
 *
 * <p>
 * Enforces that the OpenAI module only depends on allowed packages:
 *
 * <ul>
 * <li>at.aimon.core.llm.. (core LLM abstractions, including streaming)
 * <li>at.aimon.core.agent.prompt.. (structured system prompt type used by streaming overload)
 * <li>at.aimon.core.knowledge.embedding.. (embedding abstractions used by OpenAIEmbeddingClient)
 * <li>at.aimon.core.base.. (core base types)
 * <li>com.openai.. (OpenAI Java SDK)
 * <li>com.fasterxml.jackson.. (JSON processing)
 * <li>com.knuddels.jtokkit.. (tiktoken Java port for token counting)
 * <li>java.. (Java standard library)
 * <li>org.slf4j.. (logging)
 * </ul>
 *
 * <p>
 * The explicit whitelist enforces the design constraint that OpenAI SDK types ({@code com.openai.*}) never leak out of
 * the provider module into the rest of the framework.
 */
@DisplayName("OpenAI Module Architecture Tests")
class OpenAIArchitectureTest {

    private static JavaClasses classes;

    private static final String PKG_OPENAI_MODULE = "at.aimon.core.llms.openai..";
    private static final String PKG_LLM_CORE = "at.aimon.core.llm..";
    private static final String PKG_AGENT_PROMPT = "at.aimon.core.agent.prompt..";
    private static final String PKG_KNOWLEDGE_EMBEDDING = "at.aimon.core.knowledge.embedding..";
    private static final String PKG_BASE = "at.aimon.core.base..";
    private static final String PKG_OPENAI_SDK = "com.openai..";
    private static final String PKG_JACKSON = "com.fasterxml.jackson..";
    private static final String PKG_JTOKKIT = "com.knuddels.jtokkit..";
    private static final String PKG_JAVA = "java..";
    private static final String PKG_SLF4J = "org.slf4j..";

    @BeforeAll
    static void setUp() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("at.aimon.core.llms.openai");
    }

    @Test
    @DisplayName("OpenAI module should only depend on allowed packages")
    void openaiModuleShouldOnlyDependOnAllowedPackages() {
        ArchRule rule = classes().that().resideInAPackage(PKG_OPENAI_MODULE).should().onlyDependOnClassesThat()
                .resideInAnyPackage(PKG_OPENAI_MODULE, PKG_LLM_CORE, PKG_AGENT_PROMPT, PKG_KNOWLEDGE_EMBEDDING,
                        PKG_BASE, PKG_OPENAI_SDK, PKG_JACKSON, PKG_JTOKKIT, PKG_JAVA, PKG_SLF4J);

        rule.check(classes);
    }

    @Test
    @DisplayName("OpenAI module should have no cyclic dependencies")
    void openaiModuleShouldHaveNoCycles() {
        ArchRule rule = slices().matching("at.aimon.core.llms.openai.(*)..").should().beFreeOfCycles();

        rule.check(classes);
    }
}
