package at.aimon.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.RepresentationStore;
import at.aimon.core.memory.WorkspaceStore;

/**
 * Architecture rules for the {@code at.aimon.core.memory} package.
 *
 * <p>
 * The memory layer is the foundation of multi-tenant isolation: every store
 * method must take a {@link at.aimon.core.memory.Workspace}, a workspace-bound
 * id object (e.g. {@link at.aimon.core.memory.ObservationId}), or a
 * {@link at.aimon.core.memory.PeerView}. The single bootstrap exception is
 * {@link WorkspaceStore#findById(String)}, which exists so callers can obtain
 * a {@code Workspace} object before touching anything else.
 *
 * <p>
 * In-memory implementations are development/test defaults; production
 * assemblies must depend on the store <em>interfaces</em> only.
 */
@DisplayName("Memory Architecture Tests")
class MemoryArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void setUp() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("at.aimon.core");
    }

    @Test
    @DisplayName("Only WorkspaceStore.findById(String) may take a bare String id (multi-tenant isolation)")
    void onlyWhitelistedMethodTakesRawStringId() {
        ArchRule rule = methods().that().areDeclaredInClassesThat().resideInAPackage("at.aimon.core.memory").and()
                .areDeclaredInClassesThat().areInterfaces().and().arePublic().and().haveRawParameterTypes(String.class)
                .should().beDeclaredIn(WorkspaceStore.class).andShould().haveName("findById")
                .as("Memory store interfaces must not accept a bare String id; "
                        + "use Workspace, ObservationId, or PeerView for multi-tenant isolation. "
                        + "Bootstrap exception: WorkspaceStore.findById(String).");
        rule.check(classes);
    }

    /**
     * Belt-and-braces: also reject {@code (String, ...)} signatures via reflection
     * since ArchUnit's {@code haveRawParameterTypes} only matches the exact list.
     * The whitelist remains identical: {@link WorkspaceStore#findById(String)}.
     */
    @Test
    @DisplayName("No memory store method may take String as its first parameter (except WorkspaceStore.findById)")
    void noMemoryStoreMethodTakesStringAsFirstParameter() {
        List<String> violations = new ArrayList<>();
        Class<?>[] storeInterfaces = {WorkspaceStore.class, ObservationStore.class, RepresentationStore.class};
        for (Class<?> iface : storeInterfaces) {
            for (Method method : iface.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 0 || params[0] != String.class) {
                    continue;
                }
                boolean isWhitelisted = iface == WorkspaceStore.class && "findById".equals(method.getName())
                        && params.length == 1;
                if (!isWhitelisted) {
                    violations.add(iface.getSimpleName() + "#" + method.getName() + signature(params));
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new AssertionError("Memory store methods must not take String as first parameter "
                    + "(use Workspace/ObservationId/PeerView). Violations: " + violations);
        }
    }

    @Test
    @DisplayName("InMemory*Store classes must not be referenced from outside the memory package")
    void inMemoryImplsAreInternalToMemoryPackage() {
        ArchRule rule = noClasses().that().resideOutsideOfPackage("at.aimon.core.memory..").should()
                .dependOnClassesThat().haveSimpleNameStartingWith("InMemory").andShould()
                .resideInAPackage("at.aimon.core.memory..")
                .as("InMemory*Store classes are dev/test defaults; production callers "
                        + "must depend on WorkspaceStore/ObservationStore/RepresentationStore interfaces.");
        rule.check(classes);
    }

    private static String signature(Class<?>[] params) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(params[i].getSimpleName());
        }
        return sb.append(")").toString();
    }
}
