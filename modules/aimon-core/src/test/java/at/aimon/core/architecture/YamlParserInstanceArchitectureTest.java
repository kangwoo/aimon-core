package at.aimon.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * A {@code Yaml} instance may never outlive the single parse call that built it.
 *
 * <p>
 * snakeyaml documents {@link Yaml} as not thread-safe, and it means it: {@code loadFromReader} publishes each call's
 * freshly built {@code Composer} onto the <em>shared</em> {@code BaseConstructor} and reads it straight back out of
 * that same field, while {@code BaseConstructor} carries plain {@code HashMap}/{@code HashSet} collections that every
 * concurrent construction mutates and clears. Three parsers here each held one in a field, and
 * {@code SkillContentParser} held its in a {@code static} field shared by the whole JVM while claiming in its own
 * javadoc to be "Thread-safe and stateless".
 *
 * <p>
 * A field is the whole defect, which is why this rule is about fields rather than about locking. The behavioural proof
 * lives in {@code at.aimon.core.skill.parser.SkillContentParserConcurrencyTest}, but that test alone cannot pin the
 * fix: wrapping the shared instance in a global {@code synchronized} block would also turn it green, at the price of
 * serialising every skill parse in a framework built to run agents concurrently. It also only reaches one of the three
 * parsers. This rule covers all three and admits only the shape that has no shared state to guard.
 *
 * <h2>What this cannot see</h2>
 *
 * <p>
 * A {@code Yaml} stashed into a collection, a {@code ThreadLocal}, or a static map — the field's raw type would be
 * {@code Map}, not {@code Yaml}. Those are worse-behaved variants of the same idea and no rule here forbids them; what
 * this pins is that the obvious shape stays gone.
 */
@DisplayName("YAML parser instance architecture")
class YamlParserInstanceArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void setUp() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("at.aimon.core");
    }

    @Test
    @DisplayName("no class holds a Yaml in a field — it is built per parse call and discarded")
    void noYamlIsHeldInAField() {
        final ArchRule rule = noFields().should().haveRawType(Yaml.class)
                .as("org.yaml.snakeyaml.Yaml carries parse state and is not thread-safe, so it must be built inside "
                        + "the parse call that uses it and never stored. Build one per call "
                        + "(new Yaml(new SafeConstructor(new LoaderOptions()))) instead of holding it in a field.");
        rule.check(classes);
    }
}
