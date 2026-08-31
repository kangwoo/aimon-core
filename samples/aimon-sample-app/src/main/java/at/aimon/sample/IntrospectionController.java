package at.aimon.sample;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import at.aimon.bootstrap.AimonStack;
import at.aimon.bootstrap.spec.AgentDescriptor;
import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.skill.Skill;
import at.aimon.core.subagent.Subagent;
import at.aimon.spring.boot.AimonSessions;

/**
 * Reports what the running agent actually has, and runs one turn on demand.
 *
 * <p>
 * Everything here is observation. A packaging test runs in a different JVM from the application it is testing —
 * that separation is the point, since a fat jar's class path only exists inside the process that was launched
 * from it — so the test cannot reach into a registry and look. It has to ask, and this is what answers.
 *
 * <p>
 * The two endpoints are deliberately not redundant. {@code /aimon/introspect} reports what the framework
 * <em>assembled</em>: which skills the registry knows, which subagents, and which files really landed on the
 * workspace filesystem. {@code /aimon/turn} reports what a model would actually be <em>told</em>, by running a
 * real turn and handing back the tool definitions the scripted client recorded. A skill can be present in the
 * first and missing from the second — that is precisely the failure a registry-only check would wave through.
 */
@RestController
public class IntrospectionController {

    private static final String AGENT_MD_RESOURCE = "agents/sample/agent.md";
    private static final String SKILL_INDEX_RESOURCE = "agents/sample/skills/index";
    private static final String BUNDLED_SKILLS_DIR = ".aimon/bundled-skills";

    private final AimonStack stack;
    private final AimonSessions sessions;
    private final RecordingLlmClient llmClient;

    /**
     * Creates the controller over the assembled stack.
     *
     * @param stack
     *            the assembled AIMON stack (must not be null)
     * @param sessions
     *            the session facade used to run a turn (must not be null)
     * @param llmClient
     *            the model this profile answers with, read for the tool definitions it was shown (must not be
     *            null)
     */
    public IntrospectionController(AimonStack stack, AimonSessions sessions, RecordingLlmClient llmClient) {
        this.stack = Objects.requireNonNull(stack, "Stack cannot be null");
        this.sessions = Objects.requireNonNull(sessions, "Sessions cannot be null");
        this.llmClient = Objects.requireNonNull(llmClient, "LLM client cannot be null");
    }

    /**
     * Reports the class path shape and, for every configured agent, what its registries and workspace hold.
     *
     * @return a JSON-serialisable view of the running assembly
     */
    @GetMapping("/aimon/introspect")
    public Map<String, Object> introspect() {
        final Map<String, Object> result = new LinkedHashMap<>();
        // The protocol of the agent definition is what the bundle loader branches on, so it is reported rather
        // than inferred: a test that guessed "packaged means jar:" would keep passing if the guess went stale.
        result.put("agentDefinitionProtocol", protocolOf(AGENT_MD_RESOURCE));
        result.put("skillIndexResources", resourceUrls(SKILL_INDEX_RESOURCE));

        final Map<String, Object> agents = new LinkedHashMap<>();
        for (AgentDescriptor descriptor : stack.agentDescriptors()) {
            stack.runtime(descriptor.getRuntimeId())
                    .ifPresent(runtime -> agents.put(descriptor.getAgentRef(), describe(descriptor, runtime)));
        }
        result.put("agents", agents);
        return result;
    }

    /**
     * Runs one real turn and reports both the answer and what the model was shown.
     *
     * @param session
     *            the session id to run under (defaults to {@code packaging})
     * @param input
     *            the user input for the turn (defaults to a fixed prompt)
     * @return the answer, plus the tool definitions the scripted model recorded for that call
     */
    @PostMapping("/aimon/turn")
    public Map<String, Object> turn(@RequestParam(defaultValue = "packaging") String session,
            @RequestParam(defaultValue = "which skills do you have") String input) {
        final AgentExecutionResult result = sessions.submit(SessionId.of(session), input);

        final Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", result.isSuccess());
        response.put("answer", result.getFinalAnswer());
        response.put("error", result.getErrorMessage());
        // The definitions are read after the turn on purpose: they are what the executor built from the live
        // registry at call time, which is the last point where a missing skill can still be caught.
        response.put("toolDefinitions", llmClient.lastToolDefinitions());
        return response;
    }

    private Map<String, Object> describe(AgentDescriptor descriptor, OrcaAgentRuntime runtime) {
        final Map<String, Object> view = new LinkedHashMap<>();
        view.put("runtimeId", descriptor.getRuntimeId().value());
        view.put("bundleName", descriptor.getBundleName());
        view.put("skills", runtime.getSkillRegistry().getAllSkills().stream().map(Skill::getName).sorted().toList());
        view.put("subagents",
                runtime.getSubagentRegistry().getAllSubagents().stream().map(Subagent::getName).sorted().toList());
        view.put("workspaceRoot", runtime.getFileSystem().getWorkingDirectory());
        view.put("materializedFiles", materializedFiles(runtime));
        return view;
    }

    /**
     * Lists what really landed under the workspace's bundled-skills directory.
     *
     * <p>
     * This is the assertion a registry lookup cannot make. A skill body can be served straight off the class
     * path, so a registry will happily advertise a skill whose {@code reference/} directory was never copied
     * anywhere — and the gap shows up only when the agent tries to read one of those files. Listing the
     * filesystem is how a test sees the difference between "advertised" and "there".
     */
    private List<String> materializedFiles(OrcaAgentRuntime runtime) {
        try {
            if (!runtime.getFileSystem().exists(BUNDLED_SKILLS_DIR)) {
                return List.of();
            }
            final List<String> files = new ArrayList<>(runtime.getFileSystem().listRecursive(BUNDLED_SKILLS_DIR));
            files.sort(Comparator.naturalOrder());
            return files;
        } catch (RuntimeException e) {
            // Diagnostics must not be able to fail the thing they diagnose; report the failure as data.
            return List.of("<unreadable: " + e.getMessage() + ">");
        }
    }

    private String protocolOf(String resource) {
        final URL url = getClass().getClassLoader().getResource(resource);
        return url == null ? "<absent>" : url.getProtocol();
    }

    private List<String> resourceUrls(String resource) {
        try {
            final List<String> urls = new ArrayList<>();
            final Enumeration<URL> found = getClass().getClassLoader().getResources(resource);
            while (found.hasMoreElements()) {
                urls.add(found.nextElement().toExternalForm());
            }
            return urls;
        } catch (IOException e) {
            return List.of("<unreadable: " + e.getMessage() + ">");
        }
    }
}
