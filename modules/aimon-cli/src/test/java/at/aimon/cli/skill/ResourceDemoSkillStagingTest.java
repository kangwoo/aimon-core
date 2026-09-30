package at.aimon.cli.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.impl.AdaptiveAgentBundleLoader;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntimeFactory;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.impl.LocalExecutionEnvironmentProvider;
import at.aimon.core.filesystem.exception.FileAccessDeniedException;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.parser.MarkdownSkillParser;
import at.aimon.core.skill.render.DefaultSkillContentRenderer;
import at.aimon.core.skill.repository.ClasspathSkillRepository;
import at.aimon.core.skill.repository.PathSkillRepository;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.skill.SkillTool;

/**
 * The CLI's bundled {@code resource-demo} skill keeps working now that {@code ${AIMON_SKILL_DIR}} is always a staged
 * copy (execution-environment design §4.4): whichever repository the skill comes from — the materialized copy the CLI
 * actually runs, the bundle's own file-based repository ({@code gradle run} / IDE), or the classpath (a packaged jar
 * whose materialization was skipped) — the model is handed one path under {@code .aimon-staged/} that its file tools
 * can read and its shell can run scripts from, and that the file tools cannot modify.
 */
@DisplayName("resource-demo is staged into the workspace from every skill repository shape")
class ResourceDemoSkillStagingTest {

    private static final String SKILLS_BASE = "agents/default/skills";
    private static final Pattern STAGED_DIR = Pattern
            .compile("(/[^\\s`'\"]*/\\.aimon-staged/resource-demo/[0-9a-f]{16})");

    @TempDir
    Path tmp;

    private LocalFileSystem control;
    private LocalExecutionEnvironmentProvider provider;
    private ExecutionEnvironment environment;
    private ClassLoader classLoader;

    @BeforeEach
    void setUp() {
        // The stage-3 CLI shape: the control store at {project}/.aimon, the workspace provider on {project}.
        control = new LocalFileSystem(new LocalFileSystemConfig(tmp.resolve(".aimon").toString()));
        control.initialize();
        provider = LocalExecutionEnvironmentProvider.builder().workspaceRoot(tmp).contentSearch(false).build();
        environment = provider
                .resolve(EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.of("agent:default")).build());
        classLoader = Thread.currentThread().getContextClassLoader();
    }

    @AfterEach
    void tearDown() {
        provider.close();
        control.close();
    }

    @Test
    @DisplayName("the materialized registry — what the CLI runs")
    void materializedRegistry() throws Exception {
        final AgentBundle bundle = new AdaptiveAgentBundleLoader().load("default");
        final SkillRegistry registry = OrcaAgentRuntimeFactory.buildMaterializedSkillRegistry(bundle, control, "skills",
                "bundled-skills", SKILLS_BASE, classLoader, new MarkdownSkillParser());

        final String staged = assertStagesAndRuns(registry);

        // The materialized copy sits in the control store, which the file tools cannot see.
        assertThat(Files.exists(tmp.resolve(".aimon/bundled-skills/resource-demo/SKILL.md"))).isTrue();
        assertThatThrownBy(() -> environment.fileSystem().read(".aimon/bundled-skills/resource-demo/SKILL.md"))
                .isInstanceOf(FileAccessDeniedException.class);
        assertThat(staged)
                .isEqualTo(stagedDir(new DefaultSkillRegistry(new ClasspathSkillRepository(SKILLS_BASE, classLoader),
                        new MarkdownSkillParser())));
    }

    @Test
    @DisplayName("the bundle's own file-based registry (gradle run / IDE)")
    void bundlePathRegistry() throws Exception {
        final URL resource = classLoader.getResource(SKILLS_BASE);
        assumeTrue(resource != null && "file".equals(resource.getProtocol()),
                "the test class path exposes the bundled skills as a directory");
        final SkillRegistry registry = new DefaultSkillRegistry(new PathSkillRepository(directoryOf(resource)),
                new MarkdownSkillParser());

        final String staged = assertStagesAndRuns(registry);

        // Same content, same key: the file-based source and the classpath source stage to one directory.
        assertThat(staged)
                .isEqualTo(stagedDir(new DefaultSkillRegistry(new ClasspathSkillRepository(SKILLS_BASE, classLoader),
                        new MarkdownSkillParser())));
    }

    @Test
    @DisplayName("the classpath registry alone (the packaged-jar fallback)")
    void classpathRegistry() throws Exception {
        assertStagesAndRuns(new DefaultSkillRegistry(new ClasspathSkillRepository(SKILLS_BASE, classLoader),
                new MarkdownSkillParser()));
    }

    private String assertStagesAndRuns(SkillRegistry registry) throws Exception {
        final String output = invoke(registry);
        // The frontmatter description is shown verbatim (it names the placeholder); the instructions are rendered.
        final String body = output.substring(output.indexOf("Instructions:"));

        assertThat(body).doesNotContain("${AIMON_SKILL_DIR}");
        final String staged = stagedDirIn(body);
        assertThat(Path.of(staged).startsWith(tmp.resolve(".aimon-staged/resource-demo"))).as(staged).isTrue();
        assertThat(body.split(Pattern.quote(staged), -1).length - 1).as("every reference uses the same staged path")
                .isGreaterThanOrEqualTo(3);

        assertThat(read(staged + "/templates/report-template.md")).isEqualTo(bundled("templates/report-template.md"));
        assertThat(read(staged + "/references/notes.md")).isEqualTo(bundled("references/notes.md"));

        // The staging area is read-only to the file tools.
        assertThatThrownBy(() -> environment.fileSystem().write(staged + "/x", "tampered"))
                .isInstanceOf(FileAccessDeniedException.class);

        assumeTrue(onPath("python3"), "python3 is on the PATH");
        final ShellCommandResult result = environment.shell()
                .execute(() -> "python3 " + staged + "/scripts/hello.py demo", ExecutionOptions.builder().build());
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("resource-demo OK: script executed for topic 'demo'");
        return staged;
    }

    private String invoke(SkillRegistry registry) {
        final SkillTool tool = new SkillTool(registry, new DefaultSkillContentRenderer());
        final ToolContext context = ToolContext.builder().put(ToolContextKeys.EXECUTION_ENVIRONMENT, environment)
                .build();
        final ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "resource-demo", "args", "demo")), context);
        assertThat(result.isSuccess()).as(result.getContent()).isTrue();
        return result.getContent();
    }

    private String stagedDir(SkillRegistry registry) {
        return stagedDirIn(invoke(registry));
    }

    private static String stagedDirIn(String body) {
        final Matcher matcher = STAGED_DIR.matcher(body);
        assertThat(matcher.find()).as("a staged resource-demo directory in:%n%s", body).isTrue();
        return matcher.group(1);
    }

    private String read(String path) throws IOException {
        try (InputStream in = environment.fileSystem().read(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String bundled(String relative) throws IOException {
        try (InputStream in = classLoader.getResourceAsStream(SKILLS_BASE + "/resource-demo/" + relative)) {
            assertThat(in).as(relative).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path directoryOf(URL resource) throws URISyntaxException {
        return Path.of(resource.toURI());
    }

    private static boolean onPath(String executable) {
        final String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String entry : path.split(File.pathSeparator)) {
            if (Files.isExecutable(Path.of(entry, executable))) {
                return true;
            }
        }
        return false;
    }
}
