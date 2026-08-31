package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentContent;
import at.aimon.core.agent.AgentMetadata;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.parser.MarkdownSkillParser;

/**
 * End-to-end tests for {@link OrcaAgentRuntimeFactory#buildMaterializedSkillRegistry} verifying that bundled
 * (classpath) skills are materialized onto a {@link at.aimon.core.filesystem.VirtualFileSystem} and exposed with
 * resolved base directories and file maps.
 */
class OrcaAgentRuntimeFactoryMaterializeTest {

    private static final String CLASSPATH_SKILLS_BASE = "materializer/skills";
    private static final String BUNDLED_SKILLS_DIR = ".aimon/bundled-skills";
    private static final String USER_SKILLS_DIR = ".aimon/skills";

    @TempDir
    Path tempDir;

    private LocalFileSystem fileSystem;

    @BeforeEach
    void setUp() throws Exception {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        fileSystem = new LocalFileSystem(config);
        fileSystem.initialize();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fileSystem != null) {
            fileSystem.close();
        }
    }

    private AgentBundle minimalBundle() {
        DefaultAgent agent = DefaultAgent.builder().metadata(AgentMetadata.builder().name("test-agent").build())
                .content(AgentContent.builder().systemPrompt("You are a test agent.").build()).build();
        return AgentBundle.builder().agent(agent).build();
    }

    @Test
    void buildMaterializedSkillRegistry_demoSkillPresent_registryContainsDemoSkill() {
        AgentBundle bundle = minimalBundle();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        MarkdownSkillParser skillParser = new MarkdownSkillParser();

        SkillRegistry registry = OrcaAgentRuntimeFactory.buildMaterializedSkillRegistry(bundle, fileSystem,
                USER_SKILLS_DIR, BUNDLED_SKILLS_DIR, CLASSPATH_SKILLS_BASE, classLoader, skillParser);

        assertThat(registry.getSkill("demo")).isPresent();
    }

    @Test
    void buildMaterializedSkillRegistry_demoSkill_baseDirPointsToBundledSkillsDir() {
        AgentBundle bundle = minimalBundle();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        MarkdownSkillParser skillParser = new MarkdownSkillParser();

        SkillRegistry registry = OrcaAgentRuntimeFactory.buildMaterializedSkillRegistry(bundle, fileSystem,
                USER_SKILLS_DIR, BUNDLED_SKILLS_DIR, CLASSPATH_SKILLS_BASE, classLoader, skillParser);

        Skill demo = registry.getSkill("demo").orElseThrow();
        assertThat(demo.getBaseDir()).isPresent();
        assertThat(demo.getBaseDir().get()).isEqualTo(BUNDLED_SKILLS_DIR + "/demo");
    }

    @Test
    void buildMaterializedSkillRegistry_demoSkill_filesContainsTemplatesReportMd() {
        AgentBundle bundle = minimalBundle();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        MarkdownSkillParser skillParser = new MarkdownSkillParser();

        SkillRegistry registry = OrcaAgentRuntimeFactory.buildMaterializedSkillRegistry(bundle, fileSystem,
                USER_SKILLS_DIR, BUNDLED_SKILLS_DIR, CLASSPATH_SKILLS_BASE, classLoader, skillParser);

        Skill demo = registry.getSkill("demo").orElseThrow();
        assertThat(demo.getFiles()).containsKey("templates/report.md");
    }

    @Test
    void buildMaterializedSkillRegistry_demoSkill_templatesReportMdMaterializedOnVfs() {
        AgentBundle bundle = minimalBundle();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        MarkdownSkillParser skillParser = new MarkdownSkillParser();

        OrcaAgentRuntimeFactory.buildMaterializedSkillRegistry(bundle, fileSystem, USER_SKILLS_DIR, BUNDLED_SKILLS_DIR,
                CLASSPATH_SKILLS_BASE, classLoader, skillParser);

        String expectedPath = BUNDLED_SKILLS_DIR + "/demo/templates/report.md";
        assertThat(fileSystem.exists(expectedPath)).isTrue();
    }

    @Test
    void buildMaterializedSkillRegistry_demoSkill_skillsMdNotInFilesMap() {
        AgentBundle bundle = minimalBundle();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        MarkdownSkillParser skillParser = new MarkdownSkillParser();

        SkillRegistry registry = OrcaAgentRuntimeFactory.buildMaterializedSkillRegistry(bundle, fileSystem,
                USER_SKILLS_DIR, BUNDLED_SKILLS_DIR, CLASSPATH_SKILLS_BASE, classLoader, skillParser);

        Skill demo = registry.getSkill("demo").orElseThrow();
        assertThat(demo.getFiles()).doesNotContainKey("SKILL.md");
    }

    @Test
    void buildMaterializedSkillRegistry_noClasspathSkills_returnsRegistryWithNoSkills() {
        AgentBundle bundle = minimalBundle();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        MarkdownSkillParser skillParser = new MarkdownSkillParser();

        // Use a classpath base that has no index file — materializer should be a no-op
        SkillRegistry registry = OrcaAgentRuntimeFactory.buildMaterializedSkillRegistry(bundle, fileSystem,
                USER_SKILLS_DIR, BUNDLED_SKILLS_DIR, "nonexistent/skills/base", classLoader, skillParser);

        assertThat(registry.getAllSkills()).isEmpty();
    }

    @Test
    void buildMaterializedSkillRegistry_demoSkill_additionalFixtureFilesMaterialized() {
        AgentBundle bundle = minimalBundle();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        MarkdownSkillParser skillParser = new MarkdownSkillParser();

        OrcaAgentRuntimeFactory.buildMaterializedSkillRegistry(bundle, fileSystem, USER_SKILLS_DIR, BUNDLED_SKILLS_DIR,
                CLASSPATH_SKILLS_BASE, classLoader, skillParser);

        assertThat(fileSystem.exists(BUNDLED_SKILLS_DIR + "/demo/scripts/run.py")).isTrue();
        assertThat(fileSystem.exists(BUNDLED_SKILLS_DIR + "/demo/references/schema.md")).isTrue();
        assertThat(fileSystem.exists(BUNDLED_SKILLS_DIR + "/demo/SKILL.md")).isTrue();
    }
}
