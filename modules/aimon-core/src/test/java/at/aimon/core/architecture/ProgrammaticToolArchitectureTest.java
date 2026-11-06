package at.aimon.core.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolExecutionManager;

/**
 * The single-entrance rule for programmatic tool calling ({@code docs/design/tool/programmatic-tool-calling.md}
 * decision 3, §17): code in {@code at.aimon.core.tools.code..} reaches a tool only through a
 * {@code ToolCallDispatcher}, never around the gates, and the engine SPI ({@code at.aimon.core.programmatic}) stays
 * neutral so an engine module depends on it alone.
 *
 * <p>
 * The same rule for the GraalJS engine ({@code at.aimon.graaljs.code..}) lives in that module's own architecture test:
 * the core test classpath does not see it.
 */
@DisplayName("Programmatic tool calling — single entrance and neutral SPI")
class ProgrammaticToolArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void setUp() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("at.aimon.core");
    }

    @Test
    @DisplayName("tools.code.. never calls a tool or the execution manager directly")
    void codePackageReachesToolsOnlyThroughTheDispatcher() {
        final ArchRule rule = noClasses().that().resideInAPackage("at.aimon.core.tools.code..").should()
                .accessTargetWhere(describe("Tool#execute or ToolExecutionManager#execute*, called or referenced",
                        access -> (access instanceof JavaMethodCall || access instanceof JavaMethodReference)
                                && access.getTarget().getName().startsWith("execute")
                                && (access.getTarget().getOwner().isAssignableTo(Tool.class)
                                        || access.getTarget().getOwner().isAssignableTo(ToolExecutionManager.class))))
                .because("a program's tool call goes through the ToolCallDispatcher on the CODE path, so it meets the"
                        + " same gates as a direct call (programmatic-tool-calling design decision 3)");
        rule.check(classes);
    }

    @Test
    @DisplayName("tools.code.. does not depend on the invoker, the executor or the ungated dispatcher")
    void codePackageDoesNotDependOnTheInvocationPipeline() {
        final ArchRule rule = noClasses().that().resideInAPackage("at.aimon.core.tools.code..").should()
                .dependOnClassesThat()
                .resideInAnyPackage("at.aimon.core.toolinvocation..", "at.aimon.core.agent.tool.execution..")
                .because("the pipeline behind the dispatcher — SingleToolInvoker, the executor, "
                        + "ToolCallDispatchers.ungated — is reached only through the ToolCallDispatcher interface");
        rule.check(classes);
    }

    @Test
    @DisplayName("the engine SPI depends on no tool package and no implementation")
    void engineSpiIsNeutral() {
        final ArchRule rule = noClasses().that().resideInAPackage("at.aimon.core.programmatic..").should()
                .dependOnClassesThat()
                .resideInAnyPackage("at.aimon.core.tools..", "at.aimon.core..impl..", "at.aimon.core.toolinvocation..")
                .because("an engine module depends on at.aimon.core.programmatic alone (programmatic-tool-calling "
                        + "design §4)");
        rule.check(classes);
    }
}
