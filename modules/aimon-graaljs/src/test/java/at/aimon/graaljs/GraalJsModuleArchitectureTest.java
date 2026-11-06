package at.aimon.graaljs;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Module-local architecture fences:
 *
 * <ul>
 * <li>the frontend depends only on neutral core SPI, never on {@code at.aimon.core..impl..} (the impl boundary the
 * design mandates for external modules);
 * <li>the {@code ExecuteCode} engine ({@code at.aimon.graaljs.code..}, and the event loop it runs in
 * {@code internal}) calls tools only through the {@code ToolCallBridge} — never a tool, the execution manager or the
 * invocation pipeline directly ({@code docs/design/tool/programmatic-tool-calling.md} decision 3, §17);
 * <li>the engine sees only the neutral engine SPI, never {@code at.aimon.core.tools..};
 * <li>{@code at.aimon.graaljs.internal} is used only inside {@code at.aimon.graaljs..}.
 * </ul>
 */
@DisplayName("aimon-graaljs — module boundary fences")
class GraalJsModuleArchitectureTest {

    private static final String MODULE_PACKAGE = "at.aimon.graaljs";

    private final JavaClasses production = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests()).importPackages(MODULE_PACKAGE);

    @Test
    @DisplayName("does not depend on core impl packages")
    void doesNotDependOnCoreImpl() {
        final ArchRule rule = noClasses().should().dependOnClassesThat().resideInAPackage("at.aimon.core..impl..")
                .because("external modules must depend on neutral core SPI, not concrete implementations");
        rule.check(production);
    }

    @Test
    @DisplayName("does not depend on core workflow.impl (the frozen 5-primitive SPI only)")
    void dependsOnlyOnNeutralWorkflowSpi() {
        final ArchRule rule = noClasses().should().dependOnClassesThat()
                .resideInAPackage("at.aimon.core.workflow.impl..")
                .because("the frontend bridges the neutral WorkflowScript/WorkflowContext SPI, never impl");
        rule.check(production);
    }

    @Test
    @DisplayName("the ExecuteCode engine reaches tools only through the bridge")
    void codeEngineReachesToolsOnlyThroughTheBridge() {
        final ArchRule rule = noClasses().that()
                .resideInAnyPackage(MODULE_PACKAGE + ".code..", MODULE_PACKAGE + ".internal..").should()
                .dependOnClassesThat()
                .resideInAnyPackage("at.aimon.core.toolinvocation..", "at.aimon.core.agent.tool.execution..",
                        "at.aimon.core.tools..")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("at.aimon.core.agent.tool.ToolExecutionManager").orShould()
                .dependOnClassesThat().haveFullyQualifiedName("at.aimon.core.agent.tool.ToolCallDispatcher").orShould()
                .dependOnClassesThat().haveFullyQualifiedName("at.aimon.core.agent.tool.Tool")
                .because("a program's tool call goes through the ToolCallBridge, which dispatches on the CODE path "
                        + "(programmatic-tool-calling design decision 3)");
        rule.check(production);
    }

    @Test
    @DisplayName("internal is used only inside at.aimon.graaljs..")
    void internalStaysInside() {
        final ArchRule rule = noClasses().that().resideOutsideOfPackage(MODULE_PACKAGE + "..").should()
                .dependOnClassesThat().resideInAPackage(MODULE_PACKAGE + ".internal..");
        rule.check(new ClassFileImporter().importPackages("at.aimon"));
    }
}
