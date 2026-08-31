package at.aimon.core.skill.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regression tests for B-26: a frontmatter key written without a value.
 *
 * <p>
 * YAML reads {@code license:} as a null mapping value. {@link SkillContentResult} used to defensively copy the
 * frontmatter with {@code Map.copyOf}, which rejects null values, so <em>any</em> such key aborted skill loading with a
 * bare {@code NullPointerException} — an exception {@link SkillContentParser#parse(String)} does not declare, from a
 * public API, naming neither the file nor the field.
 */
class SkillContentParserEmptyValueTest {

    private static String withFrontmatterLine(String line) {
        return "---\n" + "name: sample\n" + "description: A test skill\n" + line + "\n" + "---\n" + "\n" + "Body";
    }

    /**
     * Every key the skill parser reads, not merely the one the defect was first spotted through. The defect was in the
     * shared defensive copy, so it applied to all of them equally.
     */
    @ParameterizedTest(name = "\"{0}\" parses to a null value rather than throwing")
    @ValueSource(strings = {"license:", "description:", "name:", "compatibility:", "allowed-tools:", "arguments:",
            "hooks:", "invoke:", "execution:", "metadata:", "max-iterations:", "unrecognised-key:"})
    @DisplayName("a key written without a value is carried through as null, not rejected")
    void emptyValuedKeyIsCarriedThroughAsNull(String line) {
        final String key = line.substring(0, line.length() - 1);

        final SkillContentResult result = SkillContentParser.parse(withFrontmatterLine(line));

        assertThat(result.getFrontmatter()).containsKey(key);
        assertThat(result.getFrontmatter().get(key)).isNull();
    }

    @Test
    @DisplayName("the frontmatter view stays unmodifiable now that it is no longer a Map.copyOf")
    void frontmatterRemainsUnmodifiable() {
        final Map<String, Object> frontmatter = SkillContentParser.parse(withFrontmatterLine("license:"))
                .getFrontmatter();

        assertThat(frontmatter).isUnmodifiable();
    }

    @Test
    @DisplayName("equals/hashCode survive null values")
    void valueSemanticsSurviveNullValues() {
        final SkillContentResult first = SkillContentParser.parse(withFrontmatterLine("license:"));
        final SkillContentResult second = SkillContentParser.parse(withFrontmatterLine("license:"));

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThatCode(first::toString).doesNotThrowAnyException();
    }
}
