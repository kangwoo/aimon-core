package at.aimon.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import at.aimon.core.memory.MemoryCapability;
import at.aimon.core.memory.MemoryIngestor;
import at.aimon.core.memory.MemorySearcher;
import at.aimon.core.memory.MemorySnapshotReader;
import at.aimon.core.memory.ObservationRecorder;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.PeerMemory;
import at.aimon.core.memory.RepresentationStore;
import at.aimon.core.memory.WorkspaceStore;
import at.aimon.core.memory.dialectic.DialecticEngine;

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
 *
 * <p>
 * The rules from {@link #tierSpiDoesNotMentionAnyStore()} onwards guard the
 * service-tier SPI ({@link PeerMemory} and the five capability interfaces).
 * They exist because that SPI makes three claims which are otherwise only
 * prose: that the stores were demoted to the default backend's materials, that
 * a query widens by gaining a field rather than a parameter, and that a
 * backend cannot claim a capability it does not implement.
 */
@DisplayName("Memory Architecture Tests")
class MemoryArchitectureTest {

    /**
     * The five service tiers. {@link DialecticEngine} is one of them: it predates this SPI and was adopted as the CHAT
     * tier unchanged.
     */
    private static final Class<?>[] TIER_INTERFACES = {MemorySnapshotReader.class, MemorySearcher.class,
            DialecticEngine.class, ObservationRecorder.class, MemoryIngestor.class};

    /**
     * The four tiers this SPI introduced. {@link DialecticEngine} is deliberately absent — see
     * {@link #newTierMethodsTakeExactlyOneRequestObject()}.
     */
    private static final Class<?>[] NEW_TIER_INTERFACES = {MemorySnapshotReader.class, MemorySearcher.class,
            ObservationRecorder.class, MemoryIngestor.class};

    private static final Class<?>[] STORE_INTERFACES = {WorkspaceStore.class, ObservationStore.class,
            RepresentationStore.class};

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
     *
     * <p>
     * The five service tiers are in scope too. They are not stores, but they are the other place a workspace could
     * arrive as a bare string, and the isolation argument does not care which altitude the leak happens at.
     */
    @Test
    @DisplayName("No memory store or tier method may take String as its first parameter (except WorkspaceStore.findById)")
    void noMemoryStoreMethodTakesStringAsFirstParameter() {
        List<String> violations = new ArrayList<>();
        List<Class<?>> guarded = new ArrayList<>(List.of(STORE_INTERFACES));
        guarded.addAll(List.of(TIER_INTERFACES));
        for (Class<?> iface : guarded) {
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
            throw new AssertionError("Memory store and tier methods must not take String as first parameter "
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

    /**
     * The demotion of the storage SPI, stated as something a build can check.
     *
     * <p>
     * The three stores did not move package and did not change a signature; what changed is that they are no longer
     * the seam a memory backend is replaced at. The only way to say that in code is by reference direction: if no tier
     * signature — and no accessor on {@link PeerMemory} — can name a store, then a backend built on something other
     * than a store is expressible, which is the whole claim.
     */
    @Test
    @DisplayName("No service-tier or PeerMemory signature mentions ObservationStore/RepresentationStore/WorkspaceStore")
    void tierSpiDoesNotMentionAnyStore() {
        List<String> violations = new ArrayList<>();
        List<Class<?>> guarded = new ArrayList<>(List.of(TIER_INTERFACES));
        guarded.add(PeerMemory.class);
        for (Class<?> iface : guarded) {
            for (Method method : iface.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                Set<Class<?>> mentioned = new LinkedHashSet<>();
                collectTypes(method.getGenericReturnType(), mentioned);
                for (Type parameter : method.getGenericParameterTypes()) {
                    collectTypes(parameter, mentioned);
                }
                for (Class<?> store : STORE_INTERFACES) {
                    if (mentioned.contains(store)) {
                        violations.add(
                                iface.getSimpleName() + "#" + method.getName() + " mentions " + store.getSimpleName());
                    }
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new AssertionError("The service-tier SPI must not name a storage interface — the stores are the"
                    + " default backend's materials, not the extension point. A tier that names one cannot be"
                    + " implemented by a backend that has no store. Violations: " + violations);
        }
    }

    /**
     * Query shape, enforced rather than merely conventional.
     *
     * <p>
     * A tier method that takes its axes as loose parameters breaks every implementation the day a sixth axis is
     * needed; one that takes a request object gains a field instead. {@code MemoryContextRequest} already wrote that
     * reasoning down, and until this rule existed nothing checked it — neither of the two isolation rules above
     * reaches it, as they only look at {@code String} parameters.
     *
     * <p>
     * Two carve-outs, both deliberate:
     *
     * <ul>
     * <li><b>No-argument methods are out of scope</b>, because {@link MemorySearcher#ranksByScore()} and
     * {@link ObservationRecorder#storesConfidence()} are questions about the backend rather than queries against it,
     * and there will be more of them.
     * <li><b>{@link DialecticEngine} is out of scope entirely.</b> Its
     * {@code queryStream(DialecticQuery, LlmStreamSink)} takes two parameters, the second from another package —
     * a shape this rule would reject. That interface predates this SPI and is promised unchanged, so the rule yields
     * to it rather than the other way round. Whitelisting {@code LlmStreamSink} instead was considered and rejected:
     * the next streaming signature would want the same exemption, and a rule is worth what its exception list leaves.
     * </ul>
     */
    @Test
    @DisplayName("New service-tier methods that take a parameter take exactly one, and it is a memory request object")
    void newTierMethodsTakeExactlyOneRequestObject() {
        List<String> violations = new ArrayList<>();
        for (Class<?> iface : NEW_TIER_INTERFACES) {
            for (Method method : iface.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || method.getParameterCount() == 0) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1) {
                    violations.add(iface.getSimpleName() + "#" + method.getName() + signature(params) + " takes "
                            + params.length + " parameters, expected exactly 1");
                    continue;
                }
                String parameterPackage = params[0].getPackageName();
                if (!parameterPackage.equals("at.aimon.core.memory")
                        && !parameterPackage.startsWith("at.aimon.core.memory.")) {
                    violations.add(iface.getSimpleName() + "#" + method.getName() + signature(params)
                            + " takes a parameter from " + parameterPackage + ", expected at.aimon.core.memory..");
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new AssertionError("Service-tier methods must take their axes in a request value object from"
                    + " at.aimon.core.memory.. so that widening the input never breaks an implementation."
                    + " Violations: " + violations);
        }
    }

    /**
     * The invariant that makes the capability model worth having.
     *
     * <p>
     * If a backend could hand back its own capability set, that set would be a second source of truth and could
     * disagree with the accessors — and the assembly, which decides tool registration from the set alone, would
     * register a tool whose first call finds an empty {@code Optional}. Computing the set outside the interface makes
     * the disagreement unrepresentable, but only for as long as the interface has no way to express it. This test is
     * that "for as long as".
     */
    @Test
    @DisplayName("PeerMemory exposes no capability-set method — capabilities are computed, not declared")
    void peerMemoryDoesNotDeclareItsCapabilities() {
        List<String> violations = new ArrayList<>();
        for (Method method : PeerMemory.class.getDeclaredMethods()) {
            Set<Class<?>> mentioned = new LinkedHashSet<>();
            collectTypes(method.getGenericReturnType(), mentioned);
            if (mentioned.contains(MemoryCapability.class)) {
                violations.add("PeerMemory#" + method.getName() + " returns a type mentioning MemoryCapability");
            }
        }
        if (!violations.isEmpty()) {
            throw new AssertionError("PeerMemory must not be able to state its own capabilities; MemoryCapabilities.of"
                    + " derives them from the tier accessors so a claim cannot diverge from an implementation."
                    + " A default method would not hold this line — it can be overridden. Violations: " + violations);
        }
    }

    /** Collects every class mentioned by {@code type}, including generic arguments and array components. */
    private static void collectTypes(Type type, Set<Class<?>> into) {
        if (type instanceof Class<?> clazz) {
            into.add(clazz);
            if (clazz.isArray()) {
                collectTypes(clazz.getComponentType(), into);
            }
        } else if (type instanceof ParameterizedType parameterized) {
            collectTypes(parameterized.getRawType(), into);
            for (Type argument : parameterized.getActualTypeArguments()) {
                collectTypes(argument, into);
            }
        } else if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                collectTypes(bound, into);
            }
            for (Type bound : wildcard.getLowerBounds()) {
                collectTypes(bound, into);
            }
        }
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
