package at.aimon.core.base.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

@DisplayName("YamlDuplicateKeys Tests")
class YamlDuplicateKeysTest {

    @Test
    @DisplayName("Should name a top-level key written twice")
    void namesATopLevelDuplicate() {
        assertThat(YamlDuplicateKeys.find("name: a\nversion: 1.0.0\nname: b\n")).containsExactly("name");
    }

    @Test
    @DisplayName("Should name a nested duplicate by its dotted path")
    void namesANestedDuplicate() {
        assertThat(YamlDuplicateKeys.find("""
                model:
                  temperature: 0.2
                  name: m
                  temperature: 0.9
                """)).containsExactly("model.temperature");
    }

    @Test
    @DisplayName("Should mark a sequence in the path")
    void marksASequence() {
        assertThat(YamlDuplicateKeys.find("""
                steps:
                  - name: a
                    name: b
                  - name: c
                """)).containsExactly("steps[].name");
    }

    @Test
    @DisplayName("Should list a key written three times once, and several keys in document order")
    void listsEachKeyOnce() {
        assertThat(YamlDuplicateKeys.find("b: 1\na: 1\nb: 2\na: 2\nb: 3\n")).containsExactly("b", "a");
    }

    @Test
    @DisplayName("Should treat a quoted and an unquoted spelling of one string as the same key")
    void quotingDoesNotMakeAKeyDifferent() {
        assertThat(YamlDuplicateKeys.find("name: a\n\"name\": b\n")).containsExactly("name");
    }

    @Test
    @DisplayName("Should not confuse the number 1 with the string 1, which snakeyaml keeps apart too")
    void aNumberAndAStringAreDifferentKeys() {
        final String yaml = "1: a\n\"1\": b\n";

        assertThat(YamlDuplicateKeys.find(yaml)).isEmpty();
        final Map<Object, Object> loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        assertThat(loaded).hasSize(2);
    }

    @Test
    @DisplayName("Should not report the same name in two different mappings")
    void theSameNameInTwoMappingsIsNotADuplicate() {
        assertThat(YamlDuplicateKeys.find("first:\n  name: a\nsecond:\n  name: b\nname: c\n")).isEmpty();
    }

    @Test
    @DisplayName("Should not report an explicit key that overrides a merged one")
    void aMergeOverrideIsNotADuplicate() {
        assertThat(YamlDuplicateKeys.find("""
                base: &base
                  name: a
                derived:
                  <<: *base
                  name: b
                """)).isEmpty();
    }

    @Test
    @DisplayName("Should answer with nothing for text it cannot read, and never throw")
    void unreadableTextIsEmpty() {
        assertThat(YamlDuplicateKeys.find(null)).isEmpty();
        assertThat(YamlDuplicateKeys.find("")).isEmpty();
        assertThat(YamlDuplicateKeys.find("   \n")).isEmpty();
        assertThat(YamlDuplicateKeys.find("# only a comment\n")).isEmpty();
        assertThat(YamlDuplicateKeys.find("key: [unclosed\n")).isEmpty();
        assertThat(YamlDuplicateKeys.find("a: 1\n---\na: 2\n")).isEmpty();
        assertThat(YamlDuplicateKeys.find("just a scalar")).isEmpty();
    }

    @Test
    @DisplayName("Should survive a recursive alias")
    void aRecursiveAliasTerminates() {
        assertThat(YamlDuplicateKeys.find("root: &r\n  self: *r\n  name: a\n  name: b\n")).containsExactly("root.name");
    }
}
