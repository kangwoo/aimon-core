package at.aimon.workflow.graaljs;

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
 * <li>every {@code org.graalvm} import stays inside this one package — the {@code implementation}-scoped dependency
 * already prevents transitive leakage to consumers, and there is no other package to leak into.
 * </ul>
 */
@DisplayName("aimon-workflow-graaljs — module boundary fences")
class GraalJsModuleArchitectureTest {

    private static final String MODULE_PACKAGE = "at.aimon.workflow.graaljs";

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
}
