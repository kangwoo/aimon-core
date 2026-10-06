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

    private static Map<Object, Object> load(String yaml) {
        return new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
    }

    @Test
    @DisplayName("Should report two spellings snakeyaml constructs one key from: booleans, nulls, numbers, dates")
    void spellingsOfOneValueAreOneKey() {
        // Each pair is one entry after snakeyaml's load, the first value gone. The path is the later spelling.
        final String[][] pairs = {{"yes", "true"}, {"true", "True"}, {"on", "TRUE"}, {"no", "false"}, {"~", "null"},
                {"null", "Null"}, {"1", "0x1"}, {"10", "1_0"}, {"1.0", "1.00"}, {"2001-01-01", "2001-01-01T00:00:00Z"}};
        for (String[] pair : pairs) {
            final String yaml = pair[0] + ": first\n" + pair[1] + ": second\n";

            assertThat(load(yaml)).as("what snakeyaml keeps of %s and %s", pair[0], pair[1]).hasSize(1)
                    .containsValue("second");
            assertThat(YamlDuplicateKeys.find(yaml)).as("%s and %s", pair[0], pair[1]).containsExactly(pair[1]);
        }
    }

    @Test
    @DisplayName("Should not report two keys snakeyaml keeps apart, however alike they are written")
    void keysSnakeyamlKeepsApartAreNotDuplicates() {
        final String[][] pairs = {{"true", "\"true\""}, {"yes", "no"}, {"null", "\"null\""}, {"1", "1.0"}, {"1", "2"},
                {"~", "\"\""}, {"!!binary aGk=", "!!binary aGk="}};
        for (String[] pair : pairs) {
            final String yaml = pair[0] + ": first\n" + pair[1] + ": second\n";

            assertThat(load(yaml)).as("what snakeyaml keeps of %s and %s", pair[0], pair[1]).hasSize(2);
            assertThat(YamlDuplicateKeys.find(yaml)).as("%s and %s", pair[0], pair[1]).isEmpty();
        }
    }

    @Test
    @DisplayName("Should still compare by tag and text a key whose value it cannot construct, and go on")
    void aKeyThatCannotBeConstructedIsComparedAsWritten() {
        // `!custom` has no constructor in a SafeConstructor: the caller's own load is what reports that.
        assertThat(YamlDuplicateKeys.find("!custom a: 1\n!custom a: 2\nname: x\nname: y\n")).containsExactly("a",
                "name");
        assertThat(YamlDuplicateKeys.find("!custom a: 1\n!custom b: 2\n")).isEmpty();
    }

    @Test
    @DisplayName("Should leave what the document parses to exactly as it was")
    void findingDoesNotChangeTheParse() {
        final String yaml = "yes: 1\ntrue: 2\n~: 3\nnull: 4\nmodel:\n  1: a\n  0x1: b\n";
        final Map<Object, Object> before = load(yaml);

        assertThat(YamlDuplicateKeys.find(yaml)).containsExactly("true", "null", "model.0x1");

        assertThat(load(yaml)).isEqualTo(before);
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
