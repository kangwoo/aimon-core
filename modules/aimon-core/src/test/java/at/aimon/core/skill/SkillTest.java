package at.aimon.core.skill;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Unit tests for the new baseDir and files API on {@link Skill}. */
class SkillTest {

    private static final String SKILL_NAME = "test-skill";

    private static Skill.Builder baseBuilder() {
        SkillMetadata metadata = SkillMetadata.builder().name(SKILL_NAME).description("A test skill").build();
        SkillContent content = SkillContent.of("# Test Skill\n\nInstructions.");
        return Skill.builder().name(SKILL_NAME).metadata(metadata).content(content);
    }

    // -------------------------------------------------------------------------
    // baseDir
    // -------------------------------------------------------------------------

    @Test
    void testGetBaseDir_WhenNotSet_ReturnsEmpty() {
        Skill skill = baseBuilder().build();

        assertThat(skill.getBaseDir()).isEmpty();
    }

    @Test
    void testGetBaseDir_WhenSet_ReturnsPresentOptional() {
        Skill skill = baseBuilder().baseDir("/skills/test-skill").build();

        assertThat(skill.getBaseDir()).isPresent().hasValue("/skills/test-skill");
    }

    @Test
    void testGetBaseDir_WhenSetToNull_ReturnsEmpty() {
        Skill skill = baseBuilder().baseDir(null).build();

        assertThat(skill.getBaseDir()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // files — default state
    // -------------------------------------------------------------------------

    @Test
    void testGetFiles_WhenNotSet_ReturnsEmptyMap() {
        Skill skill = baseBuilder().build();

        assertThat(skill.getFiles()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // files — builder.files(map)
    // -------------------------------------------------------------------------

    @Test
    void testGetFiles_WhenSetViaFilesMap_ReturnsAllEntries() {
        Map<String, String> inputFiles = Map.of("templates/report.md", "/skills/test-skill/templates/report.md",
                "scripts/run.py", "/skills/test-skill/scripts/run.py");

        Skill skill = baseBuilder().files(inputFiles).build();

        assertThat(skill.getFiles()).containsExactlyInAnyOrderEntriesOf(inputFiles);
    }

    @Test
    void testGetFiles_WhenSetViaEmptyMap_ReturnsEmptyMap() {
        Skill skill = baseBuilder().files(Map.of()).build();

        assertThat(skill.getFiles()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // files — builder.putFile(rel, path)
    // -------------------------------------------------------------------------

    @Test
    void testGetFiles_WhenSetViaPutFile_ContainsEntry() {
        Skill skill = baseBuilder().putFile("templates/report.md", "/skills/test-skill/templates/report.md").build();

        assertThat(skill.getFiles()).containsEntry("templates/report.md", "/skills/test-skill/templates/report.md");
    }

    @Test
    void testGetFiles_WhenMultiplePutFileCalls_ContainsAllEntries() {
        Skill skill = baseBuilder().putFile("templates/report.md", "/skills/test-skill/templates/report.md")
                .putFile("scripts/run.py", "/skills/test-skill/scripts/run.py")
                .putFile("references/schema.md", "/skills/test-skill/references/schema.md").build();

        assertThat(skill.getFiles()).hasSize(3).containsKey("templates/report.md").containsKey("scripts/run.py")
                .containsKey("references/schema.md");
    }

    // -------------------------------------------------------------------------
    // getFiles() is unmodifiable
    // -------------------------------------------------------------------------

    @Test
    void testGetFiles_ReturnedMap_IsUnmodifiable() {
        Skill skill = baseBuilder().putFile("templates/report.md", "/skills/test-skill/templates/report.md").build();

        Map<String, String> files = skill.getFiles();

        assertThatThrownBy(() -> files.put("new-key", "new-value")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testGetFiles_EmptyReturnedMap_IsUnmodifiable() {
        Skill skill = baseBuilder().build();

        Map<String, String> files = skill.getFiles();

        assertThatThrownBy(() -> files.put("new-key", "new-value")).isInstanceOf(UnsupportedOperationException.class);
    }

    // -------------------------------------------------------------------------
    // equals / hashCode — baseDir
    // -------------------------------------------------------------------------

    @Test
    void testEquals_IdenticalSkillsWithBaseDir_AreEqual() {
        Skill skill1 = baseBuilder().baseDir("/skills/test-skill").build();
        Skill skill2 = baseBuilder().baseDir("/skills/test-skill").build();

        assertThat(skill1).isEqualTo(skill2);
    }

    @Test
    void testHashCode_IdenticalSkillsWithBaseDir_HaveSameHashCode() {
        Skill skill1 = baseBuilder().baseDir("/skills/test-skill").build();
        Skill skill2 = baseBuilder().baseDir("/skills/test-skill").build();

        assertThat(skill1.hashCode()).isEqualTo(skill2.hashCode());
    }

    @Test
    void testEquals_SkillsDifferingOnlyInBaseDir_AreNotEqual() {
        Skill withBaseDir = baseBuilder().baseDir("/skills/test-skill").build();
        Skill withoutBaseDir = baseBuilder().build();

        assertThat(withBaseDir).isNotEqualTo(withoutBaseDir);
    }

    @Test
    void testEquals_SkillsWithDifferentBaseDirs_AreNotEqual() {
        Skill skill1 = baseBuilder().baseDir("/skills/test-skill").build();
        Skill skill2 = baseBuilder().baseDir("/other/path").build();

        assertThat(skill1).isNotEqualTo(skill2);
    }

    // -------------------------------------------------------------------------
    // equals / hashCode — files
    // -------------------------------------------------------------------------

    @Test
    void testEquals_IdenticalSkillsWithFiles_AreEqual() {
        Skill skill1 = baseBuilder().putFile("templates/report.md", "/skills/test-skill/templates/report.md").build();
        Skill skill2 = baseBuilder().putFile("templates/report.md", "/skills/test-skill/templates/report.md").build();

        assertThat(skill1).isEqualTo(skill2);
    }

    @Test
    void testHashCode_IdenticalSkillsWithFiles_HaveSameHashCode() {
        Skill skill1 = baseBuilder().putFile("templates/report.md", "/skills/test-skill/templates/report.md").build();
        Skill skill2 = baseBuilder().putFile("templates/report.md", "/skills/test-skill/templates/report.md").build();

        assertThat(skill1.hashCode()).isEqualTo(skill2.hashCode());
    }

    @Test
    void testEquals_SkillsDifferingOnlyInFiles_AreNotEqual() {
        Skill withFile = baseBuilder().putFile("templates/report.md", "/skills/test-skill/templates/report.md").build();
        Skill withoutFile = baseBuilder().build();

        assertThat(withFile).isNotEqualTo(withoutFile);
    }

    @Test
    void testEquals_SkillsWithDifferentFileMaps_AreNotEqual() {
        Skill skill1 = baseBuilder().putFile("templates/report.md", "/skills/test-skill/templates/report.md").build();
        Skill skill2 = baseBuilder().putFile("scripts/run.py", "/skills/test-skill/scripts/run.py").build();

        assertThat(skill1).isNotEqualTo(skill2);
    }

    // -------------------------------------------------------------------------
    // toString — baseDir and files keys appear
    // -------------------------------------------------------------------------

    @Test
    void testToString_ContainsBaseDir() {
        Skill skill = baseBuilder().baseDir("/skills/test-skill").build();

        assertThat(skill.toString()).contains("/skills/test-skill");
    }

    @Test
    void testToString_WhenBaseDirNotSet_ContainsNullRepresentation() {
        Skill skill = baseBuilder().build();

        // toString renders the literal baseDir='null' when not set (matches the production format baseDir='<value>')
        assertThat(skill.toString()).contains("baseDir='null'");
    }

    @Test
    void testToString_ContainsFilesKeys() {
        Skill skill = baseBuilder().putFile("templates/report.md", "/skills/test-skill/templates/report.md")
                .putFile("scripts/run.py", "/skills/test-skill/scripts/run.py").build();

        String str = skill.toString();

        assertThat(str).contains("files=");
        assertThat(str).contains("templates/report.md");
        assertThat(str).contains("scripts/run.py");
    }

    @Test
    void testToString_WhenFilesEmpty_ContainsEmptySetRepresentation() {
        Skill skill = baseBuilder().build();

        // toString should still include the files= token even when empty
        assertThat(skill.toString()).contains("files=");
    }

    // -------------------------------------------------------------------------
    // builder validation — name must match metadata name
    // -------------------------------------------------------------------------

    @Test
    void testBuild_WhenNameDoesNotMatchMetadataName_ThrowsIllegalArgumentException() {
        SkillMetadata metadata = SkillMetadata.builder().name("other-skill").description("A skill").build();
        SkillContent content = SkillContent.of("Instructions.");

        assertThatThrownBy(() -> Skill.builder().name(SKILL_NAME).metadata(metadata).content(content).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(SKILL_NAME)
                .hasMessageContaining("other-skill");
    }

    // -------------------------------------------------------------------------
    // putFile null-safety
    // -------------------------------------------------------------------------

    @Test
    void testPutFile_NullRelativePath_ThrowsNullPointerException() {
        assertThatThrownBy(() -> baseBuilder().putFile(null, "/skills/test-skill/templates/report.md"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testPutFile_NullPath_ThrowsNullPointerException() {
        assertThatThrownBy(() -> baseBuilder().putFile("templates/report.md", null))
                .isInstanceOf(NullPointerException.class);
    }

    // -------------------------------------------------------------------------
    // files(map) null-safety
    // -------------------------------------------------------------------------

    @Test
    void testFiles_NullMap_ThrowsNullPointerException() {
        assertThatThrownBy(() -> baseBuilder().files(null)).isInstanceOf(NullPointerException.class);
    }
}
